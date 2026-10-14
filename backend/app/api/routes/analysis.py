from fastapi import APIRouter

from app.api.deps import CurrentUser, DbSession
from app.schemas.baseline import BaselineResponse
from app.schemas.risk import RiskResponse
from app.services.baseline_service import get_baseline
from app.services.risk_service import analyze_risk

router = APIRouter()


@router.get("/baseline", response_model=BaselineResponse)
def baseline(db: DbSession, user: CurrentUser) -> dict:
    return get_baseline(db, user.user_id)


@router.get("/risk", response_model=RiskResponse)
def risk(db: DbSession, user: CurrentUser) -> dict:
    return analyze_risk(db, user.user_id)
