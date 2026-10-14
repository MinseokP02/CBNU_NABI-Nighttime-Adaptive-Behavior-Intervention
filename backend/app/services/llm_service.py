import json

import httpx
from pydantic import BaseModel, Field, ValidationError

from app.core.config import get_settings

SYSTEM_PROMPT = """You are NABI's virtual cat companion. Convert a predefined behavioral intervention into one or two gentle and concise sentences. Do not diagnose insomnia, prescribe treatment or medication, change the intervention, or exaggerate risk. Do not present correlations as proven causes. Do not request personal sensitive information."""


class LLMMessage(BaseModel):
    message: str = Field(min_length=1, max_length=500)
    emotion: str = Field(max_length=50)
    animation_hint: str = Field(max_length=50)


def fallback_message(intervention: dict) -> dict:
    return {"message": intervention["message"], "emotion": "calm", "animation_hint": "gentle_nod", "source": "fallback"}


async def personalize(intervention: dict, risk: dict, cat_name: str, output_channel: str) -> dict:
    settings = get_settings()
    if not settings.llm_enabled or not settings.llm_base_url:
        return fallback_message(intervention)
    safe_context = {"sleep_state": risk["sleep_state"], "behavior_state": risk["behavior_state"], "risk_level": risk["risk_level"], "selected_intervention": {"category": intervention["category"], "title": intervention["title"], "message": intervention["message"]}, "cat_name": cat_name[:100], "output_channel": output_channel}
    headers = {"Authorization": f"Bearer {settings.llm_api_key}"} if settings.llm_api_key else {}
    payload = {"model": settings.llm_model, "messages": [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": json.dumps(safe_context, ensure_ascii=False)}], "temperature": 0.3, "response_format": {"type": "json_object"}}
    try:
        async with httpx.AsyncClient(timeout=settings.llm_timeout_seconds) as client:
            response = await client.post(f"{settings.llm_base_url.rstrip('/')}/chat/completions", headers=headers, json=payload)
            response.raise_for_status()
        content = response.json()["choices"][0]["message"]["content"]
        parsed = LLMMessage.model_validate(json.loads(content))
        return {**parsed.model_dump(), "source": "llm"}
    except (httpx.HTTPError, KeyError, TypeError, ValueError, json.JSONDecodeError, ValidationError):
        return fallback_message(intervention)
