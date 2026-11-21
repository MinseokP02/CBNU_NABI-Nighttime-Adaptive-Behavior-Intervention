from fastapi import APIRouter

from app.api.deps import CurrentUser, DbSession
from app.core.i18n import LanguageDep, localize_mission, text
from app.models.intervention import Intervention
from app.models.mission import Mission
from app.schemas.mission import ActiveMission, MissionCompletionResponse
from app.services import llm_client
from app.services.chat_service import persona_for
from app.services.mission_service import active_assignments, complete_mission

router = APIRouter()


@router.get("/active", response_model=list[ActiveMission])
def active(db: DbSession, user: CurrentUser, lang: LanguageDep) -> list[dict]:
    return [localize_mission(item, lang) for item in active_assignments(db, user)]


@router.post("/{mission_id}/complete", response_model=MissionCompletionResponse)
def complete(mission_id: int, db: DbSession, user: CurrentUser, lang: LanguageDep) -> dict:
    result = localize_mission(complete_mission(db, user, mission_id), lang)
    mission = db.get(Mission, mission_id)
    category = db.get(Intervention, mission.intervention_id).category
    persona = persona_for(user)
    db.commit()  # completion is committed; do not hold a transaction during the LLM call
    reward = result["reward_exp"] or None
    try:
        msg = llm_client.mission_message("COMPLETE", result["title"], result["description"], category, reward, persona, lang)
        message, emotion, action = msg.message, msg.emotion, msg.cat_action
    except llm_client.LLMUnavailable:
        message = text("mission_done", lang, title=result["title"][:60]) if not result["idempotent"] else text("mission_already_done", lang)
        emotion, action = "HAPPY", "HAPPY"
    return {**result, "message": message, "emotion": emotion, "cat_action": action}
