"""Test data builders. Users live in Asia/Seoul; night `day` starts at local 23:00 + bed_offset."""
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy.orm import Session

from app.core.security import hash_password
from app.models.behavior_record import BehaviorRecord
from app.models.cat_profile import CatProfile
from app.models.sleep_record import SleepRecord
from app.models.user import User

KST = ZoneInfo("Asia/Seoul")
START = date(2026, 9, 1)


def make_user(db: Session, email: str = "engine@example.com", profile: dict | None = None) -> User:
    user = User(email=email, password_hash=hash_password("password-long-enough"), isi_score=10, timezone="Asia/Seoul", profile=profile)
    db.add(user)
    db.flush()
    db.add(CatProfile(user_id=user.user_id, name="Nabi"))
    db.commit()
    return user


def add_night(db: Session, user: User, day: date, tst: int = 410, sol: int = 15, waso: int = 10, bed_offset: int = 0, out_extra: int = 5, quality: int | None = None) -> SleepRecord:
    bedtime = datetime.combine(day, datetime.min.time(), KST) + timedelta(hours=23, minutes=bed_offset)
    final = bedtime + timedelta(minutes=sol + tst + waso)
    out = final + timedelta(minutes=out_extra)
    tib = sol + tst + waso + out_extra
    row = SleepRecord(
        user_id=user.user_id, sleep_date=day, bedtime=bedtime, sleep_onset_latency_min=sol,
        wake_after_sleep_onset_min=waso, final_awakening=final, out_of_bed_time=out, time_in_bed_min=tib,
        total_sleep_time_min=tst, sleep_efficiency=round(tst / tib, 4), sleep_quality=quality, source="android",
    )
    db.add(row)
    return row


def add_behavior(db: Session, user: User, day: date, **fields) -> BehaviorRecord:
    row = BehaviorRecord(user_id=user.user_id, record_date=day, source="android", **fields)
    db.add(row)
    return row


def normal_baseline(db: Session, user: User, days: int = 14, start: date = START) -> None:
    """Regular nights with small variation: TST 400-420, SOL 10-20, WASO 5-15, bedtime ±10 min."""
    for index in range(days):
        add_night(db, user, start + timedelta(days=index), tst=400 + (index % 3) * 10, sol=10 + (index % 3) * 5, waso=5 + (index % 3) * 5, bed_offset=(index % 3 - 1) * 10)
    db.commit()
