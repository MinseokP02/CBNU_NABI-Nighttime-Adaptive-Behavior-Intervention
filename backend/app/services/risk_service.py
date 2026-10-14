from statistics import median
from uuid import UUID

from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.models.risk_analysis import RiskAnalysis
from app.repositories.analysis_repository import get_baselines
from app.services.baseline_service import build_baseline, recent_metric_values

DIRECTION = {"bedtime": 1, "sol": 1, "waso": 1, "tst": -1, "se": -1, "screen_time": 1, "nighttime_screen_time": 1, "steps": -1}
STATE = {
    "bedtime": ("IRREGULAR_BEDTIME", None), "sol": ("DELAYED_SLEEP_ONSET", None),
    "waso": ("INCREASED_NIGHT_AWAKENING", None), "tst": ("SHORT_SLEEP_DURATION", None),
    "se": ("LOW_SLEEP_EFFICIENCY", None), "screen_time": (None, "HIGH_SCREEN_TIME"),
    "nighttime_screen_time": (None, "PRE_BED_PHONE_OVERUSE"), "steps": (None, "LOW_DAILY_ACTIVITY"),
}
PRIORITY = ["sol", "waso", "tst", "se", "bedtime", "nighttime_screen_time", "screen_time", "steps"]


def analyze_risk(db: Session, user_id: UUID, persist: bool = True) -> dict:
    settings = get_settings()
    baselines = {row.metric_name: row for row in get_baselines(db, user_id)}
    if not baselines:
        build_baseline(db, user_id)
        baselines = {row.metric_name: row for row in get_baselines(db, user_id)}
    recent, available_days = recent_metric_values(db, user_id)
    scores: dict = {}
    flagged: list[tuple[str, float]] = []
    for metric, baseline in baselines.items():
        samples = recent.get(metric, [])
        if not samples:
            continue
        value = float(median(samples))
        deviation = value - baseline.median
        if baseline.mad == 0:
            score = 0.0 if deviation == 0 else None
            status = "OK" if deviation == 0 else "DEVIATION_UNSCORABLE"
        else:
            score = deviation / (1.4826 * baseline.mad) * DIRECTION.get(metric, 0)
            status = "SCORED"
            if score >= settings.robust_z_warning_threshold:
                flagged.append((metric, score))
        scores[metric] = {"recent_value": value, "baseline": baseline.median, "mad": baseline.mad, "deviation": deviation, "risk_direction": "increase" if DIRECTION.get(metric) == 1 else "decrease", "robust_z": score, "status": status, "warning_threshold": settings.robust_z_warning_threshold, "high_threshold": settings.robust_z_high_threshold, "sample_count": len(samples)}
    if available_days < settings.recent_window_days or not baselines:
        level = "INSUFFICIENT_DATA"
    elif any(score >= settings.robust_z_high_threshold for _, score in flagged):
        level = "HIGH"
    elif flagged:
        level = "WARNING"
    else:
        level = "STABLE"
    ordered = sorted(flagged, key=lambda item: (-item[1], PRIORITY.index(item[0])))
    sleep_state, behavior_state = "NORMAL_SLEEP", "NORMAL_BEHAVIOR"
    for metric, _ in ordered:
        sleep, behavior = STATE[metric]
        if sleep and sleep_state == "NORMAL_SLEEP": sleep_state = sleep
        if behavior and behavior_state == "NORMAL_BEHAVIOR": behavior_state = behavior
    if level == "INSUFFICIENT_DATA": sleep_state, behavior_state = "UNKNOWN", "UNKNOWN"
    factors = [metric for metric, _ in ordered]
    scores["risk_factors"] = factors
    explanation = "최근 측정값과 개인 기준선의 동반 변화를 비교했습니다. 이 결과는 진단이나 인과관계 판단이 아닙니다."
    result = {"risk_level": level, "sleep_state": sleep_state, "behavior_state": behavior_state, "risk_factors": factors, "scores": scores, "explanation": explanation, "available_days": available_days, "required_days": settings.recent_window_days}
    if persist:
        db.add(RiskAnalysis(user_id=user_id, risk_level=level, sleep_state=sleep_state, behavior_state=behavior_state, risk_factor=",".join(factors), scores=scores, explanation=explanation))
        db.commit()
    return result
