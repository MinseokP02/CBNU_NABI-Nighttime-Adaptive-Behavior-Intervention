"""Trend view of the analysed metrics (for charts): one value per night/day plus the personal baseline.

Uses exactly the analysis engine's definitions so a chart never disagrees with GET /analysis/risk:
- sleep metrics: valid nights only, clock times as minutes from the user's anchor (baseline_service);
- usual range = median ± warning_z × 1.4826 × max(MAD, MAD floor), i.e. |robust z| < warning;
- behavior metrics have no personal baseline in the engine, so they carry its reference threshold.
"""
from datetime import timedelta

from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.models.user import User
from app.repositories.behavior_repository import list_range as behavior_range
from app.repositories.sleep_repository import list_range as sleep_range
from app.services.baseline_service import (
    MAD_FLOOR,
    anchors,
    clock_offset,
    current_baseline,
    is_valid_night,
    reference_date,
    robust_z,
)
from app.services.risk_service import BEHAVIOR_RULES, SLEEP_RULES

MIN_DAYS, MAX_DAYS = 7, 60

# Chart order. harmful: +1 increase is harmful, -1 decrease, 0 shift either way (from SLEEP_RULES).
SLEEP_CHARTS = ("bedtime", "sleep_onset_latency", "sleep_duration", "sleep_efficiency", "wake_after_sleep_onset")
BEHAVIOR_CHARTS = ("screen_before_bed_min", "nap_duration_min", "exercise_duration_min")
_HARMFUL = {rule.metric: rule.harmful for rule in SLEEP_RULES}
_BEHAVIOR_RULE = {field: (comparison, threshold) for _code, field, aggregate, comparison, threshold in BEHAVIOR_RULES if aggregate == "median"}


def _sleep_value(row, metric: str, user: User, bed_anchor: str) -> float:
    if metric == "bedtime":
        return clock_offset(row.bedtime, user.timezone, bed_anchor)
    return float({
        "sleep_onset_latency": row.sleep_onset_latency_min,
        "sleep_duration": row.total_sleep_time_min,
        "sleep_efficiency": row.sleep_efficiency,
        "wake_after_sleep_onset": row.wake_after_sleep_onset_min,
    }[metric])


def _band(base: dict, metric: str, warning: float) -> dict:
    spread = warning * 1.4826 * max(base["mad"], MAD_FLOOR.get(metric, 0.0))
    low, high = base["median"] - spread, base["median"] + spread
    if metric != "bedtime":  # durations and efficiency cannot go below 0; efficiency tops out at 1
        low = max(low, 0.0)
    if metric == "sleep_efficiency":
        high = min(high, 1.0)
    return {"median": base["median"], "low": round(low, 4), "high": round(high, 4)}


def _sleep_status(z: float, harmful: int, warning: float) -> str:
    if harmful == 0:
        return "changed" if abs(z) >= warning else "normal"
    if z * harmful >= warning:
        return "changed"
    if -z * harmful >= warning:
        return "improved"
    return "normal"


def build_trends(db: Session, user: User, days: int = 14) -> dict:
    settings = get_settings()
    warning = settings.robust_z_warning_threshold
    baseline = current_baseline(db, user)
    ready = baseline["status"] == "ready"
    end = reference_date(db, user.user_id)
    start = end - timedelta(days=days - 1) if end else None
    sleeps = sleep_range(db, user.user_id, start, end) if end else []
    behaviors = behavior_range(db, user.user_id, start, end) if end else []
    bed_anchor, _ = anchors(user)

    metrics: list[dict] = []
    valid = [row for row in sleeps if is_valid_night(row, settings)]
    for metric in SLEEP_CHARTS:
        points = [{"date": row.sleep_date, "value": round(_sleep_value(row, metric, user, bed_anchor), 4)} for row in valid]
        base = baseline["metrics"].get(metric) if ready else None
        latest = None
        if points:
            last = points[-1]
            if base:
                z, _ = robust_z(last["value"], base["median"], base["mad"], metric)
                latest = {**last, "robust_z": round(z, 3), "status": _sleep_status(z, _HARMFUL[metric], warning)}
            else:
                latest = {**last, "robust_z": None, "status": "unknown"}
        metrics.append({
            "metric": metric, "domain": "sleep",
            "unit": "clock" if metric == "bedtime" else "ratio" if metric == "sleep_efficiency" else "minutes",
            "anchor": bed_anchor if metric == "bedtime" else None,
            "basis": "personal_baseline", "points": points,
            "band": _band(base, metric, warning) if base else None, "latest": latest,
        })

    for field in BEHAVIOR_CHARTS:
        comparison, threshold = _BEHAVIOR_RULE[field]
        points = [{"date": row.record_date, "value": float(getattr(row, field))} for row in behaviors if getattr(row, field) is not None]
        latest = None
        if points:
            value = points[-1]["value"]
            hit = value >= threshold if comparison == "ge" else value < threshold
            latest = {**points[-1], "robust_z": None, "status": "changed" if hit else "normal"}
        metrics.append({
            "metric": field, "domain": "behavior", "unit": "minutes", "anchor": None,
            "basis": "reference_threshold", "points": points, "reference": float(threshold), "latest": latest,
        })

    return {
        "days": days, "start_date": start, "end_date": end, "baseline_status": baseline["status"],
        "warning_z": warning, "metrics": metrics,
    }
