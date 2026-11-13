from typing import Literal

from pydantic import BaseModel

Category = Literal["STIMULUS_CONTROL", "SLEEP_SCHEDULE", "RELAXATION", "COGNITIVE", "SLEEP_HYGIENE"]
Priority = Literal["HIGH", "MEDIUM", "LOW"]


class AlternativeIntervention(BaseModel):
    intervention_id: int | None
    code: str
    category: Category
    priority: Priority
    reason_codes: list[str]
    mission_id: int | None


class RecommendationResponse(BaseModel):
    """API-12 (spec §4.5). Top-level fields follow the spec example; the rest is supporting detail."""

    intervention_id: int | None
    category: Category
    priority: Priority
    reason_codes: list[str]
    mission_id: int | None
    requires_llm_explanation: bool
    code: str
    title: str
    message: str
    rule_ids: list[str]
    risk_level: str
    window_days: int
    alternatives: list[AlternativeIntervention]
    explanation: str | None = None
    explanation_source: str | None = None
