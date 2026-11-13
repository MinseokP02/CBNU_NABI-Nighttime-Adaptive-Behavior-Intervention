"""Chat orchestration (spec §6 LLM request flow).

1) request already authenticated/validated by the route
2) load personal state: baseline, 7-day risk, approved intervention
3) safety pre-check happens in the LLM server; a local crisis guard covers LLM outages
4-5) LLM server builds the prompt, infers and validates structure/safety
6) store both messages with metadata and return
"""
import re
import time
import uuid
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.core.exceptions import AppError
from app.models.conversation import ChatMessage, Conversation
from app.models.user import User
from app.services import llm_client
from app.services.baseline_service import current_baseline
from app.services.intervention_service import detect_signals, recommend
from app.services.risk_service import analyze_risk

PERSONAS = {"friendly_supportive", "calm_gentle", "playful"}

# Used only when the LLM server is unreachable, so crisis messages still get help information.
_LOCAL_CRISIS = re.compile(r"자살|죽고\s*싶|자해|살고\s*싶지\s*않|목숨을?\s*끊|사라지고\s*싶|극단적\s*(인\s*)?선택")
LOCAL_CRISIS_REPLY = (
    "많이 힘든 마음을 이야기해 줘서 고마워. 지금 혼자 견디지 않았으면 좋겠어. "
    "자살예방상담전화 109(24시간), 정신건강위기상담 1577-0199에 바로 연락하거나, "
    "위급하면 119에 도움을 요청해 줘."
)


def persona_for(user: User) -> str:
    persona = (user.profile or {}).get("cat_persona")
    return persona if persona in PERSONAS else "friendly_supportive"


def _conversation(db: Session, user: User, conversation_id: str | None) -> Conversation:
    if conversation_id:
        row = db.get(Conversation, conversation_id)
        if row is None or row.user_id != user.user_id:
            raise AppError(404, "RESOURCE_NOT_FOUND", "Conversation was not found")
        return row
    row = Conversation(conversation_id=f"conv_{uuid.uuid4().hex[:16]}", user_id=user.user_id)
    db.add(row)
    db.flush()
    return row


def _history(db: Session, conversation_id: str, limit: int) -> list[dict]:
    rows = list(db.scalars(select(ChatMessage).where(ChatMessage.conversation_id == conversation_id).order_by(ChatMessage.id.desc()).limit(limit)))
    return [{"role": row.role, "content": row.content[:1000]} for row in reversed(rows)]


def _fallback(message: str, rec: dict) -> dict:
    if _LOCAL_CRISIS.search(message):
        return {"response": LOCAL_CRISIS_REPLY, "intent": "CRISIS", "emotion": "CONCERNED", "cat_action": "COMFORT", "intervention_category": None, "safety_status": "CRISIS", "source": "fallback"}
    return {"response": f"이야기해 줘서 고마워. {rec['message']}", "intent": None, "emotion": "CALM", "cat_action": "SIT", "intervention_category": rec["category"] if rec.get("intervention_id") else None, "safety_status": "PASS", "source": "fallback"}


def send_message(db: Session, user: User, message: str, conversation_id: str | None) -> dict:
    settings = get_settings()
    conversation = _conversation(db, user, conversation_id)
    history = _history(db, conversation.conversation_id, settings.llm_history_messages)

    baseline = current_baseline(db, user)
    risk = analyze_risk(db, user, 7, baseline)
    rec = recommend(db, risk, detect_signals(message))
    request_id = llm_client.new_request_id()

    db.add(ChatMessage(conversation_id=conversation.conversation_id, user_id=user.user_id, role="user", content=message, request_id=request_id))
    db.commit()  # Never hold a DB transaction open during the LLM call.

    started = time.perf_counter()
    try:
        result = llm_client.generate(
            message, llm_client.context_payload(risk, baseline), llm_client.intervention_payload(rec),
            history, persona_for(user), request_id,
        ).model_dump()
    except llm_client.LLMUnavailable:
        if settings.llm_fail_mode == "error" and not _LOCAL_CRISIS.search(message):
            raise AppError(503, "LLM_UNAVAILABLE", "The conversation service is temporarily unavailable") from None
        result = _fallback(message, rec)
    latency_ms = int((time.perf_counter() - started) * 1000)

    related = rec.get("intervention_id") if result.get("intervention_category") else None
    reply = ChatMessage(
        conversation_id=conversation.conversation_id, user_id=user.user_id, role="assistant",
        content=result["response"], intent=result.get("intent"), emotion=result["emotion"],
        cat_action=result["cat_action"], related_intervention_id=related,
        safety_status=result["safety_status"], source=result["source"], request_id=request_id, latency_ms=latency_ms,
    )
    db.add(reply)
    conversation.updated_at = datetime.now(UTC)
    db.commit()
    db.refresh(reply)
    return {
        "message_id": reply.message_id,
        "conversation_id": conversation.conversation_id,
        "reply": reply.content,
        "emotion": reply.emotion,
        "cat_action": reply.cat_action,
        "related_intervention_id": related,
        "safety_status": reply.safety_status,
        "intent": reply.intent,
        "source": reply.source,
    }


def history(db: Session, user: User, conversation_id: str | None, limit: int) -> dict:
    if conversation_id:
        conversation = db.get(Conversation, conversation_id)
        if conversation is None or conversation.user_id != user.user_id:
            raise AppError(404, "RESOURCE_NOT_FOUND", "Conversation was not found")
    else:
        conversation = db.scalar(select(Conversation).where(Conversation.user_id == user.user_id).order_by(Conversation.updated_at.desc(), Conversation.created_at.desc()).limit(1))
        if conversation is None:
            return {"conversation_id": None, "messages": []}
    rows = list(db.scalars(select(ChatMessage).where(ChatMessage.conversation_id == conversation.conversation_id).order_by(ChatMessage.id.desc()).limit(limit)))
    return {
        "conversation_id": conversation.conversation_id,
        "messages": [
            {
                "message_id": row.message_id, "role": row.role, "content": row.content, "emotion": row.emotion,
                "cat_action": row.cat_action, "related_intervention_id": row.related_intervention_id,
                "safety_status": row.safety_status, "created_at": row.created_at,
            }
            for row in reversed(rows)
        ],
    }
