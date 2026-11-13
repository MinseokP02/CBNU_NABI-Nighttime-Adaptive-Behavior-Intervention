"""Personal baseline (spec §4.3): median and MAD over 14 days of valid sleep diaries.

Window rule
- Rolling: the 14 days right before the latest 7-day analysis window, so recent days never
  leak into the baseline they are compared against.
- Early phase: until the user has that much history, the user's first 14 calendar days are used.
  Recent and baseline days can overlap in this phase, which makes detection more conservative.

Valid-day rule (spec leaves this to implementation)
- A night counts when time in bed is within [VALID_MIN_TIME_IN_BED_MIN, VALID_MAX_TIME_IN_BED_MIN].
- status = ready when valid_days >= BASELINE_MIN_VALID_DAYS, otherwise collecting.
"""
import hashlib
from datetime import date, datetime, timedelta
from statistics import median
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.config import Settings, get_settings
from app.models.baseline import Baseline
from app.models.behavior_record import BehaviorRecord
from app.models.sleep_record import SleepRecord
from app.models.user import User
from app.repositories.sleep_repository import list_range as sleep_range

RISK_WINDOW_MAX_DAYS = 7
SLEEP_METRICS = (
    "sleep_duration",
    "sleep_efficiency",
    "sleep_onset_latency",
    "wake_after_sleep_onset",
    "bedtime",
    "wake_time",
)
# Stabilization: MAD is never treated as smaller than a meaningful change for that metric.
# Prevents division by zero and inflated z when a user's routine is very regular.
MAD_FLOOR = {
    "sleep_duration": 15.0,
    "sleep_efficiency": 0.02,
    "sleep_onset_latency": 5.0,
    "wake_after_sleep_onset": 5.0,
    "bedtime": 15.0,
    "wake_time": 15.0,
}
DEFAULT_BEDTIME_ANCHOR = "23:00"
DEFAULT_WAKE_ANCHOR = "07:00"


def clock_offset(value: datetime, timezone: str, anchor: str) -> float:
    """Minutes from an anchor clock time, wrapped to [-720, 720) so 23:50 and 00:10 stay close."""
    local = value.astimezone(ZoneInfo(timezone))
    actual = local.hour * 60 + local.minute + local.second / 60
    hour, minute = (int(part) for part in anchor.split(":"))
    return ((actual - (hour * 60 + minute) + 720) % 1440) - 720


def anchors(user: User) -> tuple[str, str]:
    profile = user.profile or {}
    return str(profile.get("target_bedtime", DEFAULT_BEDTIME_ANCHOR)), str(profile.get("target_wake_time", DEFAULT_WAKE_ANCHOR))


def is_valid_night(row: SleepRecord, settings: Settings) -> bool:
    return settings.valid_min_time_in_bed_min <= row.time_in_bed_min <= settings.valid_max_time_in_bed_min


def sleep_metric_values(rows: list[SleepRecord], user: User, settings: Settings) -> dict[str, list[float]]:
    bed_anchor, wake_anchor = anchors(user)
    values: dict[str, list[float]] = {metric: [] for metric in SLEEP_METRICS}
    for row in rows:
        if not is_valid_night(row, settings):
            continue
        values["sleep_duration"].append(float(row.total_sleep_time_min))
        values["sleep_efficiency"].append(float(row.sleep_efficiency))
        values["sleep_onset_latency"].append(float(row.sleep_onset_latency_min))
        values["wake_after_sleep_onset"].append(float(row.wake_after_sleep_onset_min))
        values["bedtime"].append(clock_offset(row.bedtime, user.timezone, bed_anchor))
        values["wake_time"].append(clock_offset(row.final_awakening, user.timezone, wake_anchor))
    return values


def median_mad(values: list[float]) -> tuple[float, float]:
    center = float(median(values))
    return center, float(median(abs(value - center) for value in values))


def robust_z(value: float, center: float, mad: float, metric: str) -> tuple[float, bool]:
    """Robust Z = (x - median) / (1.4826 * MAD), with MAD floored and z clipped to ±10."""
    floor = MAD_FLOOR.get(metric, 0.0)
    effective = max(mad, floor)
    if effective <= 0:
        return 0.0, True
    z = (value - center) / (1.4826 * effective)
    return max(-10.0, min(10.0, z)), mad < floor


def reference_date(db: Session, user_id: UUID) -> date | None:
    latest_sleep = db.scalar(select(func.max(SleepRecord.sleep_date)).where(SleepRecord.user_id == user_id))
    latest_behavior = db.scalar(select(func.max(BehaviorRecord.record_date)).where(BehaviorRecord.user_id == user_id))
    candidates = [value for value in (latest_sleep, latest_behavior) if value is not None]
    return max(candidates) if candidates else None


def baseline_window(first_date: date, ref_date: date, window_days: int) -> tuple[date, date]:
    rolling_end = ref_date - timedelta(days=RISK_WINDOW_MAX_DAYS)
    rolling_start = rolling_end - timedelta(days=window_days - 1)
    if first_date <= rolling_start:
        return rolling_start, rolling_end
    return first_date, first_date + timedelta(days=window_days - 1)


def compute_baseline(rows: list[SleepRecord], user: User, settings: Settings) -> tuple[int, dict]:
    """Pure computation: (valid_days, metrics) for the rows of one baseline window."""
    values = sleep_metric_values(rows, user, settings)
    valid_days = len(values["sleep_duration"])
    metrics: dict = {}
    if valid_days >= settings.baseline_min_valid_days:
        for metric, samples in values.items():
            center, mad = median_mad(samples)
            metrics[metric] = {"median": round(center, 4), "mad": round(mad, 4), "sample_count": len(samples)}
    return valid_days, metrics


def _fingerprint(user: User, start: date | None, end: date | None, rows: list[SleepRecord], settings: Settings) -> str:
    """Identifies the exact inputs of a baseline; records are append-only so ids suffice."""
    bed_anchor, wake_anchor = anchors(user)
    parts = [
        str(user.user_id), str(start), str(end), settings.analysis_version,
        str(settings.baseline_min_valid_days), str(settings.valid_min_time_in_bed_min),
        str(settings.valid_max_time_in_bed_min), bed_anchor, wake_anchor,
        ",".join(str(row.id) for row in rows),
    ]
    return hashlib.sha256("|".join(parts).encode()).hexdigest()


def to_response(row: Baseline, settings: Settings) -> dict:
    return {
        "baseline_id": row.id,
        "window_days": row.window_days,
        "valid_days": row.valid_days,
        "required_valid_days": settings.baseline_min_valid_days,
        "status": row.status,
        "window_start": row.window_start,
        "window_end": row.window_end,
        "metrics": row.metrics,
        "analysis_version": row.analysis_version,
    }


def current_baseline(db: Session, user: User, persist: bool = True) -> dict:
    """Return the baseline for the user's current data, storing a new snapshot when inputs changed."""
    settings = get_settings()
    ref = reference_date(db, user.user_id)
    first = db.scalar(select(func.min(SleepRecord.sleep_date)).where(SleepRecord.user_id == user.user_id))
    if ref is None or first is None:
        start = end = None
        rows: list[SleepRecord] = []
    else:
        start, end = baseline_window(first, ref, settings.baseline_window_days)
        rows = sleep_range(db, user.user_id, start, end)
    valid_days, metrics = compute_baseline(rows, user, settings)
    status = "ready" if metrics else "collecting"
    fingerprint = _fingerprint(user, start, end, rows, settings)
    latest = db.scalar(select(Baseline).where(Baseline.user_id == user.user_id).order_by(Baseline.id.desc()).limit(1))
    if latest is not None and latest.fingerprint == fingerprint:
        return to_response(latest, settings)
    row = Baseline(
        user_id=user.user_id, status=status, window_start=start, window_end=end,
        window_days=settings.baseline_window_days, valid_days=valid_days, metrics=metrics,
        fingerprint=fingerprint, analysis_version=settings.analysis_version,
    )
    if not persist:
        response = to_response(row, settings)
        response["baseline_id"] = None
        return response
    db.add(row)
    db.commit()
    db.refresh(row)
    return to_response(row, settings)
