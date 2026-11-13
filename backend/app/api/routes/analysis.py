from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Query

from app.api.deps import AnalysisWindow, CurrentUser, DbSession
from app.schemas.baseline import BaselineResponse
from app.schemas.risk import RefreshResponse, RiskResponse
from app.schemas.trend import TrendResponse
from app.services.baseline_service import current_baseline
from app.services.risk_service import ALLOWED_WINDOWS, analyze_risk, persist_risk
from app.services.trend_service import MAX_DAYS, MIN_DAYS, build_trends

router = APIRouter()


@router.get("/baseline", response_model=BaselineResponse)
def baseline(db: DbSession, user: CurrentUser) -> dict:
    return current_baseline(db, user)


@router.get("/risk", response_model=RiskResponse)
def risk(db: DbSession, user: CurrentUser, window: AnalysisWindow) -> dict:
    return analyze_risk(db, user, window)


@router.get("/trends", response_model=TrendResponse)
def trends(db: DbSession, user: CurrentUser, days: Annotated[int, Query(ge=MIN_DAYS, le=MAX_DAYS)] = 14) -> dict:
    """Per-day values of the analysed metrics with the personal baseline band (for trend charts)."""
    return build_trends(db, user, days)


@router.post("/refresh", response_model=RefreshResponse)
def refresh(db: DbSession, user: CurrentUser) -> dict:
    base = current_baseline(db, user)
    results = [analyze_risk(db, user, window, base) for window in ALLOWED_WINDOWS]
    for result in results:
        persist_risk(db, user.user_id, result, base["baseline_id"])
    db.commit()
    return {"baseline": base, "risk": results, "refreshed_at": datetime.now(UTC)}
