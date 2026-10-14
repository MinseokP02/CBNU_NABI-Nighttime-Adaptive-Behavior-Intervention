from uuid import UUID

from fastapi import APIRouter
from sqlalchemy import select

from app.api.deps import CurrentUser, DbSession
from app.core.exceptions import AppError
from app.models.cat_profile import CatProfile
from app.models.mission_assignment import MissionAssignment
from app.schemas.user import UserStateResponse
from app.services.baseline_service import get_baseline
from app.services.risk_service import analyze_risk

router = APIRouter()


@router.get("/{user_id}/state", response_model=UserStateResponse)
def state(user_id: UUID, db: DbSession, user: CurrentUser):
    if user_id != user.user_id:
        raise AppError(403, "ACCESS_DENIED", "You cannot access another user's state")
    cat = db.scalar(select(CatProfile).where(CatProfile.user_id == user.user_id))
    assignments = list(db.scalars(select(MissionAssignment).where(MissionAssignment.user_id == user.user_id, MissionAssignment.status == "ASSIGNED")))
    return {"user": user, "baseline": get_baseline(db, user.user_id), "risk": analyze_risk(db, user.user_id), "cat": {"name": cat.name, "level": cat.level, "exp": cat.exp}, "active_missions": [{"assignment_id": row.assignment_id, "mission_id": row.mission_id, "deadline": row.deadline.isoformat()} for row in assignments]}
