from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from conftest import API, TestingSession
from factories import START, add_behavior, make_user

from app.core.exceptions import AppError
from app.core.security import create_access_token
from app.models.mission import Mission
from app.models.reward_transaction import RewardTransaction
from app.seed.seed_interventions import seed_catalog
from app.services.mission_service import active_assignments, complete_mission


def _caffeine_user(db, reward=None):
    """User whose recent logs show late caffeine, so the engine assigns the caffeine mission."""
    seed_catalog(db)
    if reward is not None:
        db.query(Mission).filter_by(verification_type="NO_LATE_CAFFEINE").update({"reward_exp": reward})
        db.commit()
    user = make_user(db)
    for index in range(7):
        add_behavior(db, user, START + timedelta(days=index), caffeine_after_cutoff=True)
    db.commit()
    assignment = active_assignments(db, user)[0]
    return user, assignment


def _log_today(db, user, **fields):
    today = datetime.now(UTC).astimezone(ZoneInfo(user.timezone)).date()
    add_behavior(db, user, today, **fields)
    db.commit()


def test_active_mission_comes_from_recommendation(db):
    user, assignment = _caffeine_user(db)
    assert assignment["category"] == "SLEEP_HYGIENE"
    assert assignment["verification_type"] == "NO_LATE_CAFFEINE"
    # Calling again returns the same active assignment instead of creating a new one.
    again = active_assignments(db, user)
    assert [a["assignment_id"] for a in again] == [assignment["assignment_id"]]


def test_not_verified_is_409(db):
    user, assignment = _caffeine_user(db)
    _log_today(db, user, caffeine_after_cutoff=True)
    with pytest.raises(AppError) as exc:
        complete_mission(db, user, assignment["mission_id"])
    assert exc.value.status_code == 409 and exc.value.code == "MISSION_NOT_VERIFIED"
    assert db.query(RewardTransaction).count() == 0


def test_unknown_mission_is_404(db):
    user, _ = _caffeine_user(db)
    with pytest.raises(AppError) as exc:
        complete_mission(db, user, 99999)
    assert exc.value.status_code == 404


def test_success_idempotent_and_reward(db):
    user, assignment = _caffeine_user(db)
    _log_today(db, user, caffeine_after_cutoff=False)
    first = complete_mission(db, user, assignment["mission_id"])
    second = complete_mission(db, user, assignment["mission_id"])
    assert first["reward_exp"] == 20 and first["verified"]
    assert second["idempotent"] is True and second["reward_exp"] == 0
    assert db.query(RewardTransaction).count() == 1


def test_cat_level_up(db):
    user, assignment = _caffeine_user(db, reward=250)
    _log_today(db, user, caffeine_after_cutoff=False)
    result = complete_mission(db, user, assignment["mission_id"])
    assert (result["cat_level"], result["cat_exp"]) == (2, 150)


def test_concurrent_completion_pays_once(db):
    user, assignment = _caffeine_user(db)
    _log_today(db, user, caffeine_after_cutoff=False)

    def complete_once():
        with TestingSession() as session:
            return complete_mission(session, session.merge(user), assignment["mission_id"])

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(lambda _: complete_once(), range(2)))
    assert sorted(result["reward_exp"] for result in results) == [0, 20]


def test_mission_api_and_cat_reaction(client, db):
    user, assignment = _caffeine_user(db)
    headers = {"Authorization": f"Bearer {create_access_token(user.user_id)}"}
    active = client.get(f"{API}/missions/active", headers=headers).json()
    assert active[0]["mission_id"] == assignment["mission_id"]
    _log_today(db, user, caffeine_after_cutoff=False)
    body = client.post(f"{API}/missions/{assignment['mission_id']}/complete", headers=headers).json()
    assert body["verified"] and body["cat_action"] == "HAPPY" and body["message"]
    cat = client.get(f"{API}/cat/state", headers=headers).json()
    assert cat["cat_action"] == "HAPPY" and cat["exp"] == 20
    report = client.get(f"{API}/reports/weekly", headers=headers).json()
    assert report["missions"] == {"completed": 1, "exp_earned": 20}
