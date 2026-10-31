from datetime import date
from typing import Annotated

from fastapi import APIRouter, Query, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.api.deps import CurrentUser, DbSession
from app.core.exceptions import AppError
from app.models.behavior_record import BehaviorRecord
from app.schemas.behavior import BehaviorRecordCreate, BehaviorRecordCreated, BehaviorRecordList

router = APIRouter()


@router.post("", response_model=BehaviorRecordCreated, status_code=status.HTTP_201_CREATED)
def create_behavior(payload: BehaviorRecordCreate, db: DbSession, user: CurrentUser) -> BehaviorRecord:
    row = BehaviorRecord(user_id=user.user_id, **payload.model_dump())
    db.add(row)
    try:
        db.commit()
        db.refresh(row)
    except IntegrityError:
        db.rollback()
        raise AppError(409, "DUPLICATE_RECORD", "A behavior record already exists for this date") from None
    return row


@router.get("", response_model=BehaviorRecordList)
def list_behavior(
    db: DbSession,
    user: CurrentUser,
    start_date: date | None = None,
    end_date: date | None = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 30,
) -> dict:
    if start_date and end_date and start_date > end_date:
        raise AppError(422, "VALIDATION_ERROR", "start_date must be on or before end_date")
    query = select(BehaviorRecord).where(BehaviorRecord.user_id == user.user_id)
    if start_date:
        query = query.where(BehaviorRecord.record_date >= start_date)
    if end_date:
        query = query.where(BehaviorRecord.record_date <= end_date)
    rows = list(db.scalars(query.order_by(BehaviorRecord.record_date.desc()).limit(limit)))
    return {"items": rows, "count": len(rows)}
