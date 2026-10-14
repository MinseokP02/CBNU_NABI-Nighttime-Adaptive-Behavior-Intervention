from test_baseline import _seed_days

from app.models.baseline import Baseline
from app.services.risk_service import analyze_risk


def test_mad_zero_is_unscorable_when_changed(db):
    user = _seed_days(db)
    result = analyze_risk(db, user.user_id)
    assert result["scores"]["waso"]["status"] == "OK"
    baseline = db.query(Baseline).filter_by(user_id=user.user_id, metric_name="waso").one()
    baseline.median = 4
    db.commit()
    result = analyze_risk(db, user.user_id)
    assert result["scores"]["waso"]["status"] == "DEVIATION_UNSCORABLE"


def test_delayed_sleep_and_low_steps_detection(db):
    user = _seed_days(db)
    analyze_risk(db, user.user_id)
    bedtime = db.query(Baseline).filter_by(user_id=user.user_id, metric_name="bedtime").one()
    steps = db.query(Baseline).filter_by(user_id=user.user_id, metric_name="steps").one()
    bedtime.mad = 5; bedtime.median = -120
    steps.mad = 500; steps.median = 10000
    db.commit()
    result = analyze_risk(db, user.user_id)
    assert result["risk_level"] in {"WARNING", "HIGH"}
    assert "bedtime" in result["risk_factors"] or "steps" in result["risk_factors"]


def test_recent_days_reported(db):
    user = _seed_days(db, 16)
    result = analyze_risk(db, user.user_id)
    assert result["required_days"] == 7
    assert result["available_days"] == 7


def test_pre_bed_phone_overuse_detection(db):
    user = _seed_days(db)
    analyze_risk(db, user.user_id)
    baseline = db.query(Baseline).filter_by(user_id=user.user_id, metric_name="nighttime_screen_time").one()
    baseline.median = 0
    baseline.mad = 5
    db.commit()
    result = analyze_risk(db, user.user_id)
    assert result["behavior_state"] == "PRE_BED_PHONE_OVERUSE"
