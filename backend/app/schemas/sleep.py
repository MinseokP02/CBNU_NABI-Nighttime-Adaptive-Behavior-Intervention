from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field, model_validator


class SleepRecordCreate(BaseModel):
    sleep_date: date
    bedtime: datetime
    wake_time: datetime
    sol: float = Field(ge=0)
    waso: float = Field(ge=0)
    tst: float = Field(ge=0)
    se: float = Field(ge=0, le=100)
    sleep_quality: int | None = Field(default=None, ge=1, le=5)
    source: str = Field(min_length=1, max_length=50)

    @model_validator(mode="after")
    def validate_sleep_math(self) -> "SleepRecordCreate":
        if self.bedtime.tzinfo is None or self.wake_time.tzinfo is None:
            raise ValueError("bedtime and wake_time must include timezone offsets")
        if self.wake_time <= self.bedtime:
            raise ValueError("wake_time must be after bedtime")
        time_in_bed = (self.wake_time - self.bedtime).total_seconds() / 60
        if self.tst > time_in_bed + 0.01:
            raise ValueError("tst cannot exceed time in bed")
        expected_se = self.tst / time_in_bed * 100
        if abs(self.se - expected_se) > 1.0:
            raise ValueError("se differs from tst/time_in_bed by more than 1 percentage point")
        return self


class SleepRecordResponse(SleepRecordCreate):
    model_config = ConfigDict(from_attributes=True)
    id: int
    created_at: datetime
