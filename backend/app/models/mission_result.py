from datetime import datetime

from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKey, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class MissionResult(Base):
    __tablename__ = "mission_results"
    __table_args__ = (UniqueConstraint("assignment_id", name="uq_result_assignment"),)
    result_id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    assignment_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("mission_assignments.assignment_id", ondelete="CASCADE"))
    result: Mapped[dict] = mapped_column(JSONB)
    verified: Mapped[bool] = mapped_column(Boolean)
    verified_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
