from datetime import date
from typing import Literal

from pydantic import BaseModel


class TrendPoint(BaseModel):
    date: date
    value: float


class TrendBand(BaseModel):
    """Personal baseline: median and the range the analysis engine treats as usual (|robust z| < warning)."""

    median: float
    low: float
    high: float


class TrendLatest(BaseModel):
    date: date
    value: float
    robust_z: float | None
    # normal: inside the usual range / under the reference; changed: the analysis would flag it;
    # improved: outside the range in the helpful direction; unknown: no baseline or reference yet.
    status: Literal["normal", "changed", "improved", "unknown"]


class TrendMetric(BaseModel):
    metric: str
    domain: Literal["sleep", "behavior"]
    # clock: minutes from [anchor] (HH:MM, user's zone); minutes; ratio: 0..1.
    unit: Literal["clock", "minutes", "ratio"]
    anchor: str | None = None
    basis: Literal["personal_baseline", "reference_threshold"]
    points: list[TrendPoint]
    band: TrendBand | None = None
    reference: float | None = None
    latest: TrendLatest | None = None


class TrendResponse(BaseModel):
    """Day-by-day values of the analysed metrics with the personal baseline, for trend charts."""

    days: int
    start_date: date | None
    end_date: date | None
    baseline_status: Literal["ready", "collecting"]
    warning_z: float
    metrics: list[TrendMetric]
