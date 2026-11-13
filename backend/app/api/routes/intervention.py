from fastapi import APIRouter

from app.api.deps import AnalysisWindow, CurrentUser, DbSession
from app.schemas.intervention import RecommendationResponse
from app.services import llm_client
from app.services.baseline_service import current_baseline
from app.services.chat_service import persona_for
from app.services.intervention_service import recommend
from app.services.risk_service import analyze_risk

router = APIRouter()


@router.get("/recommendations", response_model=RecommendationResponse)
def recommendations(db: DbSession, user: CurrentUser, window: AnalysisWindow, include_explanation: bool = False) -> dict:
    base = current_baseline(db, user)
    risk = analyze_risk(db, user, window, base)
    rec = recommend(db, risk)
    persona = persona_for(user)
    db.commit()  # release the connection before any LLM call
    if include_explanation and rec["requires_llm_explanation"]:
        intervention = llm_client.intervention_payload(rec)
        try:
            result = llm_client.explain_intervention(llm_client.context_payload(risk, base), intervention, persona)
            rec["explanation"], rec["explanation_source"] = result.guidance, result.source
        except llm_client.LLMUnavailable:
            rec["explanation"], rec["explanation_source"] = rec["message"], "fallback"
    return rec
