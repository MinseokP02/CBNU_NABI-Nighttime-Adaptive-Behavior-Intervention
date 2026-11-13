from datetime import timedelta

from conftest import API
from factories import START, add_behavior, add_night, make_user, normal_baseline

from app.core.security import create_access_token
from app.models.baseline import Baseline
from app.models.risk_analysis import RiskAnalysis
from app.services.baseline_service import current_baseline, robust_z
from app.services.risk_service import analyze_risk


def _headers(user):
    return {"Authorization": f"Bearer {create_access_token(user.user_id)}"}


def _recent(db, user, days=7, **kw):
    for index in range(days):
        add_night(db, user, START + timedelta(days=14 + index), **kw)
    db.commit()


def test_baseline_collecting_until_enough_valid_days(db):
    user = make_user(db)
    normal_baseline(db, user, days=9)
    result = current_baseline(db, user)
    assert result["status"] == "collecting"
    assert result["valid_days"] == 9 and result["metrics"] == {}


def test_baseline_ready_with_median_and_mad(db):
    user = make_user(db)
    normal_baseline(db, user, days=14)
    result = current_baseline(db, user)
    assert result["status"] == "ready" and result["valid_days"] == 14 and result["window_days"] == 14
    assert result["metrics"]["sleep_duration"]["median"] == 410
    assert result["metrics"]["sleep_duration"]["mad"] == 10
    assert 0 < result["metrics"]["sleep_efficiency"]["median"] < 1


def test_invalid_nights_do_not_count(db):
    user = make_user(db)
    normal_baseline(db, user, days=10)
    # Time in bed below 3 hours is not a valid diary night.
    add_night(db, user, START + timedelta(days=10), tst=100, sol=5, waso=5, out_extra=5)
    db.commit()
    assert current_baseline(db, user)["valid_days"] == 10


def test_rolling_baseline_excludes_recent_window(db):
    user = make_user(db)
    normal_baseline(db, user, days=14)
    _recent(db, user, sol=60, waso=60)
    result = current_baseline(db, user)
    assert str(result["window_end"]) == str(START + timedelta(days=13))
    assert result["metrics"]["sleep_onset_latency"]["median"] == 15


def test_baseline_snapshot_reused_until_inputs_change(db):
    user = make_user(db)
    normal_baseline(db, user, days=12)
    first = current_baseline(db, user)["baseline_id"]
    assert current_baseline(db, user)["baseline_id"] == first
    add_night(db, user, START + timedelta(days=12))
    db.commit()
    assert current_baseline(db, user)["baseline_id"] != first
    assert db.query(Baseline).count() == 2


def test_robust_z_stabilization():
    # MAD = 0 would divide by zero; the floor keeps z finite and marks it.
    z, floored = robust_z(0.80, 0.90, 0.0, "sleep_efficiency")
    assert floored and round(z, 2) == round(-0.10 / (1.4826 * 0.02), 2)
    z, floored = robust_z(500, 400, 1000, "sleep_duration")
    assert not floored and abs(z) < 1
    assert robust_z(10_000, 0, 0, "sleep_duration")[0] == 10.0


def test_spec_example_moderate_risk(db):
    """Lower efficiency + irregular bedtime + caffeine/screen habits -> DETERIORATING / AT_RISK / MODERATE."""
    user = make_user(db)
    normal_baseline(db, user, days=14)
    # Bedtime shifts ±70 min while the wake time stays put; extra time in bed lowers efficiency.
    for index, offset in enumerate([-70, 0, 70, -70, 0, 70, 0]):
        add_night(db, user, START + timedelta(days=14 + index), tst=400 - offset, sol=15, waso=10, out_extra=37, bed_offset=offset)
    for index in range(7):
        add_behavior(db, user, START + timedelta(days=14 + index), caffeine_after_cutoff=index % 2 == 0, screen_before_bed_min=90, stress_level=2)
    db.commit()
    result = analyze_risk(db, user, 7)
    assert result["sleep_state"] == "DETERIORATING"
    assert result["behavior_state"] == "AT_RISK"
    assert result["risk_level"] == "MODERATE"
    assert result["risk_factors"][:2] == ["LOW_SLEEP_EFFICIENCY", "IRREGULAR_BEDTIME"]
    assert {"LATE_CAFFEINE", "EXCESSIVE_PRE_BED_SCREEN"} <= set(result["risk_factors"])
    se = next(f for f in result["factor_details"] if f["code"] == "LOW_SLEEP_EFFICIENCY")
    assert se["basis"] == "personal_baseline" and not se["severe"]


def test_high_risk_with_several_sleep_factors(db):
    user = make_user(db)
    normal_baseline(db, user, days=14)
    _recent(db, user, tst=330, sol=50, waso=50)
    result = analyze_risk(db, user, 7)
    assert result["risk_level"] == "HIGH"
    assert {"PROLONGED_SLEEP_ONSET", "FREQUENT_NIGHT_AWAKENING", "SHORT_SLEEP_DURATION"} <= set(result["risk_factors"])


def test_stable_and_improving(db):
    user = make_user(db)
    normal_baseline(db, user, days=14)
    _recent(db, user, tst=410, sol=15, waso=10)
    assert analyze_risk(db, user, 7)["sleep_state"] == "STABLE"

    other = make_user(db, "improving@example.com")
    for index in range(14):
        add_night(db, other, START + timedelta(days=index), tst=380, sol=25 + (index % 3) * 2, waso=25, out_extra=20)
    db.commit()
    _recent(db, other, tst=400, sol=8, waso=10, out_extra=5)
    result = analyze_risk(db, other, 7)
    assert result["sleep_state"] == "IMPROVING" and result["risk_level"] == "LOW"


def test_reference_rules_work_while_collecting(db):
    user = make_user(db)
    for index in range(3):
        add_night(db, user, START + timedelta(days=index), sol=45)
    db.commit()
    result = analyze_risk(db, user, 3)
    assert result["baseline_status"] == "collecting"
    assert result["sleep_state"] == "UNKNOWN"
    assert result["risk_factors"] == ["PROLONGED_SLEEP_ONSET"]
    assert result["factor_details"][0]["basis"] == "reference_threshold"
    assert result["risk_level"] == "MODERATE"


def test_window_three_days(db):
    user = make_user(db)
    normal_baseline(db, user, days=14)
    _recent(db, user, days=7)
    result = analyze_risk(db, user, 3)
    assert result["window_days"] == 3 and result["recent_valid_days"]["sleep"] == 3


def test_risk_api_window_validation_and_refresh(client, db):
    user = make_user(db)
    normal_baseline(db, user, days=14)
    headers = _headers(user)
    assert client.get(f"{API}/analysis/risk", params={"window": 5}, headers=headers).status_code == 422
    body = client.get(f"{API}/analysis/risk", params={"window": 3}, headers=headers).json()
    assert body["window_days"] == 3 and body["analysis_version"] == "1.0"
    baseline = client.get(f"{API}/analysis/baseline", headers=headers).json()
    assert baseline["status"] == "ready" and set(baseline["metrics"]["sleep_efficiency"]) >= {"median", "mad"}
    refreshed = client.post(f"{API}/analysis/refresh", headers=headers).json()
    assert [r["window_days"] for r in refreshed["risk"]] == [3, 7]
    assert db.query(RiskAnalysis).count() == 2
