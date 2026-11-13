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
    String,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class SleepRecord(Base):
    """Sleep diary entry (spec §4.1). sleep_date is the local date of the night (bedtime - 12h)."""

    __tablename__ = "sleep_records"
    __table_args__ = (
        UniqueConstraint("user_id", "sleep_date", name="uq_sleep_user_date"),
        CheckConstraint("final_awakening > bedtime AND out_of_bed_time >= final_awakening", name="ck_sleep_order"),
        CheckConstraint(
            "sleep_onset_latency_min >= 0 AND wake_after_sleep_onset_min >= 0 "
            "AND total_sleep_time_min >= 0 AND time_in_bed_min > 0",
            name="ck_sleep_durations",
        ),
        CheckConstraint("sleep_efficiency BETWEEN 0 AND 1", name="ck_sleep_efficiency"),
        CheckConstraint("sleep_quality IS NULL OR sleep_quality BETWEEN 1 AND 5", name="ck_sleep_quality"),
    )
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.user_id", ondelete="CASCADE"), index=True)
    sleep_date: Mapped[date] = mapped_column(Date)
    bedtime: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    sleep_onset_latency_min: Mapped[int] = mapped_column(Integer)
    wake_after_sleep_onset_min: Mapped[int] = mapped_column(Integer)
    final_awakening: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    out_of_bed_time: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    time_in_bed_min: Mapped[int] = mapped_column(Integer)
    total_sleep_time_min: Mapped[int] = mapped_column(Integer)
    sleep_efficiency: Mapped[float] = mapped_column(Float)
    sleep_quality: Mapped[int | None] = mapped_column(Integer, nullable=True)
    source: Mapped[str] = mapped_column(String(50), default="app")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
