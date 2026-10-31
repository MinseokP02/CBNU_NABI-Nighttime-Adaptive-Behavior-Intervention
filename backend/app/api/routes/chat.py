from typing import Annotated

from fastapi import APIRouter, Query

from app.api.deps import CurrentUser, DbSession
from app.core.config import get_settings
from app.core.rate_limit import chat_limiter
from app.schemas.chat import ChatHistoryResponse, ChatRequest, ChatResponse
from app.services.chat_service import history, send_message

router = APIRouter()


@router.post("/messages", response_model=ChatResponse)
def post_message(payload: ChatRequest, db: DbSession, user: CurrentUser) -> dict:
    chat_limiter.check(str(user.user_id), get_settings().chat_rate_limit_per_minute)
    return send_message(db, user, payload.message, payload.conversation_id)


@router.get("/history", response_model=ChatHistoryResponse)
def get_history(
    db: DbSession,
    user: CurrentUser,
    conversation_id: str | None = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 50,
) -> dict:
    return history(db, user, conversation_id, limit)
