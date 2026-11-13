"""Rule-based adaptive intervention engine (spec §4.5, §5.3).

The analysis engine decides the state; this engine picks the action from an approved
CBT-I catalog; the LLM only explains it. Interventions marked requires_expert_review
(e.g. sleep restriction) are never auto-selected.
"""
import re
from dataclasses import dataclass, field

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.intervention import Intervention
from app.models.mission import Mission

PRIORITY_RANK = {"HIGH": 0, "MEDIUM": 1, "LOW": 2}
CATEGORY_RANK = {"STIMULUS_CONTROL": 0, "SLEEP_SCHEDULE": 1, "COGNITIVE": 2, "RELAXATION": 3, "SLEEP_HYGIENE": 4}
DEFAULT_CODE = "SS_FIXED_WAKE_TIME"


@dataclass(frozen=True)
class Rule:
    rule_id: str
    code: str               # intervention catalog code
    category: str
    priority: str
    any_factors: frozenset[str] = field(default_factory=frozenset)
    all_factors: frozenset[str] = field(default_factory=frozenset)
    signals: frozenset[str] = field(default_factory=frozenset)


def _r(rule_id, code, category, priority, any_factors=(), all_factors=(), signals=()) -> Rule:
    return Rule(rule_id, code, category, priority, frozenset(any_factors), frozenset(all_factors), frozenset(signals))


RULES: tuple[Rule, ...] = (
    _r("R-SC-01", "SC_GET_OUT_OF_BED", "STIMULUS_CONTROL", "HIGH", any_factors={"PROLONGED_SLEEP_ONSET", "FREQUENT_NIGHT_AWAKENING"}),
    _r("R-SC-02", "SC_BED_FOR_SLEEP_ONLY", "STIMULUS_CONTROL", "HIGH", any_factors={"LOW_SLEEP_EFFICIENCY"}),
    _r("R-SS-01", "SS_CONSISTENT_BEDTIME", "SLEEP_SCHEDULE", "MEDIUM", any_factors={"IRREGULAR_BEDTIME"}),
    _r("R-SS-02", "SS_FIXED_WAKE_TIME", "SLEEP_SCHEDULE", "MEDIUM", any_factors={"IRREGULAR_WAKE_TIME", "SHORT_SLEEP_DURATION"}),
    _r("R-CG-01", "CG_WORRY_NOTE", "COGNITIVE", "MEDIUM", signals={"NEGATIVE_SLEEP_THOUGHT"}),
    _r("R-CG-02", "CG_WORRY_NOTE", "COGNITIVE", "MEDIUM", all_factors={"POOR_SLEEP_QUALITY", "HIGH_STRESS"}),
    _r("R-RX-01", "RX_BREATHING", "RELAXATION", "MEDIUM", any_factors={"HIGH_STRESS"}),
    _r("R-SH-01", "SH_CAFFEINE_CUTOFF", "SLEEP_HYGIENE", "MEDIUM", any_factors={"LATE_CAFFEINE"}),
    _r("R-SH-02", "SH_SCREEN_OFF", "SLEEP_HYGIENE", "MEDIUM", any_factors={"EXCESSIVE_PRE_BED_SCREEN"}),
    _r("R-SH-03", "SH_NAP_LIMIT", "SLEEP_HYGIENE", "LOW", any_factors={"LONG_DAYTIME_NAP"}),
    _r("R-SH-04", "SH_DAYTIME_ACTIVITY", "SLEEP_HYGIENE", "LOW", any_factors={"LOW_PHYSICAL_ACTIVITY"}),
)

# Negative sleep-related thoughts in chat (Cognitive Intervention trigger, spec §5.3).
_NEGATIVE_THOUGHT = [re.compile(p) for p in (
    r"못\s*잘\s*(것|까)", r"잠\s*(이|을)?\s*못\s*자면", r"(잠|수면).{0,8}(걱정|불안|무서|두려)",
    r"(걱정|불안).{0,8}(잠|자야|못\s*자)", r"평생.{0,6}못\s*자", r"내일.{0,10}(망|큰일)",
)]


def detect_signals(message: str | None) -> set[str]:
    if message and any(p.search(message) for p in _NEGATIVE_THOUGHT):
        return {"NEGATIVE_SLEEP_THOUGHT"}
    return set()


def evaluate_rules(risk: dict, signals: set[str] | None = None) -> list[dict]:
    """Pure: matched candidates ordered by priority, then CBT-I category order, then rule order."""
    signals = signals or set()
    factors = set(risk["risk_factors"])
    severe = {f["code"] for f in risk.get("factor_details", []) if f.get("severe")}
    by_code: dict[str, dict] = {}
    for order, rule in enumerate(RULES):
        matched = (rule.any_factors & factors) | (rule.all_factors if rule.all_factors and rule.all_factors <= factors else set()) | (rule.signals & signals)
        if not matched:
            continue
        priority = "HIGH" if matched & severe else rule.priority
        entry = by_code.setdefault(rule.code, {"code": rule.code, "category": rule.category, "priority": priority, "reason_codes": [], "rule_ids": [], "order": order})
        if PRIORITY_RANK[priority] < PRIORITY_RANK[entry["priority"]]:
            entry["priority"] = priority
        entry["reason_codes"] += [code for code in sorted(matched) if code not in entry["reason_codes"]]
        entry["rule_ids"].append(rule.rule_id)
    candidates = sorted(by_code.values(), key=lambda c: (PRIORITY_RANK[c["priority"]], CATEGORY_RANK[c["category"]], c["order"]))
    if not candidates:
        reason = "BASELINE_COLLECTING" if risk.get("baseline_status") != "ready" else "MAINTAIN_ROUTINE"
        candidates = [{"code": DEFAULT_CODE, "category": "SLEEP_SCHEDULE", "priority": "LOW", "reason_codes": [reason], "rule_ids": ["R-DEFAULT"], "order": len(RULES)}]
    return candidates


def _catalog(db: Session, codes: list[str]) -> dict[str, Intervention]:
    rows = db.scalars(select(Intervention).where(Intervention.code.in_(codes), Intervention.active.is_(True), Intervention.requires_expert_review.is_(False)))
    return {row.code: row for row in rows}


def _mission_ids(db: Session, intervention_ids: list[int]) -> dict[int, int]:
    rows = db.execute(select(Mission.intervention_id, Mission.mission_id).where(Mission.intervention_id.in_(intervention_ids), Mission.active.is_(True)).order_by(Mission.mission_id))
    result: dict[int, int] = {}
    for intervention_id, mission_id in rows:
        result.setdefault(intervention_id, mission_id)
    return result


def recommend(db: Session, risk: dict, signals: set[str] | None = None) -> dict:
    candidates = evaluate_rules(risk, signals)
    catalog = _catalog(db, [c["code"] for c in candidates] + [DEFAULT_CODE])
    # Candidates whose catalog entry is missing, inactive or under expert review are skipped,
    # never invented; the default schedule intervention is used if nothing approved remains.
    available = [c for c in candidates if c["code"] in catalog]
    if not available:
        reasons = sorted({code for c in candidates for code in c["reason_codes"]})
        available = [{"code": DEFAULT_CODE, "category": "SLEEP_SCHEDULE", "priority": "LOW", "reason_codes": reasons, "rule_ids": ["R-DEFAULT"], "order": len(RULES)}]
    missions = _mission_ids(db, [row.intervention_id for row in catalog.values()])

    def describe(candidate: dict) -> dict:
        row = catalog.get(candidate["code"])
        return {
            "intervention_id": row.intervention_id if row else None,
            "code": candidate["code"],
            "category": candidate["category"],
            "priority": candidate["priority"],
            "reason_codes": candidate["reason_codes"],
            "mission_id": missions.get(row.intervention_id) if row else None,
        }

    primary = describe(available[0])
    row = catalog.get(available[0]["code"])
    is_default = available[0]["rule_ids"] == ["R-DEFAULT"]
    return {
        **primary,
        "requires_llm_explanation": row is not None and not is_default and primary["priority"] in {"HIGH", "MEDIUM"},
        "title": row.title if row else "오늘의 수면 습관",
        "message": row.message_template if row else "매일 비슷한 시간에 일어나는 것부터 시작해 보세요.",
        "instruction": row.instruction if row else "기상 시각을 일정하게 유지하도록 안내",
        "prohibited": list(row.prohibited or []) if row else [],
        "rule_ids": available[0]["rule_ids"],
        "risk_level": risk["risk_level"],
        "window_days": risk["window_days"],
        "alternatives": [describe(c) for c in available[1:4]],
    }
