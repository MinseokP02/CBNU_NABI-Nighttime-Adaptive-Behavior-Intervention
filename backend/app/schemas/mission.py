from datetime import datetime

from pydantic import BaseModel


class ActiveMission(BaseModel):
    """API-13."""

    assignment_id: int
    mission_id: int
    intervention_id: int
    category: str
    title: str
    description: str
    verification_type: str
    target_value: float | None
    reward_exp: int
    status: str
    assigned_at: datetime
    deadline: datetime


class MissionCompletionResponse(BaseModel):
    """API-14. `message`/`cat_action` come from the LLM mission dialogue (LLM-05) or its fallback."""

    mission_id: int
    assignment_id: int
    result_id: int
    verified: bool
    reward_exp: int
    cat_level: int
    cat_exp: int
    idempotent: bool = False
    message: str
    emotion: str
    cat_action: str
