from fastapi import APIRouter

from app.api.deps import CurrentUser, DbSession
from app.schemas.mission import MissionCompletionResponse, MissionResponse
from app.services.mission_service import active_assignments, complete_mission

router = APIRouter()


@router.get("/active", response_model=list[MissionResponse])
def active(db: DbSession, user: CurrentUser):
    return active_assignments(db, user.user_id)


@router.post("/{assignment_id}/complete", response_model=MissionCompletionResponse)
def complete(assignment_id: int, db: DbSession, user: CurrentUser):
    return complete_mission(db, user.user_id, assignment_id)
