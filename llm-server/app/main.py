"""NABI internal LLM server (spec §5.1).

Only the backend calls this service. Every /internal endpoint except health requires
the shared X-Internal-Token header. Logs carry request id, latency and outcome but
never user text (NFR-09, NFR-10).
"""
import hmac
import logging
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, Header, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app import fallback, prompt_builder
from app.analyzer import classify_intent, expression_for
from app.config import Settings, get_settings
from app.inference import InferenceEngine
from app.safety import CRISIS_RESPONSE, check_input, check_output
from app.schemas import (
    CatAction,
    Emotion,
    GenerateRequest,
    GenerateResponse,
    HealthResponse,
    Intent,
    InterventionRequest,
    InterventionResponse,
    MissionPhase,
    MissionRequest,
    MissionResponse,
    SafetyCheckRequest,
    SafetyCheckResponse,
    SafetyStatus,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("nabi.llm")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    app.state.engine = InferenceEngine(get_settings())
    yield
    await app.state.engine.aclose()


app = FastAPI(title="NABI LLM Server", version="1.0.0", lifespan=lifespan)


class InternalAuthError(Exception):
    pass


@app.exception_handler(InternalAuthError)
async def _auth_error(_: Request, __: InternalAuthError) -> JSONResponse:
    return JSONResponse(status_code=401, content={"error": {"code": "UNAUTHORIZED", "message": "Internal token is missing or invalid", "details": None}})


@app.exception_handler(RequestValidationError)
async def _validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
    details = [{"loc": e.get("loc"), "msg": e.get("msg"), "type": e.get("type")} for e in exc.errors()]
    return JSONResponse(status_code=422, content={"error": {"code": "VALIDATION_ERROR", "message": "Request validation failed", "details": jsonable_encoder(details)}})


@app.exception_handler(Exception)
async def _internal_error(_: Request, exc: Exception) -> JSONResponse:
    logger.exception("unhandled error type=%s", type(exc).__name__)
    return JSONResponse(status_code=500, content={"error": {"code": "INTERNAL_ERROR", "message": "Internal server error", "details": None}})


@app.middleware("http")
async def request_log(request: Request, call_next):
    request_id = request.headers.get("X-Request-ID") or uuid.uuid4().hex
    started = time.perf_counter()
    response = await call_next(request)
    latency_ms = (time.perf_counter() - started) * 1000
    response.headers["X-Request-ID"] = request_id
    logger.info("request_id=%s method=%s path=%s status=%d latency_ms=%.1f", request_id, request.method, request.url.path, response.status_code, latency_ms)
    return response


def require_internal_token(
    settings: Annotated[Settings, Depends(get_settings)],
    x_internal_token: Annotated[str | None, Header()] = None,
) -> None:
    if not x_internal_token or not hmac.compare_digest(x_internal_token.encode(), settings.llm_internal_token.encode()):
        raise InternalAuthError()


Authorized = Depends(require_internal_token)
SettingsDep = Annotated[Settings, Depends(get_settings)]


def _engine(request: Request) -> InferenceEngine:
    return request.app.state.engine


@app.get("/internal/v1/llm/health", response_model=HealthResponse)
def health(settings: SettingsDep) -> HealthResponse:
    return HealthResponse(status="ok", provider=settings.llm_provider, model=settings.llm_model)


@app.post("/internal/v1/llm/generate", response_model=GenerateResponse, dependencies=[Authorized])
async def generate(req: GenerateRequest, request: Request, settings: SettingsDep) -> GenerateResponse:
    category = req.intervention.category if req.intervention else None
    # 1) Safety pre-check: crisis input never reaches the model.
    pre = check_input(req.user_message)
    if pre.status == SafetyStatus.CRISIS:
        logger.warning("request_id=%s crisis input routed to fixed response", req.request_id)
        return GenerateResponse(
            request_id=req.request_id, response=CRISIS_RESPONSE, intent=Intent.CRISIS,
            emotion=Emotion.CONCERNED, cat_action=CatAction.COMFORT, intervention_category=None,
            safety_status=SafetyStatus.CRISIS, source="safety", model=settings.llm_model,
        )
    # 2) LLM-01 intent hint, also used to build the deterministic fallback.
    hint = classify_intent(req.user_message)
    emotion, action = expression_for(hint)
    fallback_text = fallback.chat_reply(hint, category)
    mock_payload = {
        "response": fallback_text, "intent": hint.value, "emotion": emotion.value,
        "cat_action": action.value, "intervention_category": category.value if category else None,
    }
    # 3) Prompt + inference + structure/safety validation.
    messages = prompt_builder.build_generate_messages(req, settings.llm_history_turns)
    result = await _engine(request).generate_structured(messages, mock_payload=mock_payload, expected_category=category)
    if result.output is None:
        logger.info("request_id=%s fallback reason=%s", req.request_id, result.failure)
        return GenerateResponse(
            request_id=req.request_id, response=fallback_text, intent=hint, emotion=emotion,
            cat_action=action, intervention_category=category,
            safety_status=SafetyStatus.MODIFIED if result.unsafe else SafetyStatus.PASS,
            source="fallback", model=settings.llm_model,
        )
    out = result.output
    # Deterministic medical detection overrides the model's intent label.
    intent = Intent.MEDICAL_QUESTION if hint == Intent.MEDICAL_QUESTION else out.intent
    return GenerateResponse(
        request_id=req.request_id, response=out.response, intent=intent, emotion=out.emotion,
        cat_action=out.cat_action, intervention_category=category, safety_status=SafetyStatus.PASS,
        source=result.source, model=settings.llm_model,
    )


@app.post("/internal/v1/llm/intervention", response_model=InterventionResponse, dependencies=[Authorized])
async def intervention(req: InterventionRequest, request: Request, settings: SettingsDep) -> InterventionResponse:
    category = req.intervention.category
    text = fallback.intervention_guidance(category)
    mock_payload = {"response": text, "intent": Intent.OTHER.value, "emotion": Emotion.CALM.value, "cat_action": CatAction.SIT.value, "intervention_category": category.value}
    messages = prompt_builder.build_intervention_messages(req)
    result = await _engine(request).generate_structured(messages, mock_payload=mock_payload, expected_category=category)
    if result.output is None:
        return InterventionResponse(
            request_id=req.request_id, guidance=text, emotion=Emotion.CALM, cat_action=CatAction.SIT,
            intervention_category=category, safety_status=SafetyStatus.MODIFIED if result.unsafe else SafetyStatus.PASS,
            source="fallback", model=settings.llm_model,
        )
    out = result.output
    return InterventionResponse(
        request_id=req.request_id, guidance=out.response, emotion=out.emotion, cat_action=out.cat_action,
        intervention_category=category, safety_status=SafetyStatus.PASS, source="llm", model=settings.llm_model,
    )


@app.post("/internal/v1/llm/mission", response_model=MissionResponse, dependencies=[Authorized])
async def mission(req: MissionRequest, request: Request, settings: SettingsDep) -> MissionResponse:
    text, emotion, action = fallback.mission_message(req.phase, req.mission.title, req.reward_exp)
    mock_payload = {"response": text, "intent": Intent.MISSION_QUERY.value, "emotion": emotion.value, "cat_action": action.value}
    messages = prompt_builder.build_mission_messages(req)
    result = await _engine(request).generate_structured(messages, mock_payload=mock_payload, expected_category=None, check_category=False)
    if result.output is None:
        return MissionResponse(
            request_id=req.request_id, message=text, emotion=emotion, cat_action=action,
            safety_status=SafetyStatus.MODIFIED if result.unsafe else SafetyStatus.PASS,
            source="fallback", model=settings.llm_model,
        )
    out = result.output
    # Completion always uses the HAPPY reaction (spec §5.4).
    final_action = CatAction.HAPPY if req.phase == MissionPhase.COMPLETE else out.cat_action
    return MissionResponse(
        request_id=req.request_id, message=out.response, emotion=out.emotion, cat_action=final_action,
        safety_status=SafetyStatus.PASS, source="llm", model=settings.llm_model,
    )


@app.post("/internal/v1/llm/safety-check", response_model=SafetyCheckResponse, dependencies=[Authorized])
def safety_check(req: SafetyCheckRequest) -> SafetyCheckResponse:
    result = check_input(req.text) if req.direction == "INPUT" else check_output(req.text)
    return SafetyCheckResponse(request_id=req.request_id, safety_status=result.status, categories=result.categories, allowed=result.allowed)
