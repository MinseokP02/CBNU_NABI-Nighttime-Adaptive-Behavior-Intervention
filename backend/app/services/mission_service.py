from datetime import UTC, datetime, timedelta
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.exceptions import AppError
from app.models.behavior_record import BehaviorRecord
from app.models.cat_profile import CatProfile
from app.models.mission import Mission
from app.models.mission_assignment import MissionAssignment
from app.models.mission_result import MissionResult
from app.models.sleep_record import SleepRecord
from app.services.reward_service import grant_exp


def active_assignments(db: Session, user_id: UUID) -> list[MissionAssignment]:
    rows = list(db.scalars(select(MissionAssignment).where(MissionAssignment.user_id == user_id, MissionAssignment.status == "ASSIGNED", MissionAssignment.deadline > datetime.now(UTC)).order_by(MissionAssignment.assigned_at)))
    if rows:
        return rows
    mission = db.scalar(select(Mission).where(Mission.active.is_(True)).order_by(Mission.mission_id).limit(1))
    if mission is None:
        return []
    now = datetime.now(UTC)
    assignment = MissionAssignment(user_id=user_id, mission_id=mission.mission_id, status="ASSIGNED", assigned_at=now, deadline=now + timedelta(days=1), verification_snapshot={"verification_type": mission.verification_type, "target_value": mission.target_value, "window_start": now.isoformat(), "window_end": (now + timedelta(days=1)).isoformat(), "integrity_limit": "MVP device data is not tamper-proof"})
    db.add(assignment)
    db.commit()
    db.refresh(assignment)
    return [assignment]


def _verify(db: Session, assignment: MissionAssignment, mission: Mission) -> tuple[bool, dict]:
    start_date = assignment.assigned_at.date()
    end_date = min(assignment.deadline, datetime.now(UTC)).date()
    target = mission.target_value
    if mission.verification_type == "SLEEP_DIARY":
        row = db.scalar(select(SleepRecord).where(SleepRecord.user_id == assignment.user_id, SleepRecord.sleep_date.between(start_date, end_date)).order_by(SleepRecord.sleep_date.desc()))
        trusted = row is not None and bool(row.source) and assignment.assigned_at <= row.created_at <= assignment.deadline
        return trusted, {"record_id": row.id if row else None, "source": row.source if row else None}
    if mission.verification_type in {"STEPS", "SCREEN_TIME", "NIGHTTIME_SCREEN_TIME"}:
        row = db.scalar(select(BehaviorRecord).where(BehaviorRecord.user_id == assignment.user_id, BehaviorRecord.record_date.between(start_date, end_date)).order_by(BehaviorRecord.record_date.desc()))
        if row is None or target is None:
            return False, {"reason": "matching device record unavailable"}
        source = (row.app_usage or {}).get("source")
        if not source or not (assignment.assigned_at <= row.created_at <= assignment.deadline):
            return False, {"reason": "record source or collection time is not verifiable"}
        field = {"STEPS": "steps", "SCREEN_TIME": "screen_time", "NIGHTTIME_SCREEN_TIME": "nighttime_screen_time"}[mission.verification_type]
        actual = float(getattr(row, field))
        passed = actual >= target if field == "steps" else actual <= target
        return passed, {"record_id": row.id, "metric": field, "actual": actual, "target": target, "aggregate": "daily", "source": source}
    if mission.verification_type == "BEDTIME":
        row = db.scalar(select(SleepRecord).where(SleepRecord.user_id == assignment.user_id, SleepRecord.sleep_date.between(start_date, end_date)).order_by(SleepRecord.sleep_date.desc()))
        if row is None or target is None:
            return False, {"reason": "sleep record unavailable"}
        if not row.source or not (assignment.assigned_at <= row.created_at <= assignment.deadline):
            return False, {"reason": "record source or collection time is not verifiable"}
        actual = row.bedtime.hour * 60 + row.bedtime.minute
        return actual <= target, {"record_id": row.id, "actual_minutes": actual, "target_minutes": target}
    return False, {"reason": "unsupported verification type"}


def complete_mission(db: Session, user_id: UUID, assignment_id: int) -> dict:
    try:
        assignment = db.scalar(select(MissionAssignment).where(MissionAssignment.assignment_id == assignment_id).with_for_update())
        if assignment is None or assignment.user_id != user_id:
            raise AppError(404, "MISSION_NOT_FOUND", "Mission assignment was not found")
        prior = db.scalar(select(MissionResult).where(MissionResult.assignment_id == assignment_id))
        cat = db.scalar(select(CatProfile).where(CatProfile.user_id == user_id).with_for_update())
        if prior is not None:
            db.rollback()
            return {"assignment_id": assignment_id, "result_id": prior.result_id, "verified": prior.verified, "reward_exp": 0, "cat_level": cat.level, "cat_exp": cat.exp, "idempotent": True}
        if datetime.now(UTC) > assignment.deadline:
            raise AppError(409, "MISSION_EXPIRED", "Mission deadline has passed")
        mission = db.get(Mission, assignment.mission_id)
        verified, evidence = _verify(db, assignment, mission)
        if not verified:
            raise AppError(409, "MISSION_NOT_VERIFIED", "Mission conditions are not satisfied", evidence)
        result = MissionResult(assignment_id=assignment_id, result=evidence, verified=True)
        db.add(result)
        db.flush()
        assignment.status = "COMPLETED"
        grant_exp(db, cat, user_id, result.result_id, mission.reward_exp)
        db.commit()
        return {"assignment_id": assignment_id, "result_id": result.result_id, "verified": True, "reward_exp": mission.reward_exp, "cat_level": cat.level, "cat_exp": cat.exp, "idempotent": False}
    except AppError:
        db.rollback()
        raise
    except Exception:
        db.rollback()
        raise
