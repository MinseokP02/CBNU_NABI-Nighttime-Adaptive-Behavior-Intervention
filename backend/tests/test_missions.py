from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime

import pytest
from conftest import TestingSession

from app.core.exceptions import AppError
from app.core.security import hash_password
from app.models.behavior_record import BehaviorRecord
from app.models.cat_profile import CatProfile
from app.models.intervention import Intervention
from app.models.mission import Mission
from app.models.user import User
from app.services.mission_service import active_assignments, complete_mission


def _mission_data(db, steps=6000, reward=30):
    user = User(email="mission@example.com", password_hash=hash_password("password-long"), isi_score=10)
    db.add(user); db.flush(); db.add(CatProfile(user_id=user.user_id, name="Nabi"))
    intervention = Intervention(category="PHYSICAL_ACTIVITY", rule="LOW_DAILY_ACTIVITY", title="Move", message_template="Move gently", active=True)
    db.add(intervention); db.flush()
    mission = Mission(intervention_id=intervention.intervention_id, title="Walk", description="Walk", verification_type="STEPS", target_value=5000, reward_exp=reward, active=True)
    db.add(mission); db.commit()
    assignment = active_assignments(db, user.user_id)[0]
    if steps is not None:
        db.add(BehaviorRecord(user_id=user.user_id, record_date=datetime.now(UTC).date(), screen_time=10, nighttime_screen_time=2, steps=steps, app_usage={"source": "android"})); db.commit()
    return user, assignment


def test_mission_assignment(db):
    _, assignment = _mission_data(db, None)
    assert assignment.status == "ASSIGNED"
    assert assignment.verification_snapshot["integrity_limit"]


def test_incomplete_mission_rejected(db):
    user, assignment = _mission_data(db, 100)
    with pytest.raises(AppError) as exc: complete_mission(db, user.user_id, assignment.assignment_id)
    assert exc.value.status_code == 409


def test_success_and_duplicate_idempotency(db):
    user, assignment = _mission_data(db)
    first = complete_mission(db, user.user_id, assignment.assignment_id)
    second = complete_mission(db, user.user_id, assignment.assignment_id)
    assert first["reward_exp"] == 30
    assert second["idempotent"] is True and second["reward_exp"] == 0


def test_cat_level_up(db):
    user, assignment = _mission_data(db, reward=250)
    result = complete_mission(db, user.user_id, assignment.assignment_id)
    assert (result["cat_level"], result["cat_exp"]) == (2, 150)


def test_concurrent_completion_pays_once(db):
    user, assignment = _mission_data(db)

    def complete_once():
        with TestingSession() as session:
            return complete_mission(session, user.user_id, assignment.assignment_id)

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(lambda _: complete_once(), range(2)))
    assert sorted(result["reward_exp"] for result in results) == [0, 30]
