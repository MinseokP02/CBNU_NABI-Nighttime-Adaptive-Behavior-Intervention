from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.intervention import Intervention

MAPPING = {
    "PRE_BED_PHONE_OVERUSE": "DIGITAL_USE",
    "LOW_DAILY_ACTIVITY": "PHYSICAL_ACTIVITY",
    "DELAYED_SLEEP_ONSET": "BEDTIME_ROUTINE",
    "LOW_SLEEP_EFFICIENCY": "SLEEP_ROUTINE",
}


def select_intervention(db: Session, risk: dict) -> dict:
    if risk["risk_level"] == "INSUFFICIENT_DATA":
        return {"intervention_id": None, "category": "DATA_COLLECTION", "rule": "INSUFFICIENT_DATA", "title": "기록 이어가기", "message": "개인 기준을 만들 수 있도록 수면과 활동 기록을 조금 더 모아 주세요.", "context": {"risk_level": risk["risk_level"]}}
    category = MAPPING.get(risk["behavior_state"]) or MAPPING.get(risk["sleep_state"]) or "SLEEP_ROUTINE"
    row = db.scalar(select(Intervention).where(Intervention.category == category, Intervention.active.is_(True)))
    if row is None:
        return {"intervention_id": None, "category": category, "rule": "FALLBACK", "title": "오늘의 작은 습관", "message": "오늘 할 수 있는 작은 수면 습관 하나를 차분히 실천해 보세요.", "context": {"risk_level": risk["risk_level"]}}
    return {"intervention_id": row.intervention_id, "category": row.category, "rule": row.rule, "title": row.title, "message": row.message_template, "context": {"risk_level": risk["risk_level"], "sleep_state": risk["sleep_state"], "behavior_state": risk["behavior_state"], "selected_at": datetime.now(UTC).isoformat()}}
