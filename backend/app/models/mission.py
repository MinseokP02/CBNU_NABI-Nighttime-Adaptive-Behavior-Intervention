from sqlalchemy import BigInteger, Boolean, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class Mission(Base):
    __tablename__ = "missions"
    mission_id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    intervention_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("interventions.intervention_id"))
    title: Mapped[str] = mapped_column(String(200), unique=True)
    description: Mapped[str] = mapped_column(Text)
    verification_type: Mapped[str] = mapped_column(String(50))
    target_value: Mapped[float | None] = mapped_column(Float, nullable=True)
    reward_exp: Mapped[int] = mapped_column(Integer)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
