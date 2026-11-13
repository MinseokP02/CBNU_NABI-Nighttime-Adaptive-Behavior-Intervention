"""Weekly report (API-18): descriptive 7-day summary and change from the previous week."""
from datetime import date, timedelta
from statistics import mean

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models.mission_assignment import MissionAssignment
from app.models.mission_result import MissionResult
from app.models.reward_transaction import RewardTransaction
from app.models.user import User
from app.repositories.behavior_repository import list_range as behavior_range
from app.repositories.sleep_repository import list_range as sleep_range


def _avg(values: list, digits: int = 1) -> float | None:
    values = [v for v in values if v is not None]
    return round(mean(values), digits) if values else None


def _sleep_summary(rows) -> dict:
    return {
        "nights_logged": len(rows),
        "avg_total_sleep_time_min": _avg([r.total_sleep_time_min for r in rows]),
        "avg_time_in_bed_min": _avg([r.time_in_bed_min for r in rows]),
        "avg_sleep_efficiency": _avg([r.sleep_efficiency for r in rows], 4),
        "avg_sleep_onset_latency_min": _avg([r.sleep_onset_latency_min for r in rows]),
        "avg_wake_after_sleep_onset_min": _avg([r.wake_after_sleep_onset_min for r in rows]),
        "avg_sleep_quality": _avg([r.sleep_quality for r in rows]),
    }


def _delta(current: float | None, previous: float | None, digits: int = 1) -> float | None:
    return round(current - previous, digits) if current is not None and previous is not None else None


def weekly_report(db: Session, user: User, end_date: date) -> dict:
    start = end_date - timedelta(days=6)
    sleep = _sleep_summary(sleep_range(db, user.user_id, start, end_date))
    previous = _sleep_summary(sleep_range(db, user.user_id, start - timedelta(days=7), start - timedelta(days=1)))
    behavior_rows = behavior_range(db, user.user_id, start, end_date)
    behavior = {
        "days_logged": len(behavior_rows),
        "late_caffeine_days": sum(1 for r in behavior_rows if r.caffeine_after_cutoff),
        "relaxation_days": sum(1 for r in behavior_rows if r.relaxation_completed),
        "avg_screen_before_bed_min": _avg([r.screen_before_bed_min for r in behavior_rows]),
        "avg_nap_duration_min": _avg([r.nap_duration_min for r in behavior_rows]),
        "avg_exercise_duration_min": _avg([r.exercise_duration_min for r in behavior_rows]),
        "avg_stress_level": _avg([r.stress_level for r in behavior_rows]),
    }
    completed = db.execute(
        select(func.count(MissionResult.result_id), func.coalesce(func.sum(RewardTransaction.amount), 0))
        .join(MissionAssignment, MissionAssignment.assignment_id == MissionResult.assignment_id)
        .outerjoin(RewardTransaction, RewardTransaction.result_id == MissionResult.result_id)
        .where(
            MissionAssignment.user_id == user.user_id, MissionResult.verified.is_(True),
            func.date(func.timezone(user.timezone, MissionResult.verified_at)).between(start, end_date),
        )
    ).one()
    return {
        "start_date": start,
        "end_date": end_date,
        "sleep": sleep,
        "behavior": behavior,
        "missions": {"completed": completed[0], "exp_earned": int(completed[1])},
        "change_from_previous_week": {
            "avg_total_sleep_time_min": _delta(sleep["avg_total_sleep_time_min"], previous["avg_total_sleep_time_min"]),
            "avg_sleep_efficiency": _delta(sleep["avg_sleep_efficiency"], previous["avg_sleep_efficiency"], 4),
            "avg_sleep_onset_latency_min": _delta(sleep["avg_sleep_onset_latency_min"], previous["avg_sleep_onset_latency_min"]),
        },
    }
