from datetime import timedelta

from conftest import API
from factories import START, add_night, make_user, normal_baseline

from app.core.security import create_access_token
from app.models.intervention import Intervention
from app.seed.seed_interventions import seed_catalog
from app.services.intervention_service import detect_signals, evaluate_rules, recommend


def _risk(factors, severe=(), baseline_status="ready", risk_level="MODERATE"):
    return {
        "risk_factors": list(factors), "risk_level": risk_level, "window_days": 7, "baseline_status": baseline_status,
        "factor_details": [{"code": code, "severe": code in severe} for code in factors],
    }


def test_prolonged_sleep_onset_selects_stimulus_control():
    top = evaluate_rules(_risk(["PROLONGED_SLEEP_ONSET"]))[0]
    assert (top["category"], top["priority"], top["reason_codes"]) == ("STIMULUS_CONTROL", "HIGH", ["PROLONGED_SLEEP_ONSET"])


def test_priority_and_category_ordering():
    candidates = evaluate_rules(_risk(["LATE_CAFFEINE", "IRREGULAR_BEDTIME", "LOW_SLEEP_EFFICIENCY", "HIGH_STRESS"]))
    assert [c["category"] for c in candidates] == ["STIMULUS_CONTROL", "SLEEP_SCHEDULE", "RELAXATION", "SLEEP_HYGIENE"]


def test_severe_factor_escalates_priority():
    top = evaluate_rules(_risk(["IRREGULAR_BEDTIME"], severe={"IRREGULAR_BEDTIME"}))[0]
    assert top["priority"] == "HIGH"


def test_cognitive_rules():
    assert detect_signals("오늘도 못 잘 것 같아서 걱정돼") == {"NEGATIVE_SLEEP_THOUGHT"}
    assert detect_signals("오늘 날씨 좋다") == set()
    by_signal = evaluate_rules(_risk([]), {"NEGATIVE_SLEEP_THOUGHT"})[0]
    assert by_signal["category"] == "COGNITIVE"
    by_combo = evaluate_rules(_risk(["POOR_SLEEP_QUALITY", "HIGH_STRESS"]))
    assert {c["category"] for c in by_combo} == {"COGNITIVE", "RELAXATION"}
    # Requires both factors.
    assert all(c["category"] != "COGNITIVE" for c in evaluate_rules(_risk(["POOR_SLEEP_QUALITY"])))


def test_default_when_nothing_matches():
    top = evaluate_rules(_risk([], baseline_status="collecting", risk_level="UNKNOWN"))[0]
    assert top["code"] == "SS_FIXED_WAKE_TIME" and top["priority"] == "LOW" and top["reason_codes"] == ["BASELINE_COLLECTING"]


def test_recommend_uses_catalog_and_skips_expert_review(db):
    seed_catalog(db)
    rec = recommend(db, _risk(["PROLONGED_SLEEP_ONSET"]))
    assert rec["intervention_id"] and rec["mission_id"] and rec["requires_llm_explanation"] is True
    assert rec["code"] == "SC_GET_OUT_OF_BED"
    db.query(Intervention).filter_by(code="SC_GET_OUT_OF_BED").update({"requires_expert_review": True})
    db.commit()
    fallback = recommend(db, _risk(["PROLONGED_SLEEP_ONSET"]))
    assert fallback["code"] == "SS_FIXED_WAKE_TIME" and fallback["rule_ids"] == ["R-DEFAULT"]
    assert fallback["intervention_id"] is not None


def test_seed_is_idempotent(db):
    seed_catalog(db)
    seed_catalog(db)
    assert db.query(Intervention).count() == 10
    assert {row.category for row in db.query(Intervention)} == {"STIMULUS_CONTROL", "SLEEP_SCHEDULE", "RELAXATION", "COGNITIVE", "SLEEP_HYGIENE"}


def test_recommendations_api(client, db):
    seed_catalog(db)
    user = make_user(db)
    normal_baseline(db, user)
    for index in range(7):
        add_night(db, user, START + timedelta(days=14 + index), sol=50)
    db.commit()
    headers = {"Authorization": f"Bearer {create_access_token(user.user_id)}"}
    body = client.get(f"{API}/interventions/recommendations", headers=headers).json()
    assert body["category"] == "STIMULUS_CONTROL" and body["priority"] == "HIGH"
    assert "PROLONGED_SLEEP_ONSET" in body["reason_codes"]
    assert body["mission_id"] is not None and body["requires_llm_explanation"] is True
    assert body["explanation"] is None
    explained = client.get(f"{API}/interventions/recommendations", params={"include_explanation": True}, headers=headers).json()
    assert explained["explanation"] and explained["explanation_source"] == "fallback"
