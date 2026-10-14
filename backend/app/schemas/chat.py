from typing import Literal

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    output_channel: Literal["CHAT", "AMBIENT", "DASHBOARD"] = "CHAT"


class ChatResponse(BaseModel):
    message: str = Field(max_length=500)
    emotion: str
    animation_hint: str
    source: str
