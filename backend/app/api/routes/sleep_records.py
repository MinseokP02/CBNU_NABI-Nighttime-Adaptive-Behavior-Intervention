from zoneinfo import ZoneInfo

from fastapi import APIRouter, status
from sqlalchemy.exc import IntegrityError

from app.api.deps import CurrentUser, DbSession
from app.core.exceptions import AppError
from app.models.sleep_record import SleepRecord
from app.schemas.sleep import SleepRecordCreate, SleepRecordResponse

router = APIRouter()


@router.post("", response_model=SleepRecordResponse, status_code=status.HTTP_201_CREATED)
def create_sleep(payload: SleepRecordCreate, db: DbSession, user: CurrentUser) -> SleepRecord:
    if payload.wake_time.astimezone(ZoneInfo(user.timezone)).date() != payload.sleep_date:
        raise AppError(422, "INVALID_SLEEP_DATE", "sleep_date must be the local morning wake date")
    row = SleepRecord(user_id=user.user_id, **payload.model_dump())
    db.add(row)
    try:
        db.commit(); db.refresh(row)
    except IntegrityError:
        db.rollback()
        raise AppError(409, "DUPLICATE_SLEEP_DATE", "A sleep record already exists for this date") from None
    return row
