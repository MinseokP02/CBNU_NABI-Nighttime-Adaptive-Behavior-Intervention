from datetime import date
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.behavior_record import BehaviorRecord


def list_range(db: Session, user_id: UUID, start: date, end: date) -> list[BehaviorRecord]:
    return list(db.scalars(select(BehaviorRecord).where(BehaviorRecord.user_id == user_id, BehaviorRecord.record_date.between(start, end)).order_by(BehaviorRecord.record_date)))
