import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, String, Text, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class RiskAnalysis(Base):
    __tablename__ = "risk_analyses"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.user_id", ondelete="CASCADE"), index=True)
    risk_level: Mapped[str] = mapped_column(String(32))
    sleep_state: Mapped[str] = mapped_column(String(64))
    behavior_state: Mapped[str] = mapped_column(String(64))
    risk_factor: Mapped[str] = mapped_column(String(128))
    scores: Mapped[dict] = mapped_column(JSONB)
    explanation: Mapped[str] = mapped_column(Text)
    analyzed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), index=True)
