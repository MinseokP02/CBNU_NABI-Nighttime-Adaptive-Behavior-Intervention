from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

BEHAVIOR_FIELDS = (
    "caffeine_after_cutoff",
    "screen_before_bed_min",
    "nap_duration_min",
    "exercise_duration_min",
    "stress_level",
    "relaxation_completed",
)


class BehaviorRecordCreate(BaseModel):
    """API-07 behavior input (spec §4.2). Omitted fields are stored as 'not recorded'."""

    model_config = ConfigDict(extra="forbid")
    record_date: date
    caffeine_after_cutoff: bool | None = None
    screen_before_bed_min: int | None = Field(default=None, ge=0, le=1440)
    nap_duration_min: int | None = Field(default=None, ge=0, le=1440)
    exercise_duration_min: int | None = Field(default=None, ge=0, le=1440)
    stress_level: int | None = Field(default=None, ge=1, le=5)
    relaxation_completed: bool | None = None
    source: str = Field(default="app", min_length=1, max_length=50)

    @model_validator(mode="after")
    def at_least_one_value(self) -> "BehaviorRecordCreate":
        if all(getattr(self, name) is None for name in BEHAVIOR_FIELDS):
            raise ValueError("at least one behavior field must be provided")
        return self


class BehaviorRecordItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    record_id: int = Field(validation_alias="id")
    record_date: date
    caffeine_after_cutoff: bool | None
    screen_before_bed_min: int | None
    nap_duration_min: int | None
    exercise_duration_min: int | None
    stress_level: int | None
    relaxation_completed: bool | None
    source: str
    created_at: datetime


class BehaviorRecordCreated(BehaviorRecordItem):
    status: Literal["created"] = "created"


class BehaviorRecordList(BaseModel):
    items: list[BehaviorRecordItem]
    count: int
