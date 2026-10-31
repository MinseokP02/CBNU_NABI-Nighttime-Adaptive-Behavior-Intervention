from datetime import UTC, date, datetime, timedelta
from typing import Annotated
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Query, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.api.deps import CurrentUser, DbSession
from app.core.exceptions import AppError
from app.models.sleep_record import SleepRecord
from app.schemas.sleep import SleepRecordCreate, SleepRecordCreated, SleepRecordList

router = APIRouter()
FUTURE_TOLERANCE = timedelta(minutes=10)


def night_date(bedtime: datetime, timezone: str) -> date:
    """The night a bedtime belongs to: local date of (bedtime - 12h), so 01:00 counts as the previous night."""
    return (bedtime.astimezone(ZoneInfo(timezone)) - timedelta(hours=12)).date()


@router.post("", response_model=SleepRecordCreated, status_code=status.HTTP_201_CREATED)
def create_sleep(payload: SleepRecordCreate, db: DbSession, user: CurrentUser) -> dict:
    if night_date(payload.bedtime, user.timezone) != payload.sleep_date:
        raise AppError(422, "VALIDATION_ERROR", "sleep_date must be the local date of the night the bedtime belongs to")
    if payload.out_of_bed_time > datetime.now(UTC) + FUTURE_TOLERANCE:
        raise AppError(422, "VALIDATION_ERROR", "Sleep times cannot be in the future")
    computed = payload.computed()
    row = SleepRecord(user_id=user.user_id, **payload.model_dump(), **computed)
    db.add(row)
    try:
        db.commit()
        db.refresh(row)
    except IntegrityError:
        db.rollback()
        raise AppError(409, "DUPLICATE_RECORD", "A sleep record already exists for this date") from None
    return {"record_id": row.id, **computed, "status": "created"}


@router.get("", response_model=SleepRecordList)
def list_sleep(
    db: DbSession,
    user: CurrentUser,
    start_date: date | None = None,
    end_date: date | None = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 30,
) -> dict:
    if start_date and end_date and start_date > end_date:
        raise AppError(422, "VALIDATION_ERROR", "start_date must be on or before end_date")
    query = select(SleepRecord).where(SleepRecord.user_id == user.user_id)
    if start_date:
        query = query.where(SleepRecord.sleep_date >= start_date)
    if end_date:
        query = query.where(SleepRecord.sleep_date <= end_date)
    rows = list(db.scalars(query.order_by(SleepRecord.sleep_date.desc()).limit(limit)))
    return {"items": rows, "count": len(rows)}
