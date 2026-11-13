"""Prompt structure (spec §5.5).

1) System prompt: role, persona, medical safety rules, output format
2) Personal context: baseline, sleep/behavior state, risk
3) Intervention context: the approved intervention and prohibitions
4) Conversation context: user message + minimal recent history
"""
import json

from app.schemas import (
    ApprovedIntervention,
    CatAction,
    CatPersona,
    Emotion,
    GenerateRequest,
    HistoryTurn,
    Intent,
    InterventionRequest,
    MissionRequest,
    PersonalContext,
)

PERSONA_TEXT: dict[CatPersona, str] = {
    CatPersona.FRIENDLY_SUPPORTIVE: "다정하고 지지적인 고양이 친구. 반말을 쓰고 따뜻하게 공감한다.",
    CatPersona.CALM_GENTLE: "차분하고 부드러운 고양이 친구. 반말을 쓰고 느린 호흡으로 안정감을 준다.",
    CatPersona.PLAYFUL: "밝고 장난기 있는 고양이 친구. 반말을 쓰고 가볍게 응원하되 사용자의 어려움을 가볍게 여기지 않는다.",
}

SAFETY_RULES = """[의료 안전 규칙 — 반드시 지킬 것]
- 너는 의료인이 아니다. 불면증이나 다른 질환을 진단하지 않는다.
- 약물(수면제, 멜라토닌 등)·용량·복용·중단을 권하거나 언급하지 않는다. 약이나 진단 질문에는 의사와 상담하라고만 안내한다.
- 수면 제한(침대에 있는 시간을 줄이는 처방)이나 수면 일정을 직접 정해 주지 않는다.
- 술을 수면 보조로 권하지 않는다.
- 위험을 과장하거나 겁주지 않는다. 기록 변화를 원인으로 단정하지 않는다.
- 제공된 '승인된 중재' 외의 새로운 치료 행동을 제안하지 않는다. 승인된 중재를 바꾸지 않는다.
- 사용자가 자해·자살을 언급하면 공감하고 전문 상담(109)을 안내한다."""


def _json_schema_hint(include_category: bool) -> str:
    fields = {
        "response": "사용자에게 보낼 한국어 1~2문장 (최대 200자)",
        "intent": "|".join(i.value for i in Intent),
        "emotion": "|".join(e.value for e in Emotion),
        "cat_action": "|".join(c.value for c in CatAction),
    }
    if include_category:
        fields["intervention_category"] = "승인된 중재의 category 값 그대로, 없으면 null"
    return json.dumps(fields, ensure_ascii=False)


def system_prompt(persona: CatPersona, include_category: bool = True) -> str:
    return (
        "너는 수면 개선 앱 NABI의 2D 고양이 캐릭터다. CBT-I 원칙에 따라 서버가 이미 선택한 중재를 "
        "사용자에게 친근하게 설명하고 대화한다. 판단과 중재 선택은 서버가 하며 너는 설명과 표현만 맡는다.\n"
        f"[페르소나] {PERSONA_TEXT[persona]}\n\n{SAFETY_RULES}\n\n"
        "[출력 형식] 아래 키만 가진 JSON 객체 하나만 출력한다. 마크다운이나 설명을 붙이지 않는다. "
        f"enum 값은 제시된 값 중 하나만 사용한다.\n{_json_schema_hint(include_category)}"
    )


def personal_context_block(ctx: PersonalContext) -> str:
    lines = [
        "[개인 상태]",
        f"- 수면 상태: {ctx.sleep_state.value}",
        f"- 행동 상태: {ctx.behavior_state.value}",
        f"- 위험 수준: {ctx.risk_level.value}",
    ]
    if ctx.risk_factors:
        lines.append(f"- 관찰된 변화: {', '.join(ctx.risk_factors)}")
    b = ctx.baseline_summary
    if b is not None:
        if b.median_sleep_min is not None:
            lines.append(f"- 평소 수면 시간 중앙값: {round(b.median_sleep_min)}분")
        if b.median_efficiency is not None:
            lines.append(f"- 평소 수면 효율 중앙값: {round(b.median_efficiency * 100)}%")
        if b.median_sleep_onset_min is not None:
            lines.append(f"- 평소 잠드는 시간 중앙값: {round(b.median_sleep_onset_min)}분")
    return "\n".join(lines)


def intervention_block(intervention: ApprovedIntervention | None) -> str:
    if intervention is None:
        return "[승인된 중재] 없음. 새로운 행동 지침을 제안하지 말고 공감과 대화에 집중한다. intervention_category는 null."
    lines = [
        "[승인된 중재]",
        f"- category: {intervention.category.value}",
        f"- 안내 내용: {intervention.instruction}",
    ]
    if intervention.reason_codes:
        lines.append(f"- 선택 이유: {', '.join(intervention.reason_codes)}")
    if intervention.prohibited:
        lines.append(f"- 금지 사항: {'; '.join(intervention.prohibited)}")
    return "\n".join(lines)


def _trim_history(history: list[HistoryTurn], turns: int) -> list[dict]:
    recent = history[-turns:] if turns > 0 else []
    return [{"role": t.role, "content": t.content[:500]} for t in recent]


def build_generate_messages(req: GenerateRequest, history_turns: int) -> list[dict]:
    context = f"{personal_context_block(req.context)}\n\n{intervention_block(req.intervention)}"
    return [
        {"role": "system", "content": f"{system_prompt(req.cat_persona)}\n\n{context}"},
        *_trim_history(req.recent_history, history_turns),
        {"role": "user", "content": req.user_message},
    ]


def build_intervention_messages(req: InterventionRequest) -> list[dict]:
    context = f"{personal_context_block(req.context)}\n\n{intervention_block(req.intervention)}"
    task = (
        "[과제] 승인된 중재를 사용자가 오늘 바로 해 볼 수 있도록 1~2문장으로 설명한다. "
        "intent는 OTHER로 둔다."
    )
    return [
        {"role": "system", "content": f"{system_prompt(req.cat_persona)}\n\n{context}"},
        {"role": "user", "content": task},
    ]


def build_mission_messages(req: MissionRequest) -> list[dict]:
    phase_text = {
        "START": "미션을 시작하도록 부드럽게 권하는",
        "PROGRESS": "미션을 진행 중인 사용자를 응원하는",
        "COMPLETE": "미션 완료를 축하하는",
    }[req.phase.value]
    reward = f" 보상 경험치는 {req.reward_exp}이다." if req.reward_exp else ""
    task = (
        f"[미션] 제목: {req.mission.title}\n설명: {req.mission.description}\n"
        f"[과제] {phase_text} 1~2문장을 작성한다.{reward} intent는 MISSION_QUERY로 둔다."
    )
    return [
        {"role": "system", "content": system_prompt(req.cat_persona, include_category=False)},
        {"role": "user", "content": task},
    ]


def repair_message(error: str) -> dict:
    return {
        "role": "user",
        "content": f"직전 출력이 형식 검증에 실패했다({error[:200]}). 규칙을 지켜 JSON 객체 하나만 다시 출력해.",
    }
