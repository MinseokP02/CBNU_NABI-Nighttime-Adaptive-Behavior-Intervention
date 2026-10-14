from sqlalchemy import BigInteger, Boolean, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class Intervention(Base):
    __tablename__ = "interventions"
    intervention_id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    category: Mapped[str] = mapped_column(String(64), unique=True)
    rule: Mapped[str] = mapped_column(String(128), unique=True)
    title: Mapped[str] = mapped_column(String(200))
    message_template: Mapped[str] = mapped_column(Text)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
