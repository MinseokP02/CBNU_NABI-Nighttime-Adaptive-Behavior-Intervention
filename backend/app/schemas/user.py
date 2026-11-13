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
    """API-04: current sleep/behavior state summary for the home screen."""

    baseline_status: str
    baseline_valid_days: int
    window_days: int
    sleep_state: str
    behavior_state: str
    risk_level: str
    risk_factors: list[str]
    cat: dict
    active_missions: list[dict]
