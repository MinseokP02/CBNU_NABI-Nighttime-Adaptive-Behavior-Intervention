from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, EmailStr


class UserResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    user_id: UUID
    email: EmailStr
    isi_score: int
    timezone: str
    profile: dict | None
    created_at: datetime


class UserStateResponse(BaseModel):
    user: UserResponse
    baseline: dict
    risk: dict
    cat: dict
    active_missions: list[dict]
