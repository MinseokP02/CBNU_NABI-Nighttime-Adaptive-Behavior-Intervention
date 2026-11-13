"""Idempotent seed of the approved CBT-I intervention and mission catalog.

Run: python -m app.seed.seed_interventions
Content is a draft for expert review (spec §5.3, §9). Sleep restriction is intentionally absent;
add it only with requires_expert_review=True after clinical review.
"""
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.intervention import Intervention
from app.models.mission import Mission

COMMON_PROHIBITED = ["약물·용량·복용 언급 금지", "진단 표현 금지", "수면 제한 처방 금지"]

# code, category, title, instruction (for LLM), message (fallback), extra prohibited, missions
CATALOG = [
    (
        "SC_GET_OUT_OF_BED", "STIMULUS_CONTROL", "잠이 안 오면 잠시 침대 밖으로",
        "누운 뒤 20분 정도 지나도 잠이 오지 않으면 침대에서 나와 어둡고 조용한 곳에서 쉬다가 졸릴 때 다시 눕도록 안내",
        "20분쯤 지나도 잠이 오지 않으면 잠시 침대에서 나와 조용히 쉬다가, 졸릴 때 다시 누워 보세요.",
        ["침대에서 시계를 계속 확인하라고 하지 않기"],
        [("20분 규칙 지키고 수면일지 쓰기", "잠이 오지 않으면 20분 뒤 침대에서 나오고, 아침에 수면일지를 기록해요.", "SLEEP_DIARY", None, 25)],
    ),
    (
        "SC_BED_FOR_SLEEP_ONLY", "STIMULUS_CONTROL", "침대는 잠잘 때만",
        "침대에서 휴대폰·TV·업무를 하지 않고 졸릴 때만 눕도록 안내",
        "침대에서는 휴대폰이나 영상 대신 잠만 자고, 졸릴 때 눕는 습관을 만들어 보세요.",
        [],
        [("침대에서 화면 보지 않기", "잠들기 전 화면 사용을 15분 이하로 줄여요.", "SCREEN_BEFORE_BED_MAX", 15, 25)],
    ),
    (
        "SS_FIXED_WAKE_TIME", "SLEEP_SCHEDULE", "같은 시간에 일어나기",
        "전날 잠을 설쳤더라도 이미 정해 둔 기상 시각에 일어나도록 안내. 새 수면 일정을 정해 주지 않음",
        "전날 잠을 설쳤더라도 정해 둔 시간에 일어나 보세요. 일정한 기상 시각이 리듬을 잡아 줘요.",
        ["새로운 취침·기상 시각을 처방하지 않기"],
        [("목표 기상 시각 지키기", "목표 기상 시각 ±30분 안에 일어나고 수면일지를 기록해요.", "WAKE_TIME_WINDOW", 30, 25)],
    ),
    (
        "SS_CONSISTENT_BEDTIME", "SLEEP_SCHEDULE", "일정한 취침 준비",
        "매일 비슷한 시각에 취침 준비를 시작하도록 안내. 새 수면 일정을 정해 주지 않음",
        "매일 비슷한 시간에 조명을 낮추고 잠자리 준비를 시작해 보세요.",
        ["새로운 취침·기상 시각을 처방하지 않기"],
        [("목표 취침 시각 지키기", "목표 취침 시각 ±30분 안에 잠자리에 들어요.", "BEDTIME_WINDOW", 30, 25)],
    ),
    (
        "RX_BREATHING", "RELAXATION", "자기 전 이완 호흡",
        "취침 전 5~10분 동안 천천히 복식호흡을 하거나 몸의 긴장을 차례로 푸는 이완 활동을 안내",
        "자기 전 5분 정도 천천히 숨을 쉬면서 어깨와 몸의 긴장을 풀어 보세요.",
        [],
        [("자기 전 10분 이완하기", "잠들기 전 호흡이나 스트레칭으로 10분간 긴장을 풀어요.", "RELAXATION_DONE", None, 20)],
    ),
    (
        "CG_WORRY_NOTE", "COGNITIVE", "걱정 노트",
        "잠에 대한 부정적 생각에 공감하고, 자기 전 걱정을 적어 두며 '오늘은 쉬는 것만으로도 괜찮다'는 관점을 지지적으로 안내. 생각을 비난하거나 반박하지 않음",
        "잠에 대한 걱정이 떠오르면 종이에 적어 두고, 오늘은 편히 쉬는 것만으로도 충분하다고 생각해 보세요.",
        ["사용자의 생각을 비난하거나 틀렸다고 단정하지 않기"],
        [("자기 전 걱정 노트 쓰기", "잠들기 전 머릿속 걱정을 세 줄로 적어 둬요.", "SELF_REPORT", None, 15)],
    ),
    (
        "SH_CAFFEINE_CUTOFF", "SLEEP_HYGIENE", "오후 카페인 줄이기",
        "정해 둔 제한 시각 이후에는 커피·에너지음료 등 카페인을 피하도록 안내",
        "오후 늦게는 커피나 에너지음료 대신 따뜻한 물이나 디카페인 음료를 골라 보세요.",
        [],
        [("제한 시각 이후 카페인 끊기", "오늘은 제한 시각 이후 카페인을 마시지 않아요.", "NO_LATE_CAFFEINE", None, 20)],
    ),
    (
        "SH_SCREEN_OFF", "SLEEP_HYGIENE", "잠들기 전 화면 내려놓기",
        "취침 전 화면 사용 시간을 줄이고 조명을 낮추도록 안내",
        "잠들기 30분 전에는 휴대폰을 내려놓고 조명을 낮춰 보세요.",
        [],
        [("취침 전 화면 30분 이하", "잠들기 전 화면 사용을 30분 이하로 줄여요.", "SCREEN_BEFORE_BED_MAX", 30, 20)],
    ),
    (
        "SH_NAP_LIMIT", "SLEEP_HYGIENE", "낮잠은 짧게",
        "낮잠이 필요하면 이른 오후에 30분 이내로 짧게 자도록 안내",
        "낮잠이 필요하면 이른 오후에 30분 이내로 짧게 자 보세요.",
        [],
        [("낮잠 30분 이하", "오늘 낮잠은 30분 이내로 해요.", "NAP_MAX", 30, 15)],
    ),
    (
        "SH_DAYTIME_ACTIVITY", "SLEEP_HYGIENE", "낮 동안 가볍게 움직이기",
        "무리하지 않는 범위에서 낮 시간에 가벼운 운동을 하도록 안내. 취침 직전 격한 운동은 피함",
        "무리하지 않는 범위에서 낮에 20분 정도 가볍게 걸어 보세요.",
        [],
        [("가벼운 운동 20분", "낮 시간에 걷기나 스트레칭을 20분 해요.", "EXERCISE_MIN", 20, 20)],
    ),
]


def seed_catalog(db: Session) -> None:
    for code, category, title, instruction, message, prohibited, missions in CATALOG:
        row = db.scalar(select(Intervention).where(Intervention.code == code))
        if row is None:
            row = Intervention(code=code)
            db.add(row)
        row.category, row.title, row.instruction, row.message_template = category, title, instruction, message
        row.prohibited = COMMON_PROHIBITED + prohibited
        row.requires_expert_review, row.active = False, True
        db.flush()
        for mission_title, description, verification, target, reward in missions:
            mission = db.scalar(select(Mission).where(Mission.title == mission_title))
            if mission is None:
                mission = Mission(title=mission_title)
                db.add(mission)
            mission.intervention_id, mission.description = row.intervention_id, description
            mission.verification_type, mission.target_value, mission.reward_exp, mission.active = verification, target, reward, True
    db.commit()


def seed() -> None:
    from app.core.database import SessionLocal

    with SessionLocal() as db:
        seed_catalog(db)


if __name__ == "__main__":
    seed()
