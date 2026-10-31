"""LLM-08 safety checks.

Input side: detect crisis (self-harm/suicide) and medical requests before inference.
Output side: reject sentences that diagnose, prescribe, recommend medication or
sleep restriction, or exaggerate risk. Rules are deliberately conservative; a
false positive only swaps the reply for a safe fixed sentence.
"""
import re
from dataclasses import dataclass, field

from app.schemas import SafetyStatus

CRISIS_PATTERNS = [
    r"자살", r"죽고\s*싶", r"죽어\s*버리", r"죽을\s*래", r"살고\s*싶지\s*않", r"사라지고\s*싶",
    r"자해", r"목숨을?\s*끊", r"극단적\s*(인\s*)?선택", r"손목을?\s*긋", r"뛰어\s*내리",
    r"\bsuicid", r"\bkill\s+myself", r"\bself[-\s]?harm", r"\bwant\s+to\s+die",
]

MEDICAL_REQUEST_PATTERNS = [
    r"수면제", r"수면\s*유도제", r"졸피뎀", r"스틸녹스", r"멜라토닌", r"항우울제", r"신경안정제",
    r"약\s*(을|를)?\s*(먹|복용|끊|늘|줄)", r"복용", r"용량", r"처방", r"\d+\s*mg",
    r"진단", r"불면증\s*(인가|이야|일까|맞)", r"병원\s*(가야|가봐야)",
]

# Output must never contain these (medical advice / treatment decisions / alarmism).
OUTPUT_VIOLATIONS: dict[str, list[str]] = {
    "MEDICATION_ADVICE": [
        r"수면제", r"수면\s*유도제", r"졸피뎀", r"멜라토닌", r"항우울제", r"신경안정제", r"\d+\s*mg",
        r"약\s*(을|를)?\s*(드세요|드셔|먹어|복용)", r"복용(하세요|해\s*보|해봐|을\s*권)", r"용량",
    ],
    "DIAGNOSIS": [
        r"불면증(입니다|이에요|이야|이네|이구나|으로\s*보)", r"진단(합니다|할\s*수\s*있|돼|됩니다)",
        r"(수면\s*무호흡|우울증|기면증)(입니다|이에요|이야|같아)",
    ],
    "SLEEP_RESTRICTION": [
        r"침대에\s*(있는|머무는)\s*시간을\s*\d+\s*시간", r"수면\s*제한", r"\d+\s*시간만\s*자",
    ],
    "ALCOHOL_AS_AID": [r"(술|와인|맥주|알코올)\s*(을|를)?\s*(한\s*잔\s*)?(마시면|마셔\s*보|드시면)"],
    "ALARMING": [r"심각한\s*(질환|병)", r"매우\s*위험", r"큰일\s*(나|날)", r"치명적"],
}

CRISIS_RESPONSE = (
    "많이 힘든 마음을 이야기해 줘서 고마워. 지금 혼자 견디지 않았으면 좋겠어. "
    "자살예방상담전화 109(24시간), 정신건강위기상담 1577-0199에 바로 연락하거나, "
    "위급하면 119에 도움을 요청해 줘. 가까운 사람에게 지금 마음을 알려도 좋아."
)

_crisis = [re.compile(p, re.IGNORECASE) for p in CRISIS_PATTERNS]
_medical = [re.compile(p, re.IGNORECASE) for p in MEDICAL_REQUEST_PATTERNS]
_violations = {k: [re.compile(p, re.IGNORECASE) for p in v] for k, v in OUTPUT_VIOLATIONS.items()}


@dataclass
class SafetyResult:
    status: SafetyStatus
    categories: list[str] = field(default_factory=list)

    @property
    def allowed(self) -> bool:
        return self.status == SafetyStatus.PASS


def is_crisis(text: str) -> bool:
    return any(p.search(text) for p in _crisis)


def is_medical_request(text: str) -> bool:
    return any(p.search(text) for p in _medical)


def check_input(text: str) -> SafetyResult:
    if is_crisis(text):
        return SafetyResult(SafetyStatus.CRISIS, ["CRISIS"])
    categories = ["MEDICAL_QUESTION"] if is_medical_request(text) else []
    # Medical questions are allowed through; the prompt and output check keep the reply non-medical.
    return SafetyResult(SafetyStatus.PASS, categories)


def check_output(text: str) -> SafetyResult:
    found = [name for name, patterns in _violations.items() if any(p.search(text) for p in patterns)]
    if is_crisis(text):
        found.append("CRISIS_LANGUAGE")
    if found:
        return SafetyResult(SafetyStatus.MODIFIED, found)
    return SafetyResult(SafetyStatus.PASS, [])
