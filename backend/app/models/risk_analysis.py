import uuid
from datetime import date, datetime

from sqlalchemy import BigInteger, Date, DateTime, ForeignKey, Integer, String, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class RiskAnalysis(Base):
    """Stored result of POST /analysis/refresh (spec §4.4)."""

    __tablename__ = "risk_analyses"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.user_id", ondelete="CASCADE"), index=True)
    baseline_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("baselines.id", ondelete="SET NULL"), nullable=True)
    window_days: Mapped[int] = mapped_column(Integer)
    reference_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    risk_level: Mapped[str] = mapped_column(String(32))
    sleep_state: Mapped[str] = mapped_column(String(32))
    behavior_state: Mapped[str] = mapped_column(String(32))
    risk_factors: Mapped[list] = mapped_column(JSONB)
    details: Mapped[dict] = mapped_column(JSONB)
    analysis_version: Mapped[str] = mapped_column(String(16))
    analyzed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), index=True)
