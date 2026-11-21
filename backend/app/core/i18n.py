"""Response language for user-facing text (the app's language, from Accept-Language).

Only text the user reads is localized: the intervention/mission catalog, fixed fallback sentences
and LLM replies (the LLM server is told the language). Codes, enums and error envelopes stay as they are.
The catalog is stored in Korean (seed); English lives here, keyed by intervention code / Korean mission title.
"""
from typing import Annotated, Literal

from fastapi import Depends, Header

Lang = Literal["ko", "en"]


def language(accept_language: Annotated[str | None, Header()] = None) -> Lang:
    """First language tag wins: en*, otherwise Korean (the default)."""
    first = (accept_language or "").split(",")[0].strip().lower()
    return "en" if first.startswith("en") else "ko"


LanguageDep = Annotated[Lang, Depends(language)]

# code -> (title, user-facing message)
INTERVENTIONS_EN: dict[str, tuple[str, str]] = {
    "SC_GET_OUT_OF_BED": (
        "Get out of bed for a bit if you can't sleep",
        "If you still can't fall asleep after about 20 minutes, get out of bed, rest quietly, and lie down again when you feel sleepy.",
    ),
    "SC_BED_FOR_SLEEP_ONLY": (
        "Bed is for sleep only",
        "Use your bed just for sleep instead of your phone or videos, and only lie down when you feel sleepy.",
    ),
    "SS_FIXED_WAKE_TIME": (
        "Wake up at the same time",
        "Even after a rough night, try getting up at your set time. A steady wake time keeps your rhythm in place.",
    ),
    "SS_CONSISTENT_BEDTIME": (
        "A regular wind-down time",
        "Try dimming the lights and getting ready for bed at about the same time every day.",
    ),
    "RX_BREATHING": (
        "Relaxing breaths before bed",
        "Before bed, take about 5 minutes to breathe slowly and let the tension in your shoulders and body go.",
    ),
    "CG_WORRY_NOTE": (
        "Worry notebook",
        "When sleep worries come up, write them down and remind yourself that simply resting tonight is enough.",
    ),
    "SH_CAFFEINE_CUTOFF": (
        "Less caffeine in the afternoon",
        "Late in the afternoon, pick warm water or a decaf drink instead of coffee or energy drinks.",
    ),
    "SH_SCREEN_OFF": (
        "Put the screen down before sleep",
        "Try putting your phone down and dimming the lights 30 minutes before sleep.",
    ),
    "SH_NAP_LIMIT": (
        "Keep naps short",
        "If you need a nap, keep it under 30 minutes in the early afternoon.",
    ),
    "SH_DAYTIME_ACTIVITY": (
        "Move a little during the day",
        "Without overdoing it, try a light 20-minute walk during the day.",
    ),
}

# Used when no catalog entry is available (intervention_service.recommend default).
DEFAULT_REC = {
    "ko": ("오늘의 수면 습관", "매일 비슷한 시간에 일어나는 것부터 시작해 보세요."),
    "en": ("Today's sleep habit", "Start by waking up at about the same time every day."),
}

# Korean mission title (unique in the catalog) -> (title, description)
MISSIONS_EN: dict[str, tuple[str, str]] = {
    "20분 규칙 지키고 수면일지 쓰기": (
        "Follow the 20-minute rule and log your sleep",
        "If you can't sleep, get out of bed after 20 minutes, and log your sleep diary in the morning.",
    ),
    "침대에서 화면 보지 않기": ("No screens in bed", "Keep screen time before sleep to 15 minutes or less."),
    "목표 기상 시각 지키기": (
        "Keep your target wake time",
        "Get up within ±30 minutes of your target wake time and log your sleep diary.",
    ),
    "목표 취침 시각 지키기": ("Keep your target bedtime", "Go to bed within ±30 minutes of your target bedtime."),
    "자기 전 10분 이완하기": ("Relax for 10 minutes before bed", "Ease your tension for 10 minutes with breathing or stretching before sleep."),
    "자기 전 걱정 노트 쓰기": ("Write a worry note before bed", "Before sleep, write down the worries on your mind in three lines."),
    "제한 시각 이후 카페인 끊기": ("No caffeine after the cutoff", "No caffeine after your cutoff time today."),
    "취침 전 화면 30분 이하": ("30 min or less of screens before bed", "Keep screen time before sleep to 30 minutes or less."),
    "낮잠 30분 이하": ("Nap 30 minutes or less", "Keep today's nap under 30 minutes."),
    "가벼운 운동 20분": ("20 minutes of light exercise", "Walk or stretch for 20 minutes during the day."),
}

TEXT: dict[str, dict[Lang, str]] = {
    "chat_fallback": {"ko": "이야기해 줘서 고마워. {message}", "en": "Thanks for telling me. {message}"},
    "mission_done": {"ko": "'{title}' 미션 완료! 정말 잘했어.", "en": "'{title}' mission complete! Really well done."},
    "mission_already_done": {"ko": "이미 완료한 미션이야. 잘하고 있어!", "en": "You already finished this mission. You're doing great!"},
    "crisis": {
        "ko": (
            "많이 힘든 마음을 이야기해 줘서 고마워. 지금 혼자 견디지 않았으면 좋겠어. "
            "자살예방상담전화 109(24시간), 정신건강위기상담 1577-0199에 바로 연락하거나, "
            "위급하면 119에 도움을 요청해 줘."
        ),
        "en": (
            "Thank you for telling me how hard things are. I don't want you to go through this alone. "
            "Please call the suicide prevention line 109 (24 hours) or the mental health crisis line 1577-0199 right away, "
            "or 119 in an emergency (outside Korea, your local emergency number)."
        ),
    },
}


def text(key: str, lang: Lang, **kwargs) -> str:
    return TEXT[key][lang].format(**kwargs)


def localize_recommendation(rec: dict, lang: Lang) -> dict:
    """Title and message of a recommend() result in [lang]. The LLM instruction stays Korean (model-facing)."""
    if lang == "ko":
        return rec
    en = INTERVENTIONS_EN.get(rec.get("code") or "")
    if rec.get("intervention_id") is None or en is None:
        title, message = DEFAULT_REC["en"]
    else:
        title, message = en
    return {**rec, "title": title, "message": message}


def localize_mission(item: dict, lang: Lang) -> dict:
    """title/description of a mission dict in [lang] (unknown titles are left as stored)."""
    if lang == "ko" or item.get("title") not in MISSIONS_EN:
        return item
    title, description = MISSIONS_EN[item["title"]]
    return {**item, "title": title, "description": description}
