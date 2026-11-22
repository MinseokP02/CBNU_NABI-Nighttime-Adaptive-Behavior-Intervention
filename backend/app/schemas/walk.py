from datetime import date, datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

MAX_WALK_SECONDS = 6 * 60 * 60
MAX_POINTS = 2000

# [lat, lon] with WGS84 bounds.
LatLon = Annotated[list[Annotated[float, Field(ge=-180, le=180)]], Field(min_length=2, max_length=2)]


def _check_points(name: str, points: list[list[float]]) -> None:
    for lat, _lon in points:
        if not -90 <= lat <= 90:
            raise ValueError(f"{name}: latitude out of range")


class WalkRecordCreate(BaseModel):
    """A finished walk. Times must carry a timezone offset."""

    model_config = ConfigDict(extra="forbid")
    started_at: datetime
    ended_at: datetime
    distance_m: float = Field(ge=0, le=100_000)
    target_minutes: int | None = Field(default=None, ge=5, le=180)
    planned_distance_m: float | None = Field(default=None, ge=0, le=100_000)
    planned_route: list[LatLon] = Field(default_factory=list, max_length=MAX_POINTS)
    path: list[LatLon] = Field(default_factory=list, max_length=MAX_POINTS)
    proposal_id: str | None = Field(default=None, max_length=64)
    source: str = Field(default="app", min_length=1, max_length=50)

    @model_validator(mode="after")
    def validate(self) -> "WalkRecordCreate":
        for name in ("started_at", "ended_at"):
            if getattr(self, name).tzinfo is None:
                raise ValueError(f"{name} must include a timezone offset (ISO 8601)")
        if self.ended_at <= self.started_at:
            raise ValueError("ended_at must be after started_at")
        if (self.ended_at - self.started_at).total_seconds() > MAX_WALK_SECONDS:
            raise ValueError("a walk cannot be longer than 6 hours")
        _check_points("planned_route", self.planned_route)
        _check_points("path", self.path)
        return self

    def duration_sec(self) -> int:
        return max(1, round((self.ended_at - self.started_at).total_seconds()))


class WalkRecordItem(BaseModel):
    """List item: no coordinates, so history lists stay small."""

    model_config = ConfigDict(from_attributes=True)
    walk_id: int = Field(validation_alias="id")
    started_at: datetime
    ended_at: datetime
    duration_sec: int
    distance_m: float
    target_minutes: int | None
    planned_distance_m: float | None
    proposal_id: str | None
    source: str
    created_at: datetime


class WalkRecordDetail(WalkRecordItem):
    planned_route: list[list[float]]
    path: list[list[float]]


class WalkRecordCreated(WalkRecordDetail):
    status: Literal["created"] = "created"


class WalkSummary(BaseModel):
    walks: int
    total_distance_m: float
    total_duration_sec: int


class WalkRecordList(BaseModel):
    items: list[WalkRecordItem]
    count: int
    summary: WalkSummary
    start_date: date | None = None
    end_date: date | None = None
