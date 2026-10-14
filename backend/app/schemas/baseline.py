from datetime import date

from pydantic import BaseModel


class BaselineMetric(BaseModel):
    metric_name: str
    status: str
    median: float | None = None
    mad: float | None = None
    sample_count: int
    window_start: date | None = None
    window_end: date | None = None


class BaselineResponse(BaseModel):
    status: str
    metrics: list[BaselineMetric]
