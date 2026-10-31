from fastapi import APIRouter

from app.api.deps import CurrentUser, DbSession
from app.models.intervention import Intervention
from app.models.mission import Mission
from app.schemas.mission import ActiveMission, MissionCompletionResponse
from app.services import llm_client
from app.services.chat_service import persona_for
from app.services.mission_service import active_assignments, complete_mission

router = APIRouter()


@router.get("/active", response_model=list[ActiveMission])
def active(db: DbSession, user: CurrentUser) -> list[dict]:
    return active_assignments(db, user)


@router.post("/{mission_id}/complete", response_model=MissionCompletionResponse)
def complete(mission_id: int, db: DbSession, user: CurrentUser) -> dict:
    result = complete_mission(db, user, mission_id)
    mission = db.get(Mission, mission_id)
    category = db.get(Intervention, mission.intervention_id).category
    persona = persona_for(user)
    db.commit()  # completion is committed; do not hold a transaction during the LLM call
    reward = result["reward_exp"] or None
    try:
        msg = llm_client.mission_message("COMPLETE", result["title"], result["description"], category, reward, persona)
        message, emotion, action = msg.message, msg.emotion, msg.cat_action
    except llm_client.LLMUnavailable:
        message = f"'{result['title'][:60]}' 미션 완료! 정말 잘했어." if not result["idempotent"] else "이미 완료한 미션이야. 잘하고 있어!"
        emotion, action = "HAPPY", "HAPPY"
    return {**result, "message": message, "emotion": emotion, "cat_action": action}
