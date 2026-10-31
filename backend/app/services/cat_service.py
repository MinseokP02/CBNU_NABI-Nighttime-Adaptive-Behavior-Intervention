"""Cat state (API-17): which animation tag the app should play, decided by fixed rules.

Order: recent mission completion -> HAPPY, local night -> SLEEP,
MODERATE/HIGH risk -> COMFORT, otherwise IDLE.
"""
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.cat_profile import CatProfile
from app.models.mission_assignment import MissionAssignment
from app.models.mission_result import MissionResult
from app.models.user import User
from app.services.reward_service import required_exp
from app.services.risk_service import analyze_risk

HAPPY_WINDOW = timedelta(hours=6)
NIGHT_HOURS = range(0, 6)


def cat_state(db: Session, user: User, now: datetime | None = None) -> dict:
    now = now or datetime.now(UTC)
    cat = db.scalar(select(CatProfile).where(CatProfile.user_id == user.user_id))
    recent_completion = db.scalar(
        select(MissionResult.result_id)
        .join(MissionAssignment, MissionAssignment.assignment_id == MissionResult.assignment_id)
        .where(MissionAssignment.user_id == user.user_id, MissionResult.verified.is_(True), MissionResult.verified_at >= now - HAPPY_WINDOW)
        .limit(1)
    )
    if recent_completion is not None:
        action, reason = "HAPPY", "MISSION_COMPLETED"
    elif now.astimezone(ZoneInfo(user.timezone)).hour in NIGHT_HOURS:
        action, reason = "SLEEP", "NIGHT_TIME"
    elif analyze_risk(db, user, 7)["risk_level"] in {"MODERATE", "HIGH"}:
        action, reason = "COMFORT", "SLEEP_RISK"
    else:
        action, reason = "IDLE", "DEFAULT"
    return {
        "cat_id": cat.cat_id, "name": cat.name, "level": cat.level, "exp": cat.exp,
        "exp_to_next_level": required_exp(cat.level) - cat.exp, "cat_action": action, "reason": reason,
    }
