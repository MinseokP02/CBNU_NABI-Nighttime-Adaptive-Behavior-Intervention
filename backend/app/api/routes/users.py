from fastapi import APIRouter

from app.api.deps import CurrentUser, DbSession
from app.models.user import User
from app.schemas.user import UserResponse, UserStateResponse
from app.services.baseline_service import current_baseline
from app.services.cat_service import cat_state
from app.services.mission_service import list_active
from app.services.risk_service import analyze_risk

router = APIRouter()


@router.get("/me", response_model=UserResponse)
def me(user: CurrentUser) -> User:
    return user


@router.get("/me/state", response_model=UserStateResponse)
def state(db: DbSession, user: CurrentUser) -> dict:
    base = current_baseline(db, user)
    risk = analyze_risk(db, user, 7, base)
    cat = cat_state(db, user)
    return {
        "baseline_status": base["status"],
        "baseline_valid_days": base["valid_days"],
        "window_days": risk["window_days"],
        "sleep_state": risk["sleep_state"],
        "behavior_state": risk["behavior_state"],
        "risk_level": risk["risk_level"],
        "risk_factors": risk["risk_factors"],
        "cat": {"name": cat["name"], "level": cat["level"], "exp": cat["exp"], "cat_action": cat["cat_action"]},
        "active_missions": [
            {"assignment_id": m["assignment_id"], "mission_id": m["mission_id"], "title": m["title"], "deadline": m["deadline"].isoformat()}
            for m in list_active(db, user)
        ],
    }
