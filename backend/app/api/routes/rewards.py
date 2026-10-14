from fastapi import APIRouter
from sqlalchemy import select

from app.api.deps import CurrentUser, DbSession
from app.models.reward_transaction import RewardTransaction
from app.schemas.reward import RewardResponse

router = APIRouter()


@router.get("/history", response_model=list[RewardResponse])
def history(db: DbSession, user: CurrentUser):
    return list(db.scalars(select(RewardTransaction).where(RewardTransaction.user_id == user.user_id).order_by(RewardTransaction.created_at.desc())))
