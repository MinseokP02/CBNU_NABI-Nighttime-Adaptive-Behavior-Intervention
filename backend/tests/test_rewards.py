from test_missions import _mission_data

from app.models.reward_transaction import RewardTransaction
from app.services.mission_service import complete_mission


def test_reward_created_in_completion(db):
    user, assignment = _mission_data(db)
    complete_mission(db, user.user_id, assignment.assignment_id)
    rewards = db.query(RewardTransaction).all()
    assert len(rewards) == 1 and rewards[0].amount == 30


def test_failed_verification_rolls_back(db):
    from app.core.exceptions import AppError
    user, assignment = _mission_data(db, 10)
    try: complete_mission(db, user.user_id, assignment.assignment_id)
    except AppError: pass
    assert db.query(RewardTransaction).count() == 0
