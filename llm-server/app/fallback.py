"""LLM-10 fallback: safe fixed sentences used when inference fails or is rejected.

Every sentence here must pass safety.check_output (enforced by tests).
"""
from app.schemas import (
    CatAction,
    Emotion,
    Intent,
    InterventionCategory,
    MissionPhase,
)

INTENT_OPENER: dict[Intent, str] = {
    Intent.SLEEP_COMPLAINT: "잠 때문에 많이 힘들었겠다.",
    Intent.SLEEP_WORRY: "잠에 대한 걱정이 크면 마음이 더 무거워지지.",
    Intent.STRESS: "요즘 마음이 많이 긴장돼 있었구나.",
    Intent.MISSION_QUERY: "오늘의 작은 미션을 같이 해 보자.",
    Intent.MEDICAL_QUESTION: "약이나 진단에 관한 건 의사 선생님과 상담하는 게 가장 안전해.",
    Intent.GREETING: "안녕! 오늘 하루는 어땠어?",
    Intent.GRATITUDE: "나도 고마워. 함께해서 기뻐.",
    Intent.SMALL_TALK: "이야기해 줘서 고마워.",
    Intent.OTHER: "이야기해 줘서 고마워.",
    Intent.CRISIS: "",
}

CATEGORY_GUIDANCE: dict[InterventionCategory, str] = {
    InterventionCategory.STIMULUS_CONTROL: (
        "침대에서는 잠만 자고, 20분쯤 지나도 잠이 오지 않으면 잠시 일어나 조용히 쉬다가 졸릴 때 다시 누워 보자."
    ),
    InterventionCategory.SLEEP_SCHEDULE: "일어나는 시간을 매일 비슷하게 맞추는 것부터 해 보자.",
    InterventionCategory.RELAXATION: "자기 전에 천천히 숨을 쉬면서 몸의 긴장을 풀어 보는 건 어때?",
    InterventionCategory.COGNITIVE: (
        "잠에 대한 걱정이 떠오르면 종이에 적어 두고, 오늘은 편히 쉬는 것만으로도 괜찮다고 생각해 보자."
    ),
    InterventionCategory.SLEEP_HYGIENE: (
        "오후 늦게는 카페인을 피하고, 잠들기 전에는 화면을 조금 일찍 내려놓아 보자."
    ),
}

DEFAULT_CLOSER = "오늘도 편안한 밤 보내길 바라."


def chat_reply(intent: Intent, category: InterventionCategory | None) -> str:
    opener = INTENT_OPENER.get(intent) or INTENT_OPENER[Intent.OTHER]
    if intent in {Intent.GREETING, Intent.GRATITUDE} or category is None:
        return f"{opener} {DEFAULT_CLOSER}" if intent != Intent.GREETING else opener
    return f"{opener} {CATEGORY_GUIDANCE[category]}"


def intervention_guidance(category: InterventionCategory) -> str:
    return CATEGORY_GUIDANCE[category]


def mission_message(phase: MissionPhase, title: str, reward_exp: int | None) -> tuple[str, Emotion, CatAction]:
    title = title.strip()[:60]
    if phase == MissionPhase.START:
        return f"'{title}' 미션을 시작해 보자. 천천히 해도 괜찮아.", Emotion.ENCOURAGING, CatAction.WALK
    if phase == MissionPhase.PROGRESS:
        return f"'{title}' 미션 잘 하고 있어. 조금만 더 해 보자.", Emotion.ENCOURAGING, CatAction.SIT
    reward = f" 경험치 {reward_exp}을 받았어." if reward_exp else ""
    return f"'{title}' 미션 완료! 정말 잘했어.{reward}", Emotion.HAPPY, CatAction.HAPPY
