import uuid
from datetime import date, datetime

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class BehaviorRecord(Base):
    __tablename__ = "behavior_records"
    __table_args__ = (
        UniqueConstraint("user_id", "record_date", name="uq_behavior_user_date"),
        CheckConstraint("screen_time >= 0 AND nighttime_screen_time >= 0 AND nighttime_screen_time <= screen_time", name="ck_behavior_screen"),
        CheckConstraint("steps >= 0", name="ck_behavior_steps"),
    )
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.user_id", ondelete="CASCADE"), index=True)
    record_date: Mapped[date] = mapped_column(Date)
    screen_time: Mapped[float] = mapped_column(Float)
    nighttime_screen_time: Mapped[float] = mapped_column(Float)
    steps: Mapped[int] = mapped_column(Integer)
    app_usage: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
