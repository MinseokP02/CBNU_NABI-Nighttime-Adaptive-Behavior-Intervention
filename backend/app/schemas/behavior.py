from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field, model_validator


class BehaviorRecordCreate(BaseModel):
    record_date: date
    screen_time: float = Field(ge=0)
    nighttime_screen_time: float = Field(ge=0)
    steps: int = Field(ge=0)
    app_usage: dict | None = None

    @model_validator(mode="after")
    def validate_screen_time(self) -> "BehaviorRecordCreate":
        if self.nighttime_screen_time > self.screen_time:
            raise ValueError("nighttime_screen_time cannot exceed screen_time")
        return self


class BehaviorRecordResponse(BehaviorRecordCreate):
    model_config = ConfigDict(from_attributes=True)
    id: int
    created_at: datetime
