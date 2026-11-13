"""Rule-based risk analysis (spec §4.4) over the most recent 3 or 7 days.

Sleep factors fire on either basis:
- personal_baseline: Robust Z of the recent median vs the personal baseline crosses
  ROBUST_Z_WARNING_THRESHOLD in the harmful direction (requires a ready baseline).
- reference_threshold: commonly used CBT-I sleep-diary reference values
  (SOL > 30 min, WASO > 30 min, SE < 85 %). These work during the collecting phase too.
Behavior factors use sleep-hygiene reference values only.

States
- sleep_state: UNKNOWN (baseline collecting or too few recent nights) | DETERIORATING (any sleep
  factor) | IMPROVING (no factor and SE up / SOL or WASO down beyond the warning z) | STABLE
- behavior_state: UNKNOWN (too few recent logs) | HEALTHY (0 factors) | CAUTION (1) | AT_RISK (2+)
- risk_level: HIGH (3+ sleep factors, or 2+ with one severe) | MODERATE (any sleep factor or
  behavior AT_RISK) | LOW | UNKNOWN (no usable recent data)
These are change-detection rules for choosing an intervention, not a diagnosis.
"""
import math
from dataclasses import dataclass
from datetime import date, timedelta
from statistics import median
from uuid import UUID

from sqlalchemy.orm import Session

from app.core.config import Settings, get_settings
from app.models.behavior_record import BehaviorRecord
from app.models.risk_analysis import RiskAnalysis
from app.models.sleep_record import SleepRecord
from app.models.user import User
from app.repositories.behavior_repository import list_range as behavior_range
from app.repositories.sleep_repository import list_range as sleep_range
from app.services.baseline_service import (
    current_baseline,
    reference_date,
    robust_z,
    sleep_metric_values,
)

ALLOWED_WINDOWS = (3, 7)


@dataclass(frozen=True)
class SleepRule:
    code: str
    metric: str
    harmful: int  # +1: increase is harmful, -1: decrease is harmful, 0: shift either way (regularity)
    reference: float | None = None
    reference_severe: float | None = None


SLEEP_RULES = (
    SleepRule("PROLONGED_SLEEP_ONSET", "sleep_onset_latency", +1, 30, 60),
    SleepRule("FREQUENT_NIGHT_AWAKENING", "wake_after_sleep_onset", +1, 30, 60),
    SleepRule("LOW_SLEEP_EFFICIENCY", "sleep_efficiency", -1, 0.85, 0.75),
    SleepRule("SHORT_SLEEP_DURATION", "sleep_duration", -1),
    SleepRule("IRREGULAR_BEDTIME", "bedtime", 0),
    SleepRule("IRREGULAR_WAKE_TIME", "wake_time", 0),
)
SLEEP_ORDER = [rule.code for rule in SLEEP_RULES] + ["POOR_SLEEP_QUALITY"]

# (code, field, aggregate, comparison, threshold)
BEHAVIOR_RULES = (
    ("LATE_CAFFEINE", "caffeine_after_cutoff", "ratio_true", "ge", 0.25),
    ("EXCESSIVE_PRE_BED_SCREEN", "screen_before_bed_min", "median", "ge", 60),
    ("LONG_DAYTIME_NAP", "nap_duration_min", "median", "ge", 30),
    ("HIGH_STRESS", "stress_level", "median", "ge", 4),
    ("LOW_PHYSICAL_ACTIVITY", "exercise_duration_min", "median", "lt", 10),
)


def min_recent_days(window: int) -> int:
    return math.ceil(window / 2)


def _harmful_z(z: float, harmful: int) -> float:
    return abs(z) if harmful == 0 else z * harmful


def _sleep_factors(values: dict[str, list[float]], baseline: dict, settings: Settings, quality: list[int]) -> tuple[list[dict], dict, bool]:
    ready = baseline["status"] == "ready"
    metrics_out: dict = {}
    factors: list[dict] = []
    improving = False
    for rule in SLEEP_RULES:
        samples = values[rule.metric]
        if not samples:
            continue
        recent = float(median(samples))
        score: dict = {"recent_value": round(recent, 4), "sample_count": len(samples)}
        z = None
        if ready and rule.metric in baseline["metrics"]:
            base = baseline["metrics"][rule.metric]
            z, floored = robust_z(recent, base["median"], base["mad"], rule.metric)
            score.update(baseline_median=base["median"], baseline_mad=base["mad"], robust_z=round(z, 3), mad_floor_applied=floored)
        metrics_out[rule.metric] = score

        harmful_z = _harmful_z(z, rule.harmful) if z is not None else None
        z_hit = harmful_z is not None and harmful_z >= settings.robust_z_warning_threshold
        reference_hit = False
        reference_severe = False
        if rule.reference is not None:
            if rule.harmful > 0:
                reference_hit, reference_severe = recent > rule.reference, recent > (rule.reference_severe or math.inf)
            else:
                reference_hit, reference_severe = recent < rule.reference, recent < (rule.reference_severe or -math.inf)
        if rule.harmful == 0 and len(samples) >= 2:
            # Night-to-night spread of clock times within the window.
            reference_hit = (max(samples) - min(samples)) >= settings.irregular_schedule_range_min
        if z_hit or reference_hit:
            severe = (harmful_z is not None and harmful_z >= settings.robust_z_high_threshold) or reference_severe
            factors.append({
                "code": rule.code, "domain": "sleep",
                "basis": "personal_baseline" if z_hit else "reference_threshold",
                "severe": bool(severe), "metric": rule.metric, "value": round(recent, 4),
                "robust_z": round(z, 3) if z is not None else None,
            })
        elif z is not None and rule.metric in {"sleep_efficiency", "sleep_onset_latency", "wake_after_sleep_onset"}:
            if -_harmful_z(z, rule.harmful) >= settings.robust_z_warning_threshold:
                improving = True
    if quality:
        recent_quality = float(median(quality))
        metrics_out["sleep_quality"] = {"recent_value": recent_quality, "sample_count": len(quality)}
        if recent_quality <= 2:
            factors.append({"code": "POOR_SLEEP_QUALITY", "domain": "sleep", "basis": "reference_threshold", "severe": False, "metric": "sleep_quality", "value": recent_quality, "robust_z": None})
    return factors, metrics_out, improving


def _behavior_factors(rows: list[BehaviorRecord], min_days: int) -> tuple[list[dict], dict, bool]:
    factors: list[dict] = []
    metrics_out: dict = {}
    any_enough = False
    for code, field, aggregate, comparison, threshold in BEHAVIOR_RULES:
        samples = [getattr(row, field) for row in rows if getattr(row, field) is not None]
        if len(samples) < min_days:
            continue
        any_enough = True
        value = sum(1 for s in samples if s) / len(samples) if aggregate == "ratio_true" else float(median(samples))
        metrics_out[field] = {"recent_value": round(value, 4), "sample_count": len(samples)}
        hit = value >= threshold if comparison == "ge" else value < threshold
        if hit:
            factors.append({"code": code, "domain": "behavior", "basis": "reference_threshold", "severe": False, "metric": field, "value": round(value, 4), "robust_z": None})
    return factors, metrics_out, any_enough


def assess(
    sleep_rows: list[SleepRecord],
    behavior_rows: list[BehaviorRecord],
    baseline: dict,
    user: User,
    settings: Settings,
    window: int,
    ref_date: date | None,
) -> dict:
    """Pure rule evaluation for one analysis window."""
    min_days = min_recent_days(window)
    values = sleep_metric_values(sleep_rows, user, settings)
    valid_nights = len(values["sleep_duration"])
    quality = [row.sleep_quality for row in sleep_rows if row.sleep_quality is not None]

    sleep_factors: list[dict] = []
    metrics: dict = {}
    improving = False
    if valid_nights >= min_days:
        sleep_factors, metrics, improving = _sleep_factors(values, baseline, settings, quality if len(quality) >= min_days else [])
    behavior_factors, behavior_metrics, behavior_enough = _behavior_factors(behavior_rows, min_days)
    metrics.update(behavior_metrics)

    if valid_nights < min_days or baseline["status"] != "ready":
        sleep_state = "UNKNOWN"
    elif sleep_factors:
        sleep_state = "DETERIORATING"
    elif improving:
        sleep_state = "IMPROVING"
    else:
        sleep_state = "STABLE"

    if not behavior_enough:
        behavior_state = "UNKNOWN"
    else:
        behavior_state = {0: "HEALTHY", 1: "CAUTION"}.get(len(behavior_factors), "AT_RISK")

    severe = any(f["severe"] for f in sleep_factors)
    if len(sleep_factors) >= 3 or (len(sleep_factors) >= 2 and severe):
        risk_level = "HIGH"
    elif sleep_factors or behavior_state == "AT_RISK":
        risk_level = "MODERATE"
    elif valid_nights >= min_days or behavior_enough:
        risk_level = "LOW"
    else:
        risk_level = "UNKNOWN"

    sleep_factors.sort(key=lambda f: (not f["severe"], SLEEP_ORDER.index(f["code"])))
    ordered = sleep_factors + behavior_factors
    return {
        "window_days": window,
        "reference_date": ref_date,
        "sleep_state": sleep_state,
        "behavior_state": behavior_state,
        "risk_level": risk_level,
        "risk_factors": [f["code"] for f in ordered],
        "analysis_version": settings.analysis_version,
        "baseline_status": baseline["status"],
        "recent_valid_days": {"sleep": valid_nights, "behavior": len(behavior_rows)},
        "factor_details": ordered,
        "metrics": metrics,
    }


def analyze_risk(db: Session, user: User, window: int = 7, baseline: dict | None = None) -> dict:
    if window not in ALLOWED_WINDOWS:
        raise ValueError("window must be 3 or 7")
    settings = get_settings()
    baseline = baseline or current_baseline(db, user)
    ref = reference_date(db, user.user_id)
    if ref is None:
        return assess([], [], baseline, user, settings, window, None)
    start = ref - timedelta(days=window - 1)
    return assess(sleep_range(db, user.user_id, start, ref), behavior_range(db, user.user_id, start, ref), baseline, user, settings, window, ref)


def persist_risk(db: Session, user_id: UUID, result: dict, baseline_id: int | None) -> RiskAnalysis:
    row = RiskAnalysis(
        user_id=user_id, baseline_id=baseline_id, window_days=result["window_days"], reference_date=result["reference_date"],
        risk_level=result["risk_level"], sleep_state=result["sleep_state"], behavior_state=result["behavior_state"],
        risk_factors=result["risk_factors"], details={"factor_details": result["factor_details"], "metrics": result["metrics"]},
        analysis_version=result["analysis_version"],
    )
    db.add(row)
    return row
