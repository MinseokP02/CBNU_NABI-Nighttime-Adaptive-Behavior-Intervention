from sqlalchemy import BigInteger, Boolean, String, Text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base


class Intervention(Base):
    """Approved CBT-I intervention catalog entry. The rule engine selects entries by `code`."""

    __tablename__ = "interventions"
    intervention_id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    code: Mapped[str] = mapped_column(String(128), unique=True)
    category: Mapped[str] = mapped_column(String(64), index=True)
    title: Mapped[str] = mapped_column(String(200))
    # instruction: what the LLM should explain. message_template: user-facing fallback sentence.
    instruction: Mapped[str] = mapped_column(Text, default="")
    message_template: Mapped[str] = mapped_column(Text)
    prohibited: Mapped[list] = mapped_column(JSONB, default=list)
    # Sensitive interventions (e.g. sleep restriction) need expert review; the engine never auto-selects them.
    requires_expert_review: Mapped[bool] = mapped_column(Boolean, default=False)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
