import uuid
from datetime import date, datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class BehaviorRecord(Base):
    """Daily behavior log (spec §4.2). NULL means "not recorded", not zero."""

    __tablename__ = "behavior_records"
    __table_args__ = (
        UniqueConstraint("user_id", "record_date", name="uq_behavior_user_date"),
        CheckConstraint(
            "(screen_before_bed_min IS NULL OR screen_before_bed_min BETWEEN 0 AND 1440) AND "
            "(nap_duration_min IS NULL OR nap_duration_min BETWEEN 0 AND 1440) AND "
            "(exercise_duration_min IS NULL OR exercise_duration_min BETWEEN 0 AND 1440)",
            name="ck_behavior_minutes",
        ),
        CheckConstraint("stress_level IS NULL OR stress_level BETWEEN 1 AND 5", name="ck_behavior_stress"),
    )
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.user_id", ondelete="CASCADE"), index=True)
    record_date: Mapped[date] = mapped_column(Date)
    caffeine_after_cutoff: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    screen_before_bed_min: Mapped[int | None] = mapped_column(Integer, nullable=True)
    nap_duration_min: Mapped[int | None] = mapped_column(Integer, nullable=True)
    exercise_duration_min: Mapped[int | None] = mapped_column(Integer, nullable=True)
    stress_level: Mapped[int | None] = mapped_column(Integer, nullable=True)
    relaxation_completed: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    source: Mapped[str] = mapped_column(String(50), default="app")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
