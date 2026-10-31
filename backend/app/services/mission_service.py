"""Missions (API-13/14). The mission comes from the intervention engine's recommendation."""
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.core.exceptions import AppError
from app.models.behavior_record import BehaviorRecord
from app.models.cat_profile import CatProfile
from app.models.intervention import Intervention
from app.models.mission import Mission
from app.models.mission_assignment import MissionAssignment
from app.models.mission_result import MissionResult
from app.models.sleep_record import SleepRecord
from app.models.user import User
from app.services.baseline_service import anchors, clock_offset
from app.services.intervention_service import recommend
from app.services.reward_service import grant_exp
from app.services.risk_service import analyze_risk

MISSION_DURATION = timedelta(hours=24)
# verification_type -> (behavior field, check)
BEHAVIOR_CHECKS = {
    "NO_LATE_CAFFEINE": ("caffeine_after_cutoff", lambda value, target: value is False),
    "SCREEN_BEFORE_BED_MAX": ("screen_before_bed_min", lambda value, target: value is not None and value <= target),
    "NAP_MAX": ("nap_duration_min", lambda value, target: value is not None and value <= target),
    "EXERCISE_MIN": ("exercise_duration_min", lambda value, target: value is not None and value >= target),
    "RELAXATION_DONE": ("relaxation_completed", lambda value, target: value is True),
}


def _describe(assignment: MissionAssignment, mission: Mission, intervention: Intervention) -> dict:
    return {
        "assignment_id": assignment.assignment_id, "mission_id": mission.mission_id,
        "intervention_id": intervention.intervention_id, "category": intervention.category,
        "title": mission.title, "description": mission.description,
        "verification_type": mission.verification_type, "target_value": mission.target_value,
        "reward_exp": mission.reward_exp, "status": assignment.status,
        "assigned_at": assignment.assigned_at, "deadline": assignment.deadline,
    }


def list_active(db: Session, user: User) -> list[dict]:
    now = datetime.now(UTC)
    rows = db.execute(
        select(MissionAssignment, Mission, Intervention)
        .join(Mission, Mission.mission_id == MissionAssignment.mission_id)
        .join(Intervention, Intervention.intervention_id == Mission.intervention_id)
        .where(MissionAssignment.user_id == user.user_id, MissionAssignment.status == "ASSIGNED", MissionAssignment.deadline > now)
        .order_by(MissionAssignment.assigned_at)
    ).all()
    return [_describe(*row) for row in rows]


def active_assignments(db: Session, user: User) -> list[dict]:
    """Return active missions; if none, assign the mission of the current recommendation."""
    now = datetime.now(UTC)
    db.execute(
        update(MissionAssignment)
        .where(MissionAssignment.user_id == user.user_id, MissionAssignment.status == "ASSIGNED", MissionAssignment.deadline <= now)
        .values(status="EXPIRED")
    )
    db.commit()
    active = list_active(db, user)
    if active:
        return active
    rec = recommend(db, analyze_risk(db, user, 7))
    mission = db.get(Mission, rec["mission_id"]) if rec.get("mission_id") else None
    if mission is None:
        return []
    assignment = MissionAssignment(
        user_id=user.user_id, mission_id=mission.mission_id, status="ASSIGNED", assigned_at=now, deadline=now + MISSION_DURATION,
        verification_snapshot={
            "verification_type": mission.verification_type, "target_value": mission.target_value,
            "intervention_code": rec["code"], "reason_codes": rec["reason_codes"], "rule_ids": rec["rule_ids"],
            "integrity_limit": "Self-reported diary data; not tamper-proof",
        },
    )
    db.add(assignment)
    db.commit()
    return list_active(db, user)


def _in_window(created_at: datetime, assignment: MissionAssignment) -> bool:
    return assignment.assigned_at <= created_at <= assignment.deadline


def _verify(db: Session, user: User, assignment: MissionAssignment, mission: Mission) -> tuple[bool, dict]:
    tz = ZoneInfo(user.timezone)
    start_date = assignment.assigned_at.astimezone(tz).date()
    end_date = min(assignment.deadline, datetime.now(UTC)).astimezone(tz).date()
    kind, target = mission.verification_type, mission.target_value

    if kind == "SELF_REPORT":
        return True, {"self_report": True}

    if kind in {"SLEEP_DIARY", "BEDTIME_WINDOW", "WAKE_TIME_WINDOW"}:
        # The night that starts on the assignment day is logged with that day's sleep_date.
        row = db.scalar(
            select(SleepRecord)
            .where(SleepRecord.user_id == user.user_id, SleepRecord.sleep_date.between(start_date - timedelta(days=1), end_date))
            .order_by(SleepRecord.created_at.desc())
        )
        if row is None or not _in_window(row.created_at, assignment):
            return False, {"reason": "no sleep diary logged during the mission window"}
        if kind == "SLEEP_DIARY":
            return True, {"record_id": row.id}
        bed_anchor, wake_anchor = anchors(user)
        if kind == "BEDTIME_WINDOW":
            offset = clock_offset(row.bedtime, user.timezone, bed_anchor)
        else:
            offset = clock_offset(row.final_awakening, user.timezone, wake_anchor)
        tolerance = target or 30
        return abs(offset) <= tolerance, {"record_id": row.id, "offset_min": round(offset, 1), "tolerance_min": tolerance}

    if kind in BEHAVIOR_CHECKS:
        field, check = BEHAVIOR_CHECKS[kind]
        row = db.scalar(
            select(BehaviorRecord)
            .where(BehaviorRecord.user_id == user.user_id, BehaviorRecord.record_date.between(start_date, end_date))
            .order_by(BehaviorRecord.created_at.desc())
        )
        if row is None or not _in_window(row.created_at, assignment):
            return False, {"reason": "no behavior record logged during the mission window"}
        value = getattr(row, field)
        return bool(check(value, target)), {"record_id": row.id, "metric": field, "actual": value, "target": target}

    return False, {"reason": "unsupported verification type"}


def complete_mission(db: Session, user: User, mission_id: int) -> dict:
    try:
        assignment = db.scalar(
            select(MissionAssignment)
            .where(MissionAssignment.user_id == user.user_id, MissionAssignment.mission_id == mission_id)
            .order_by(MissionAssignment.assigned_at.desc())
            .limit(1)
            .with_for_update()
        )
        if assignment is None:
            raise AppError(404, "RESOURCE_NOT_FOUND", "No assignment exists for this mission")
        mission = db.get(Mission, mission_id)
        prior = db.scalar(select(MissionResult).where(MissionResult.assignment_id == assignment.assignment_id))
        cat = db.scalar(select(CatProfile).where(CatProfile.user_id == user.user_id).with_for_update())
        base = {"mission_id": mission_id, "assignment_id": assignment.assignment_id, "title": mission.title, "description": mission.description}
        if prior is not None:
            db.rollback()
            return {**base, "result_id": prior.result_id, "verified": prior.verified, "reward_exp": 0, "cat_level": cat.level, "cat_exp": cat.exp, "idempotent": True}
        if assignment.status != "ASSIGNED" or datetime.now(UTC) > assignment.deadline:
            raise AppError(409, "MISSION_EXPIRED", "Mission is no longer active")
        verified, evidence = _verify(db, user, assignment, mission)
        if not verified:
            raise AppError(409, "MISSION_NOT_VERIFIED", "Mission conditions are not satisfied", evidence)
        result = MissionResult(assignment_id=assignment.assignment_id, result=evidence, verified=True)
        db.add(result)
        db.flush()
        assignment.status = "COMPLETED"
        grant_exp(db, cat, user.user_id, result.result_id, mission.reward_exp)
        db.commit()
        return {**base, "result_id": result.result_id, "verified": True, "reward_exp": mission.reward_exp, "cat_level": cat.level, "cat_exp": cat.exp, "idempotent": False}
    except Exception:
        db.rollback()
        raise
