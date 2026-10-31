from datetime import date

from pydantic import BaseModel


class SleepSummary(BaseModel):
    nights_logged: int
    avg_total_sleep_time_min: float | None
    avg_time_in_bed_min: float | None
    avg_sleep_efficiency: float | None
    avg_sleep_onset_latency_min: float | None
    avg_wake_after_sleep_onset_min: float | None
    avg_sleep_quality: float | None


class BehaviorSummary(BaseModel):
    days_logged: int
    late_caffeine_days: int
    relaxation_days: int
    avg_screen_before_bed_min: float | None
    avg_nap_duration_min: float | None
    avg_exercise_duration_min: float | None
    avg_stress_level: float | None


class MissionSummary(BaseModel):
    completed: int
    exp_earned: int


class WeeklyReportResponse(BaseModel):
    """API-18. Descriptive summary only — no diagnosis."""

    start_date: date
    end_date: date
    sleep: SleepSummary
    behavior: BehaviorSummary
    missions: MissionSummary
    change_from_previous_week: dict[str, float | None]
