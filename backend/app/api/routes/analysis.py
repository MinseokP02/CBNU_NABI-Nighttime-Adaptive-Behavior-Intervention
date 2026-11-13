from datetime import UTC, datetime

from fastapi import APIRouter

from app.api.deps import AnalysisWindow, CurrentUser, DbSession
from app.schemas.baseline import BaselineResponse
from app.schemas.risk import RefreshResponse, RiskResponse
from app.services.baseline_service import current_baseline
from app.services.risk_service import ALLOWED_WINDOWS, analyze_risk, persist_risk

router = APIRouter()


@router.get("/baseline", response_model=BaselineResponse)
def baseline(db: DbSession, user: CurrentUser) -> dict:
    return current_baseline(db, user)


@router.get("/risk", response_model=RiskResponse)
def risk(db: DbSession, user: CurrentUser, window: AnalysisWindow) -> dict:
    return analyze_risk(db, user, window)


@router.post("/refresh", response_model=RefreshResponse)
def refresh(db: DbSession, user: CurrentUser) -> dict:
    base = current_baseline(db, user)
    results = [analyze_risk(db, user, window, base) for window in ALLOWED_WINDOWS]
    for result in results:
        persist_risk(db, user.user_id, result, base["baseline_id"])
    db.commit()
    return {"baseline": base, "risk": results, "refreshed_at": datetime.now(UTC)}
