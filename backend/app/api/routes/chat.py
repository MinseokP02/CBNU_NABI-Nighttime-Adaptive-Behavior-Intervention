from fastapi import APIRouter
from sqlalchemy import select

from app.api.deps import CurrentUser, DbSession
from app.models.cat_profile import CatProfile
from app.schemas.chat import ChatRequest, ChatResponse
from app.services.intervention_service import select_intervention
from app.services.llm_service import personalize
from app.services.risk_service import analyze_risk

router = APIRouter()


@router.post("", response_model=ChatResponse)
async def chat(payload: ChatRequest, db: DbSession, user: CurrentUser):
    risk = analyze_risk(db, user.user_id)
    intervention = select_intervention(db, risk)
    cat = db.scalar(select(CatProfile).where(CatProfile.user_id == user.user_id))
    db.close()  # Never retain a DB transaction during an external LLM call.
    return await personalize(intervention, risk, cat.name, payload.output_channel)
