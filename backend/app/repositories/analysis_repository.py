from uuid import UUID

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.models.baseline import Baseline


def replace_baselines(db: Session, user_id: UUID, values: list[Baseline]) -> None:
    db.execute(delete(Baseline).where(Baseline.user_id == user_id))
    db.add_all(values)


def get_baselines(db: Session, user_id: UUID) -> list[Baseline]:
    return list(db.scalars(select(Baseline).where(Baseline.user_id == user_id).order_by(Baseline.metric_name)))
