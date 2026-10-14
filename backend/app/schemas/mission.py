from datetime import datetime

from pydantic import BaseModel, ConfigDict


class MissionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    assignment_id: int
    mission_id: int
    status: str
    assigned_at: datetime
    deadline: datetime
    verification_snapshot: dict | None


class MissionCompletionResponse(BaseModel):
    assignment_id: int
    result_id: int
    verified: bool
    reward_exp: int
    cat_level: int
    cat_exp: int
    idempotent: bool = False
