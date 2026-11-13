"""Client for the internal LLM server (spec §5.1).

Sends only minimal, de-identified context (no email, user id, or raw diary rows; NFR-10).
Responses are re-validated here so a misbehaving LLM server cannot push unknown enums
to the app. Any failure raises LLMUnavailable; callers decide between fallback and 503.
"""
import logging
import uuid
from typing import Literal

import httpx
from pydantic import BaseModel, ValidationError

from app.core.config import get_settings
from app.schemas.chat import CatAction, Emotion, SafetyStatus

logger = logging.getLogger("nabi.llm_client")


class LLMUnavailable(Exception):
    pass


class GenerateResult(BaseModel):
    request_id: str
    response: str
    intent: str
    emotion: Emotion
    cat_action: CatAction
    intervention_category: str | None
    safety_status: SafetyStatus
    source: Literal["llm", "fallback", "safety"]


class InterventionResult(BaseModel):
    guidance: str
    emotion: Emotion
    cat_action: CatAction
    safety_status: SafetyStatus
    source: str


class MissionResult(BaseModel):
    message: str
    emotion: Emotion
    cat_action: CatAction
    safety_status: SafetyStatus
    source: str


def new_request_id() -> str:
    return f"req_{uuid.uuid4().hex[:16]}"


def _http_post(url: str, payload: dict, headers: dict, timeout: float) -> httpx.Response:
    with httpx.Client(timeout=timeout) as client:
        return client.post(url, json=payload, headers=headers)


def _post(path: str, payload: dict, model: type[BaseModel]):
    settings = get_settings()
    if not settings.llm_enabled or not settings.llm_server_url:
        raise LLMUnavailable("disabled")
    headers = {"X-Internal-Token": settings.llm_internal_token, "X-Request-ID": payload.get("request_id", "")}
    url = f"{settings.llm_server_url.rstrip('/')}{path}"
    try:
        response = _http_post(url, payload, headers, settings.llm_timeout_seconds)
        response.raise_for_status()
        return model.model_validate(response.json())
    except (httpx.HTTPError, ValueError, ValidationError) as exc:
        logger.warning("llm call failed path=%s request_id=%s reason=%s", path, payload.get("request_id"), type(exc).__name__)
        raise LLMUnavailable(type(exc).__name__) from None


def context_payload(risk: dict, baseline: dict) -> dict:
    metrics = baseline.get("metrics") or {}
    summary = None
    if baseline.get("status") == "ready":
        summary = {
            "median_sleep_min": metrics.get("sleep_duration", {}).get("median"),
            "median_efficiency": metrics.get("sleep_efficiency", {}).get("median"),
            "median_sleep_onset_min": metrics.get("sleep_onset_latency", {}).get("median"),
        }
    return {
        "sleep_state": risk["sleep_state"],
        "behavior_state": risk["behavior_state"],
        "risk_level": risk["risk_level"],
        "risk_factors": risk["risk_factors"][:12],
        "baseline_summary": summary,
    }


def intervention_payload(rec: dict | None) -> dict | None:
    if rec is None or rec.get("intervention_id") is None:
        return None
    return {
        "intervention_id": rec["intervention_id"],
        "category": rec["category"],
        "instruction": (rec.get("instruction") or rec["message"])[:300],
        "reason_codes": rec["reason_codes"][:12],
        "prohibited": rec.get("prohibited", [])[:12],
    }


def generate(user_message: str, context: dict, intervention: dict | None, history: list[dict], persona: str = "friendly_supportive", request_id: str | None = None) -> GenerateResult:
    payload = {
        "request_id": request_id or new_request_id(),
        "user_message": user_message,
        "context": context,
        "intervention": intervention,
        "recent_history": history,
        "cat_persona": persona,
    }
    return _post("/internal/v1/llm/generate", payload, GenerateResult)


def explain_intervention(context: dict, intervention: dict, persona: str = "friendly_supportive") -> InterventionResult:
    payload = {"request_id": new_request_id(), "intervention": intervention, "context": context, "cat_persona": persona}
    return _post("/internal/v1/llm/intervention", payload, InterventionResult)


def mission_message(phase: str, title: str, description: str, category: str | None, reward_exp: int | None, persona: str = "friendly_supportive") -> MissionResult:
    payload = {
        "request_id": new_request_id(), "phase": phase,
        "mission": {"title": title[:200], "description": description[:500], "category": category},
        "reward_exp": reward_exp, "cat_persona": persona,
    }
    return _post("/internal/v1/llm/mission", payload, MissionResult)
