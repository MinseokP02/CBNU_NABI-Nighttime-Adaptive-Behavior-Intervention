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
    __tablename__ = "sleep_records"
    __table_args__ = (
        UniqueConstraint("user_id", "sleep_date", name="uq_sleep_user_date"),
        CheckConstraint("wake_time > bedtime", name="ck_sleep_order"),
        CheckConstraint("sol >= 0 AND waso >= 0 AND tst >= 0", name="ck_sleep_durations"),
        CheckConstraint("se BETWEEN 0 AND 100", name="ck_sleep_se"),
        CheckConstraint("sleep_quality IS NULL OR sleep_quality BETWEEN 1 AND 5", name="ck_sleep_quality"),
    )
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.user_id", ondelete="CASCADE"), index=True)
    sleep_date: Mapped[date] = mapped_column(Date)
    bedtime: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    wake_time: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    sol: Mapped[float] = mapped_column(Float)
    waso: Mapped[float] = mapped_column(Float)
    tst: Mapped[float] = mapped_column(Float)
    se: Mapped[float] = mapped_column(Float)
    sleep_quality: Mapped[int | None] = mapped_column(Integer, nullable=True)
    source: Mapped[str] = mapped_column(String(50))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
