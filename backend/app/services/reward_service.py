from sqlalchemy.orm import Session

from app.models.cat_profile import CatProfile
from app.models.reward_transaction import RewardTransaction


def required_exp(level: int) -> int:
    return level * 100


def grant_exp(db: Session, cat: CatProfile, user_id, result_id: int, amount: int) -> RewardTransaction:
    reward = RewardTransaction(user_id=user_id, result_id=result_id, reward_type="EXP", amount=amount)
    db.add(reward)
    cat.exp += amount
    while cat.exp >= required_exp(cat.level):
        cat.exp -= required_exp(cat.level)
        cat.level += 1
    return reward
