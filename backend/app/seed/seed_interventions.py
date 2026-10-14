from sqlalchemy import select

from app.core.database import SessionLocal
from app.models.intervention import Intervention
from app.models.mission import Mission

SEEDS = [
    ("DIGITAL_USE", "PRE_BED_PHONE_OVERUSE", "화면과 잠시 거리두기", "오늘은 잠들기 전 화면 사용을 조금 줄여 보세요.", "NIGHTTIME_SCREEN_TIME", 30, 30),
    ("PHYSICAL_ACTIVITY", "LOW_DAILY_ACTIVITY", "가벼운 움직임", "무리하지 않는 범위에서 오늘 조금 더 걸어 보세요.", "STEPS", 5000, 30),
    ("BEDTIME_ROUTINE", "DELAYED_SLEEP_ONSET", "차분한 취침 준비", "정한 시간에 조명을 낮추고 취침 준비를 시작해 보세요.", "BEDTIME", 1380, 25),
    ("SLEEP_ROUTINE", "LOW_SLEEP_EFFICIENCY", "수면 일지 기록", "오늘의 수면을 짧게 기록해 나만의 흐름을 살펴보세요.", "SLEEP_DIARY", None, 20),
]


def seed() -> None:
    with SessionLocal() as db:
        for category, rule, title, message, verification, target, reward in SEEDS:
            intervention = db.scalar(select(Intervention).where(Intervention.category == category))
            if intervention is None:
                intervention = Intervention(category=category, rule=rule, title=title, message_template=message, active=True)
                db.add(intervention); db.flush()
            mission_title = f"{title} 미션"
            if db.scalar(select(Mission).where(Mission.title == mission_title)) is None:
                db.add(Mission(intervention_id=intervention.intervention_id, title=mission_title, description=message, verification_type=verification, target_value=target, reward_exp=reward, active=True))
        db.commit()


if __name__ == "__main__":
    seed()
