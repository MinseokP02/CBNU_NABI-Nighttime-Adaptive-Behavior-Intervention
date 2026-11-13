from datetime import UTC, datetime, timedelta

from conftest import API
from factories import START, add_behavior, add_night, make_user, normal_baseline

from app.core.security import create_access_token
from app.services.cat_service import cat_state


def test_cat_state_rules(db):
    user = make_user(db)
    night = datetime(2026, 10, 30, 17, 0, tzinfo=UTC)   # 02:00 KST
    day = datetime(2026, 10, 30, 3, 0, tzinfo=UTC)      # 12:00 KST
    assert cat_state(db, user, night)["cat_action"] == "SLEEP"
    assert cat_state(db, user, day)["cat_action"] == "IDLE"
    normal_baseline(db, user)
    for index in range(7):
        add_night(db, user, START + timedelta(days=14 + index), sol=50, waso=50, tst=330)
    db.commit()
    state = cat_state(db, user, day)
    assert state["cat_action"] == "COMFORT" and state["exp_to_next_level"] == 100


def test_weekly_report(client, db):
    user = make_user(db)
    for index in range(14):
        add_night(db, user, START + timedelta(days=index), tst=400 if index < 7 else 420)
    for index in range(7, 14):
        add_behavior(db, user, START + timedelta(days=index), caffeine_after_cutoff=index % 2 == 0, stress_level=3)
    db.commit()
    headers = {"Authorization": f"Bearer {create_access_token(user.user_id)}"}
    end = START + timedelta(days=13)
    body = client.get(f"{API}/reports/weekly", params={"end_date": str(end)}, headers=headers).json()
    assert body["start_date"] == str(START + timedelta(days=7))
    assert body["sleep"]["nights_logged"] == 7 and body["sleep"]["avg_total_sleep_time_min"] == 420
    assert body["behavior"]["late_caffeine_days"] == 3 and body["behavior"]["avg_stress_level"] == 3
    assert body["change_from_previous_week"]["avg_total_sleep_time_min"] == 20
