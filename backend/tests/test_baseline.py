from datetime import UTC, date, datetime, timedelta

from app.core.security import hash_password
from app.models.behavior_record import BehaviorRecord
from app.models.sleep_record import SleepRecord
from app.models.user import User
from app.services.baseline_service import build_baseline


def _seed_days(db, count=21):
    user = User(email="base@example.com", password_hash=hash_password("password-long"), isi_score=10, timezone="Asia/Seoul")
    db.add(user); db.flush()
    start = date(2026, 1, 1)
    for index in range(count):
        day = start + timedelta(days=index)
        bedtime = datetime(2025, 12, 31, 14, tzinfo=UTC) + timedelta(days=index)
        db.add(SleepRecord(user_id=user.user_id, sleep_date=day, bedtime=bedtime, wake_time=bedtime + timedelta(hours=8), sol=10 + index % 3, waso=5, tst=450, se=93.75, source="android"))
        db.add(BehaviorRecord(user_id=user.user_id, record_date=day, screen_time=100 + index % 3, nighttime_screen_time=20, steps=7000, app_usage={"source": "android"}))
    db.commit()
    return user


def test_baseline_insufficient(db):
    user = _seed_days(db, 10)
    assert build_baseline(db, user.user_id)["status"] == "INSUFFICIENT_DATA"


def test_baseline_median_mad_and_no_recent_leakage(db):
    user = _seed_days(db)
    result = build_baseline(db, user.user_id)
    sol = next(item for item in result["metrics"] if item["metric_name"] == "sol")
    assert sol["sample_count"] == 14
    assert sol["median"] == 11
    assert sol["window_end"] == date(2026, 1, 14)
