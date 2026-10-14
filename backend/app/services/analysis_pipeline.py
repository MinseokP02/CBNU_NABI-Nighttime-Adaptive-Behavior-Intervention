from uuid import UUID

from sqlalchemy.orm import Session

from app.services.intervention_service import select_intervention
from app.services.risk_service import analyze_risk


def current_analysis(db: Session, user_id: UUID) -> dict:
    risk = analyze_risk(db, user_id)
    return {"risk": risk, "intervention": select_intervention(db, risk)}
