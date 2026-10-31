from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

CatAction = Literal["IDLE", "SIT", "WALK", "SLEEP", "HAPPY", "COMFORT"]
Emotion = Literal["EMPATHETIC", "CALM", "ENCOURAGING", "HAPPY", "CONCERNED", "NEUTRAL"]
SafetyStatus = Literal["PASS", "MODIFIED", "CRISIS"]


class ClientContext(BaseModel):
    model_config = ConfigDict(extra="ignore")
    screen: str | None = Field(default=None, max_length=32)
    cat_animation: str | None = Field(default=None, max_length=32)


class ChatRequest(BaseModel):
    """API-15 (spec §4.6)."""

    model_config = ConfigDict(extra="forbid")
    message: str = Field(min_length=1, max_length=1000)
    conversation_id: str | None = Field(default=None, max_length=40)
    client_context: ClientContext | None = None


class ChatResponse(BaseModel):
    message_id: str
    conversation_id: str
    reply: str
    emotion: Emotion
    cat_action: CatAction
    related_intervention_id: int | None
    safety_status: SafetyStatus
    intent: str | None = None
    source: str


class ChatHistoryItem(BaseModel):
    message_id: str
    role: Literal["user", "assistant"]
    content: str
    emotion: str | None
    cat_action: str | None
    related_intervention_id: int | None
    safety_status: str | None
    created_at: datetime


class ChatHistoryResponse(BaseModel):
    """API-16."""

    conversation_id: str | None
    messages: list[ChatHistoryItem]
