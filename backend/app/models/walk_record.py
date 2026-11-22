import uuid
from datetime import datetime

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class WalkRecord(Base):
    """One finished walk from the app's 산책 page, kept in the user's own history.

    Paths are lists of [lat, lon] pairs (already thinned by the app). They are personal location
    data: only the owner can read them, and deleting the user deletes them (ON DELETE CASCADE).
    """

    __tablename__ = "walk_records"
    __table_args__ = (
        CheckConstraint("ended_at > started_at", name="ck_walk_order"),
        CheckConstraint("duration_sec > 0 AND distance_m >= 0", name="ck_walk_amounts"),
        CheckConstraint("target_minutes IS NULL OR target_minutes BETWEEN 5 AND 180", name="ck_walk_target"),
    )
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.user_id", ondelete="CASCADE"), index=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    ended_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    duration_sec: Mapped[int] = mapped_column(Integer)
    distance_m: Mapped[float] = mapped_column(Float)
    target_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    planned_distance_m: Mapped[float | None] = mapped_column(Float, nullable=True)
    planned_route: Mapped[list] = mapped_column(JSONB, default=list)
    path: Mapped[list] = mapped_column(JSONB, default=list)
    # "나비의 제안" this walk answered (e.g. overlay walk suggestion), if any.
    proposal_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    source: Mapped[str] = mapped_column(String(50), default="app")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
