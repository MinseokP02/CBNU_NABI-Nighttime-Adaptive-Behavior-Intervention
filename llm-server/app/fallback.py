"""LLM-10 fallback: safe fixed sentences used when inference fails or is rejected.

Every sentence here must pass safety.check_output (enforced by tests).
"""
from app.schemas import (
    CatAction,
    Emotion,
    Intent,
    InterventionCategory,
    Language,
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

INTENT_OPENER_EN: dict[Intent, str] = {
    Intent.SLEEP_COMPLAINT: "Sleep must have been really hard for you.",
    Intent.SLEEP_WORRY: "Worrying about sleep can make your heart feel even heavier.",
    Intent.STRESS: "You've been feeling pretty tense lately, huh.",
    Intent.MISSION_QUERY: "Let's do today's little mission together.",
    Intent.MEDICAL_QUESTION: "For medicine or a diagnosis, talking with a doctor is the safest way.",
    Intent.GREETING: "Hi! How was your day?",
    Intent.GRATITUDE: "Thank you too. I'm happy we're together.",
    Intent.SMALL_TALK: "Thanks for telling me.",
    Intent.OTHER: "Thanks for telling me.",
    Intent.CRISIS: "",
}

CATEGORY_GUIDANCE_EN: dict[InterventionCategory, str] = {
    InterventionCategory.STIMULUS_CONTROL: (
        "Use your bed just for sleep, and if you can't fall asleep after about 20 minutes, "
        "get up and rest quietly, then lie down again when you feel sleepy."
    ),
    InterventionCategory.SLEEP_SCHEDULE: "Let's start by waking up at about the same time every day.",
    InterventionCategory.RELAXATION: "How about breathing slowly before bed and letting your body relax?",
    InterventionCategory.COGNITIVE: (
        "When sleep worries come up, write them down, and remind yourself that just resting tonight is okay."
    ),
    InterventionCategory.SLEEP_HYGIENE: (
        "Let's skip caffeine late in the afternoon and put the screen down a little earlier before bed."
    ),
}

DEFAULT_CLOSER_EN = "I hope you have a cozy night."


def _en(lang: Language) -> bool:
    return lang == Language.EN


def chat_reply(intent: Intent, category: InterventionCategory | None, lang: Language = Language.KO) -> str:
    openers = INTENT_OPENER_EN if _en(lang) else INTENT_OPENER
    closer = DEFAULT_CLOSER_EN if _en(lang) else DEFAULT_CLOSER
    opener = openers.get(intent) or openers[Intent.OTHER]
    if intent in {Intent.GREETING, Intent.GRATITUDE} or category is None:
        return f"{opener} {closer}" if intent != Intent.GREETING else opener
    return f"{opener} {intervention_guidance(category, lang)}"


def intervention_guidance(category: InterventionCategory, lang: Language = Language.KO) -> str:
    return (CATEGORY_GUIDANCE_EN if _en(lang) else CATEGORY_GUIDANCE)[category]


def mission_message(phase: MissionPhase, title: str, reward_exp: int | None, lang: Language = Language.KO) -> tuple[str, Emotion, CatAction]:
    title = title.strip()[:60]
    if _en(lang):
        if phase == MissionPhase.START:
            return f"Let's start the '{title}' mission. Take your time.", Emotion.ENCOURAGING, CatAction.WALK
        if phase == MissionPhase.PROGRESS:
            return f"You're doing great on '{title}'. Just a little more.", Emotion.ENCOURAGING, CatAction.SIT
        reward = f" You earned {reward_exp} EXP." if reward_exp else ""
        return f"'{title}' mission complete! Really well done.{reward}", Emotion.HAPPY, CatAction.HAPPY
    if phase == MissionPhase.START:
        return f"'{title}' 미션을 시작해 보자. 천천히 해도 괜찮아.", Emotion.ENCOURAGING, CatAction.WALK
    if phase == MissionPhase.PROGRESS:
        return f"'{title}' 미션 잘 하고 있어. 조금만 더 해 보자.", Emotion.ENCOURAGING, CatAction.SIT
    reward = f" 경험치 {reward_exp}을 받았어." if reward_exp else ""
    return f"'{title}' 미션 완료! 정말 잘했어.{reward}", Emotion.HAPPY, CatAction.HAPPY
