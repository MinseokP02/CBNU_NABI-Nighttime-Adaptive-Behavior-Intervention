from datetime import date, datetime, timedelta
from statistics import median
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.models.baseline import Baseline
from app.models.behavior_record import BehaviorRecord
from app.models.sleep_record import SleepRecord
from app.models.user import User
from app.repositories.analysis_repository import get_baselines, replace_baselines
from app.repositories.behavior_repository import list_range as behavior_range
from app.repositories.sleep_repository import list_range as sleep_range

METRICS = ("bedtime", "wake_time", "sol", "waso", "tst", "se", "screen_time", "nighttime_screen_time", "steps")


def _clock_minutes(value: datetime, timezone: str, anchor: str) -> float:
    local = value.astimezone(ZoneInfo(timezone))
    actual = local.hour * 60 + local.minute + local.second / 60
    hour, minute = (int(part) for part in anchor.split(":"))
    anchor_minutes = hour * 60 + minute
    return ((actual - anchor_minutes + 720) % 1440) - 720


def _values(sleep: list[SleepRecord], behavior: list[BehaviorRecord], user: User) -> dict[str, list[float]]:
    profile = user.profile or {}
    bedtime_anchor = str(profile.get("target_bedtime", "23:00"))
    wake_anchor = str(profile.get("target_wake_time", "07:00"))
    result = {metric: [] for metric in METRICS}
    for row in sleep:
        result["bedtime"].append(_clock_minutes(row.bedtime, user.timezone, bedtime_anchor))
        result["wake_time"].append(_clock_minutes(row.wake_time, user.timezone, wake_anchor))
        for metric in ("sol", "waso", "tst", "se"):
            result[metric].append(float(getattr(row, metric)))
    for row in behavior:
        for metric in ("screen_time", "nighttime_screen_time", "steps"):
            result[metric].append(float(getattr(row, metric)))
    return result


def _median_mad(values: list[float]) -> tuple[float, float]:
    center = float(median(values))
    return center, float(median(abs(value - center) for value in values))


def _latest_date(db: Session, user_id: UUID) -> date | None:
    sleep_latest = db.scalar(select(func.max(SleepRecord.sleep_date)).where(SleepRecord.user_id == user_id))
    behavior_latest = db.scalar(select(func.max(BehaviorRecord.record_date)).where(BehaviorRecord.user_id == user_id))
    return max(value for value in (sleep_latest, behavior_latest) if value is not None) if sleep_latest or behavior_latest else None


def build_baseline(db: Session, user_id: UUID) -> dict:
    settings = get_settings()
    user = db.get(User, user_id)
    latest = _latest_date(db, user_id)
    if user is None or latest is None:
        return {"status": "INSUFFICIENT_DATA", "metrics": []}
    window_end = latest - timedelta(days=settings.recent_window_days)
    earliest_sleep = db.scalar(select(func.min(SleepRecord.sleep_date)).where(SleepRecord.user_id == user_id))
    earliest_behavior = db.scalar(select(func.min(BehaviorRecord.record_date)).where(BehaviorRecord.user_id == user_id))
    starts = [value for value in (earliest_sleep, earliest_behavior) if value is not None]
    window_start = min(starts) if starts else window_end
    values = _values(sleep_range(db, user_id, window_start, window_end), behavior_range(db, user_id, window_start, window_end), user)
    rows: list[Baseline] = []
    response: list[dict] = []
    for metric, samples in values.items():
        if len(samples) >= settings.baseline_min_days:
            center, mad = _median_mad(samples)
            rows.append(Baseline(user_id=user_id, metric_name=metric, median=center, mad=mad, sample_count=len(samples), window_start=window_start, window_end=window_end))
            response.append({"metric_name": metric, "status": "READY", "median": center, "mad": mad, "sample_count": len(samples), "window_start": window_start, "window_end": window_end})
        else:
            response.append({"metric_name": metric, "status": "INSUFFICIENT_DATA", "sample_count": len(samples)})
    replace_baselines(db, user_id, rows)
    db.commit()
    status = "READY" if rows else "INSUFFICIENT_DATA"
    return {"status": status, "metrics": response}


def get_baseline(db: Session, user_id: UUID) -> dict:
    rows = get_baselines(db, user_id)
    if not rows:
        return build_baseline(db, user_id)
    return {"status": "READY", "metrics": [{"metric_name": row.metric_name, "status": "READY", "median": row.median, "mad": row.mad, "sample_count": row.sample_count, "window_start": row.window_start, "window_end": row.window_end} for row in rows]}


def refresh_baseline(db: Session, user_id: UUID) -> dict:
    return build_baseline(db, user_id)


def recent_metric_values(db: Session, user_id: UUID) -> tuple[dict[str, list[float]], int]:
    settings = get_settings()
    user = db.get(User, user_id)
    latest = _latest_date(db, user_id)
    if user is None or latest is None:
        return {metric: [] for metric in METRICS}, 0
    start = latest - timedelta(days=settings.recent_window_days - 1)
    sleeps = sleep_range(db, user_id, start, latest)
    behaviors = behavior_range(db, user_id, start, latest)
    return _values(sleeps, behaviors, user), max(len(sleeps), len(behaviors))
