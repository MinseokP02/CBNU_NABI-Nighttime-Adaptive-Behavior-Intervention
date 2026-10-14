from fastapi import APIRouter, status
from sqlalchemy.exc import IntegrityError

from app.api.deps import CurrentUser, DbSession
from app.core.exceptions import AppError
from app.models.behavior_record import BehaviorRecord
from app.schemas.behavior import BehaviorRecordCreate, BehaviorRecordResponse

router = APIRouter()


@router.post("", response_model=BehaviorRecordResponse, status_code=status.HTTP_201_CREATED)
def create_behavior(payload: BehaviorRecordCreate, db: DbSession, user: CurrentUser) -> BehaviorRecord:
    row = BehaviorRecord(user_id=user.user_id, **payload.model_dump())
    db.add(row)
    try:
        db.commit(); db.refresh(row)
    except IntegrityError:
        db.rollback()
        raise AppError(409, "DUPLICATE_BEHAVIOR_DATE", "A behavior record already exists for this date") from None
    return row
