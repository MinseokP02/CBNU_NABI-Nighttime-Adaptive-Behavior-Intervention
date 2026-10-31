from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel

SleepState = Literal["UNKNOWN", "STABLE", "IMPROVING", "DETERIORATING"]
BehaviorState = Literal["UNKNOWN", "HEALTHY", "CAUTION", "AT_RISK"]
RiskLevel = Literal["UNKNOWN", "LOW", "MODERATE", "HIGH"]


class FactorDetail(BaseModel):
    code: str
    domain: Literal["sleep", "behavior"]
    basis: Literal["personal_baseline", "reference_threshold"]
    severe: bool
    metric: str
    value: float | None
    robust_z: float | None = None


class MetricScore(BaseModel):
    recent_value: float
    baseline_median: float | None = None
    baseline_mad: float | None = None
    robust_z: float | None = None
    mad_floor_applied: bool = False
    sample_count: int


class RiskResponse(BaseModel):
    """API-10 (spec §4.4)."""

    window_days: int
    reference_date: date | None
    sleep_state: SleepState
    behavior_state: BehaviorState
    risk_level: RiskLevel
    risk_factors: list[str]
    analysis_version: str
    baseline_status: Literal["ready", "collecting"]
    recent_valid_days: dict[str, int]
    factor_details: list[FactorDetail]
    metrics: dict[str, MetricScore]


class RefreshResponse(BaseModel):
    """API-11."""

    baseline: dict
    risk: list[RiskResponse]
    refreshed_at: datetime
