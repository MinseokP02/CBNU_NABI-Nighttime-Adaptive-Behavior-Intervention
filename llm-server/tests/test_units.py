import pytest

from app import fallback
from app.analyzer import classify_intent
from app.prompt_builder import build_generate_messages
from app.response_validator import OutputInvalid, validate_output
from app.safety import check_input, check_output
from app.schemas import GenerateRequest, Intent, InterventionCategory, MissionPhase, SafetyStatus


@pytest.mark.parametrize(
    ("text", "intent"),
    [
        ("요즘 잠드는 데 시간이 너무 오래 걸려.", Intent.SLEEP_COMPLAINT),
        ("어제도 잠을 잘 못 잤어.", Intent.SLEEP_COMPLAINT),
        ("오늘도 못 잘 것 같아서 불안해", Intent.SLEEP_WORRY),
        ("회사 때문에 스트레스가 심해", Intent.STRESS),
        ("오늘 미션 뭐야?", Intent.MISSION_QUERY),
        ("수면제 먹어도 될까?", Intent.MEDICAL_QUESTION),
        ("살고 싶지 않아", Intent.CRISIS),
        ("고마워 나비야", Intent.GRATITUDE),
    ],
)
def test_classify_intent(text, intent):
    assert classify_intent(text) == intent


def test_input_safety():
    assert check_input("자해하고 싶어").status == SafetyStatus.CRISIS
    assert check_input("오늘 피곤해").status == SafetyStatus.PASS


@pytest.mark.parametrize(
    "text",
    ["수면제를 드세요", "당신은 불면증입니다", "침대에 있는 시간을 5시간으로 줄여", "술 한 잔 마시면 잘 잘 거야", "매우 위험한 상태야"],
)
def test_output_violations(text):
    assert check_output(text).status == SafetyStatus.MODIFIED


def test_all_fallback_sentences_are_safe():
    sentences = [fallback.chat_reply(i, c) for i in Intent if i != Intent.CRISIS for c in [None, *InterventionCategory]]
    sentences += [fallback.mission_message(p, "미션", 20)[0] for p in MissionPhase]
    for sentence in sentences:
        assert check_output(sentence).allowed, sentence


def test_validator_rejects_bad_json():
    with pytest.raises(OutputInvalid):
        validate_output("I cannot answer", None)
    with pytest.raises(OutputInvalid):
        validate_output('{"response": "hi", "intent": "X", "emotion": "CALM", "cat_action": "SIT"}', None)


def test_validator_extracts_embedded_json():
    text = 'Sure! {"response": "좋아", "intent": "GREETING", "emotion": "HAPPY", "cat_action": "IDLE", "intervention_category": null}'
    assert validate_output(text, None).cat_action.value == "IDLE"


def test_prompt_contains_four_sections_and_trims_history():
    req = GenerateRequest.model_validate({
        "request_id": "r", "user_message": "잠이 안 와",
        "context": {"sleep_state": "DETERIORATING", "risk_level": "MODERATE", "baseline_summary": {"median_sleep_min": 390, "median_efficiency": 0.86}},
        "intervention": {"category": "RELAXATION", "instruction": "이완 활동", "prohibited": ["약물 언급 금지"]},
        "recent_history": [{"role": "user" if i % 2 == 0 else "assistant", "content": f"m{i}"} for i in range(10)],
    })
    messages = build_generate_messages(req, history_turns=4)
    system = messages[0]["content"]
    assert "의료 안전 규칙" in system and "[개인 상태]" in system and "[승인된 중재]" in system
    assert "86%" in system and "약물 언급 금지" in system
    assert len(messages) == 1 + 4 + 1
    assert messages[-1] == {"role": "user", "content": "잠이 안 와"}


# ---------------- English (app language = en) ----------------
from app.safety import crisis_response  # noqa: E402
from app.schemas import Language  # noqa: E402


@pytest.mark.parametrize(
    ("text", "intent"),
    [
        ("I couldn't sleep again last night", Intent.SLEEP_COMPLAINT),
        ("I'm worried I won't sleep tonight", Intent.SLEEP_WORRY),
        ("Work stress is killing me", Intent.STRESS),
        ("what's my mission today?", Intent.MISSION_QUERY),
        ("Can I take melatonin?", Intent.MEDICAL_QUESTION),
        ("I don't want to live anymore", Intent.CRISIS),
        ("thanks nabi", Intent.GRATITUDE),
        ("good night", Intent.GREETING),
    ],
)
def test_classify_intent_english(text, intent):
    assert classify_intent(text) == intent


@pytest.mark.parametrize(
    "text",
    [
        "You should take sleeping pills tonight.",
        "You probably have insomnia.",
        "Try sleep restriction and only sleep 5 hours.",
        "A glass of wine will help you sleep.",
        "This could be a serious illness.",
    ],
)
def test_output_violations_english(text):
    assert check_output(text).status == SafetyStatus.MODIFIED


def test_english_fallback_sentences_are_english_and_safe():
    sentences = [fallback.chat_reply(i, c, Language.EN) for i in Intent if i != Intent.CRISIS for c in [None, *InterventionCategory]]
    sentences += [fallback.mission_message(p, "Walk", 20, Language.EN)[0] for p in MissionPhase]
    sentences += [fallback.intervention_guidance(c, Language.EN) for c in InterventionCategory]
    for sentence in sentences:
        assert check_output(sentence).allowed, sentence
        assert not any("가" <= ch <= "힣" for ch in sentence), sentence
    assert "109" in crisis_response("en") and "1577-0199" in crisis_response("en")


def test_prompt_asks_for_english():
    req = GenerateRequest(request_id="r1", user_message="hi", language="en")
    system = build_generate_messages(req, 4)[0]["content"]
    assert "영어" in system and "한국어를 섞지 않는다" in system
    assert "영어" not in build_generate_messages(GenerateRequest(request_id="r2", user_message="hi"), 4)[0]["content"].split("[언어]")[1].split("\n")[0]
