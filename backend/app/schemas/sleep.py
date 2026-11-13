from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

MAX_TIME_IN_BED_MIN = 24 * 60


def _minutes(start: datetime, end: datetime) -> float:
    return (end - start).total_seconds() / 60


class SleepRecordCreate(BaseModel):
    """API-05 sleep diary input (spec §4.1)."""

    model_config = ConfigDict(extra="forbid")
    sleep_date: date
    bedtime: datetime
    sleep_onset_latency_min: int = Field(ge=0, le=720)
    wake_after_sleep_onset_min: int = Field(ge=0, le=1440)
    final_awakening: datetime
    out_of_bed_time: datetime
    sleep_quality: int | None = Field(default=None, ge=1, le=5)
    source: str = Field(default="app", min_length=1, max_length=50)

    @model_validator(mode="after")
    def validate_time_order(self) -> "SleepRecordCreate":
        for name in ("bedtime", "final_awakening", "out_of_bed_time"):
            if getattr(self, name).tzinfo is None:
                raise ValueError(f"{name} must include a timezone offset (ISO 8601)")
        if self.final_awakening <= self.bedtime:
            raise ValueError("final_awakening must be after bedtime")
        if self.out_of_bed_time < self.final_awakening:
            raise ValueError("out_of_bed_time cannot be before final_awakening")
        if _minutes(self.bedtime, self.out_of_bed_time) > MAX_TIME_IN_BED_MIN:
            raise ValueError("time in bed cannot exceed 24 hours")
        if self.sleep_onset_latency_min + self.wake_after_sleep_onset_min > _minutes(self.bedtime, self.final_awakening):
            raise ValueError("sleep onset latency plus WASO cannot exceed bedtime-to-final-awakening span")
        return self

    def computed(self) -> dict:
        """Diary metrics.

        TIB = out_of_bed_time - bedtime
        TST = (final_awakening - bedtime) - SOL - WASO
        SE  = TST / TIB   (ratio 0-1; multiply by 100 for %)
        """
        time_in_bed = round(_minutes(self.bedtime, self.out_of_bed_time))
        total_sleep = round(_minutes(self.bedtime, self.final_awakening)) - self.sleep_onset_latency_min - self.wake_after_sleep_onset_min
        total_sleep = max(total_sleep, 0)
        return {
            "time_in_bed_min": time_in_bed,
            "total_sleep_time_min": total_sleep,
            "sleep_efficiency": round(total_sleep / time_in_bed, 4),
        }


class SleepRecordCreated(BaseModel):
    record_id: int
    total_sleep_time_min: int
    time_in_bed_min: int
    sleep_efficiency: float
    status: Literal["created"] = "created"


class SleepRecordItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    record_id: int = Field(validation_alias="id")
    sleep_date: date
    bedtime: datetime
    sleep_onset_latency_min: int
    wake_after_sleep_onset_min: int
    final_awakening: datetime
    out_of_bed_time: datetime
    time_in_bed_min: int
    total_sleep_time_min: int
    sleep_efficiency: float
    sleep_quality: int | None
    source: str
    created_at: datetime


class SleepRecordList(BaseModel):
    items: list[SleepRecordItem]
    count: int
