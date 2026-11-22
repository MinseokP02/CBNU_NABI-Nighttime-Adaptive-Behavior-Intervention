from datetime import timedelta

from conftest import API
from factories import START, add_behavior, add_night, make_user, normal_baseline

from app.core.security import create_access_token
from app.services.trend_service import build_trends


def _headers(user):
    return {"Authorization": f"Bearer {create_access_token(user.user_id)}"}


def _metric(result, name):
    return next(m for m in result["metrics"] if m["metric"] == name)


def test_trends_band_matches_baseline_and_flags_recent_change(db):
    user = make_user(db)
    normal_baseline(db, user, days=14)
    for index in range(7):  # recent week: much longer sleep onset
        add_night(db, user, START + timedelta(days=14 + index), sol=60)
    db.commit()

    result = build_trends(db, user, days=14)
    assert result["baseline_status"] == "ready"
    assert str(result["end_date"]) == str(START + timedelta(days=20))
    sol = _metric(result, "sleep_onset_latency")
    assert len(sol["points"]) == 14  # one point per night in the window
    assert sol["band"]["median"] == 15
    assert sol["band"]["low"] < 15 < sol["band"]["high"] < 60
    assert sol["latest"]["value"] == 60 and sol["latest"]["status"] == "changed"
    # Nothing changed for total sleep: last night sits inside the usual range.
    assert _metric(result, "sleep_duration")["latest"]["status"] == "normal"
    # Band edges stay physically possible.
    assert _metric(result, "sleep_efficiency")["band"]["high"] <= 1
    assert _metric(result, "wake_after_sleep_onset")["band"]["low"] >= 0


def test_trends_bedtime_is_anchor_offset_and_shorter_onset_is_improved(db):
    user = make_user(db)
    normal_baseline(db, user, days=14)
    add_night(db, user, START + timedelta(days=14), sol=0, bed_offset=90)
    db.commit()
    result = build_trends(db, user, days=7)
    bedtime = _metric(result, "bedtime")
    assert bedtime["unit"] == "clock" and bedtime["anchor"] == "23:00"
    assert bedtime["latest"]["value"] == 90 and bedtime["latest"]["status"] == "changed"
    assert _metric(result, "sleep_onset_latency")["latest"]["status"] == "improved"


def test_trends_without_baseline_have_no_band(db):
    user = make_user(db)
    normal_baseline(db, user, days=5)
    result = build_trends(db, user, days=14)
    assert result["baseline_status"] == "collecting"
    sol = _metric(result, "sleep_onset_latency")
    assert sol["band"] is None and sol["latest"]["status"] == "unknown" and len(sol["points"]) == 5


def test_trends_behavior_uses_reference_threshold(db):
    user = make_user(db)
    add_behavior(db, user, START, exercise_duration_min=30, nap_duration_min=10)
    add_behavior(db, user, START + timedelta(days=1), exercise_duration_min=0, nap_duration_min=45)
    db.commit()
    result = build_trends(db, user, days=7)
    exercise = _metric(result, "exercise_duration_min")
    assert exercise["basis"] == "reference_threshold" and exercise["reference"] == 10
    assert [p["value"] for p in exercise["points"]] == [30, 0]
    assert exercise["latest"]["status"] == "changed"  # below 10 min
    assert _metric(result, "nap_duration_min")["latest"]["status"] == "changed"  # 30 min or more
    assert _metric(result, "screen_before_bed_min")["points"] == []  # not recorded → no points


def test_trends_api_and_validation(client, db):
    user = make_user(db)
    normal_baseline(db, user, days=3)
    response = client.get(f"{API}/analysis/trends", headers=_headers(user))
    assert response.status_code == 200
    body = response.json()
    assert body["days"] == 14 and [m["metric"] for m in body["metrics"]][:2] == ["bedtime", "sleep_onset_latency"]
    assert client.get(f"{API}/analysis/trends?days=3", headers=_headers(user)).status_code == 422
    assert client.get(f"{API}/analysis/trends").status_code == 401


def test_trends_empty_user(db):
    user = make_user(db)
    result = build_trends(db, user)
    assert result["end_date"] is None and all(m["points"] == [] and m["latest"] is None for m in result["metrics"])
