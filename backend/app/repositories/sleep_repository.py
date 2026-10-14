from datetime import date
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.sleep_record import SleepRecord


def list_range(db: Session, user_id: UUID, start: date, end: date) -> list[SleepRecord]:
    return list(db.scalars(select(SleepRecord).where(SleepRecord.user_id == user_id, SleepRecord.sleep_date.between(start, end)).order_by(SleepRecord.sleep_date)))
