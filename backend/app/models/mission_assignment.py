import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, String, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class MissionAssignment(Base):
    __tablename__ = "mission_assignments"
    assignment_id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.user_id", ondelete="CASCADE"), index=True)
    mission_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("missions.mission_id"), index=True)
    status: Mapped[str] = mapped_column(String(32), default="ASSIGNED")
    assigned_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    deadline: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    verification_snapshot: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
