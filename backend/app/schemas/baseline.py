from datetime import date
from typing import Literal

from pydantic import BaseModel


class MetricBaseline(BaseModel):
    median: float
    mad: float
    sample_count: int


class BaselineResponse(BaseModel):
    """API-09 (spec §4.3)."""

    baseline_id: int | None
    window_days: int
    valid_days: int
    required_valid_days: int
    status: Literal["ready", "collecting"]
    window_start: date | None
    window_end: date | None
    metrics: dict[str, MetricBaseline]
    analysis_version: str
