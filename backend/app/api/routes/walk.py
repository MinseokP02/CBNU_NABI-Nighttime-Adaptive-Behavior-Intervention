"""Walk history (산책 내역): finished walks from the app's 산책 page, per user."""
from datetime import date, datetime, time, timedelta
from typing import Annotated
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Query, status
from sqlalchemy import func, select

from app.api.deps import CurrentUser, DbSession
from app.core.exceptions import AppError
from app.models.walk_record import WalkRecord
from app.schemas.walk import WalkRecordCreate, WalkRecordCreated, WalkRecordDetail, WalkRecordList

router = APIRouter()


@router.post("", response_model=WalkRecordCreated, status_code=status.HTTP_201_CREATED)
def create_walk(payload: WalkRecordCreate, db: DbSession, user: CurrentUser) -> WalkRecord:
    data = payload.model_dump()
    row = WalkRecord(user_id=user.user_id, duration_sec=payload.duration_sec(), **data)
    db.add(row)
    db.commit()
    db.refresh(row)
    return row


def _local_bounds(user_tz: str, start: date | None, end: date | None) -> tuple[datetime | None, datetime | None]:
    """Date filters are the user's local days."""
    zone = ZoneInfo(user_tz)
    lo = datetime.combine(start, time.min, zone) if start else None
    hi = datetime.combine(end + timedelta(days=1), time.min, zone) if end else None
    return lo, hi


@router.get("", response_model=WalkRecordList)
def list_walks(
    db: DbSession,
    user: CurrentUser,
    start_date: date | None = None,
    end_date: date | None = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 30,
) -> dict:
    if start_date and end_date and start_date > end_date:
        raise AppError(422, "VALIDATION_ERROR", "start_date must be on or before end_date")
    lo, hi = _local_bounds(user.timezone, start_date, end_date)
    conditions = [WalkRecord.user_id == user.user_id]
    if lo:
        conditions.append(WalkRecord.started_at >= lo)
    if hi:
        conditions.append(WalkRecord.started_at < hi)
    rows = list(db.scalars(select(WalkRecord).where(*conditions).order_by(WalkRecord.started_at.desc()).limit(limit)))
    walks, distance, duration = db.execute(
        select(func.count(WalkRecord.id), func.coalesce(func.sum(WalkRecord.distance_m), 0.0),
               func.coalesce(func.sum(WalkRecord.duration_sec), 0)).where(*conditions)
    ).one()
    return {
        "items": rows, "count": len(rows),
        "summary": {"walks": walks, "total_distance_m": round(float(distance), 1), "total_duration_sec": int(duration)},
        "start_date": start_date, "end_date": end_date,
    }


@router.get("/{walk_id}", response_model=WalkRecordDetail)
def get_walk(walk_id: int, db: DbSession, user: CurrentUser) -> WalkRecord:
    row = db.get(WalkRecord, walk_id)
    if row is None or row.user_id != user.user_id:  # other users' walks look the same as missing ones
        raise AppError(404, "RESOURCE_NOT_FOUND", "Walk not found")
    return row


@router.delete("/{walk_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_walk(walk_id: int, db: DbSession, user: CurrentUser) -> None:
    row = db.get(WalkRecord, walk_id)
    if row is None or row.user_id != user.user_id:
        raise AppError(404, "RESOURCE_NOT_FOUND", "Walk not found")
    db.delete(row)
    db.commit()
