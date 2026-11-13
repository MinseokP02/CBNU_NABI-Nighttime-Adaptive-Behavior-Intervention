"""LLM-01 utterance analysis: deterministic keyword classifier.

Used as (1) the intent source when inference is unavailable and
(2) a sanity check — a CRISIS/MEDICAL classification here always wins over the model.
"""
import re

from app.safety import is_crisis, is_medical_request
from app.schemas import CatAction, Emotion, Intent

_RULES: list[tuple[Intent, list[str]]] = [
    (Intent.SLEEP_WORRY, [
        r"못\s*잘\s*(것|까)", r"잠\s*(이|을)?\s*못\s*자면", r"(잠|수면).{0,8}(걱정|불안|무서|두려)",
        r"(걱정|불안).{0,8}(잠|자야|못\s*자)", r"내일.{0,10}(망|큰일)", r"평생.{0,6}못\s*자",
    ]),
    (Intent.SLEEP_COMPLAINT, [
        r"잠\s*(이|을)?\s*(못|안)\s*(자|와|들)", r"못\s*잤", r"잠드는\s*데", r"잠들기\s*(가\s*)?(힘들|어렵)",
        r"자꾸\s*깨", r"새벽에\s*깨", r"뒤척", r"피곤", r"졸려", r"불면", r"푹\s*못", r"설쳤",
    ]),
    (Intent.STRESS, [r"스트레스", r"긴장", r"불안", r"걱정", r"우울", r"짜증", r"힘들"]),
    (Intent.MISSION_QUERY, [r"미션", r"오늘\s*(뭐|무엇)\s*(해|하)", r"할\s*일", r"보상", r"경험치"]),
    (Intent.GRATITUDE, [r"고마워", r"고맙", r"감사", r"땡큐"]),
    (Intent.GREETING, [r"^\s*(안녕|하이|hi|hello|좋은\s*(아침|밤))", r"잘\s*자\b", r"굿나잇"]),
]
_compiled = [(intent, [re.compile(p, re.IGNORECASE) for p in pats]) for intent, pats in _RULES]

# Default cat expression per intent (LLM-06). The model may choose otherwise within the enum.
INTENT_EXPRESSION: dict[Intent, tuple[Emotion, CatAction]] = {
    Intent.SLEEP_COMPLAINT: (Emotion.EMPATHETIC, CatAction.SIT),
    Intent.SLEEP_WORRY: (Emotion.EMPATHETIC, CatAction.COMFORT),
    Intent.STRESS: (Emotion.EMPATHETIC, CatAction.COMFORT),
    Intent.MISSION_QUERY: (Emotion.ENCOURAGING, CatAction.WALK),
    Intent.MEDICAL_QUESTION: (Emotion.CALM, CatAction.SIT),
    Intent.GREETING: (Emotion.HAPPY, CatAction.IDLE),
    Intent.GRATITUDE: (Emotion.HAPPY, CatAction.HAPPY),
    Intent.SMALL_TALK: (Emotion.NEUTRAL, CatAction.IDLE),
    Intent.CRISIS: (Emotion.CONCERNED, CatAction.COMFORT),
    Intent.OTHER: (Emotion.NEUTRAL, CatAction.SIT),
}


def classify_intent(text: str) -> Intent:
    if is_crisis(text):
        return Intent.CRISIS
    if is_medical_request(text):
        return Intent.MEDICAL_QUESTION
    for intent, patterns in _compiled:
        if any(p.search(text) for p in patterns):
            return intent
    return Intent.SMALL_TALK if len(text.strip()) <= 12 else Intent.OTHER


def expression_for(intent: Intent) -> tuple[Emotion, CatAction]:
    return INTENT_EXPRESSION[intent]
