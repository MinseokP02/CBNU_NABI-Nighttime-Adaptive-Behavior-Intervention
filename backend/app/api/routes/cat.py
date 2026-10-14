from fastapi import APIRouter
from sqlalchemy import select

from app.api.deps import CurrentUser, DbSession
from app.models.cat_profile import CatProfile
from app.schemas.cat import CatResponse

router = APIRouter()


@router.get("/profile", response_model=CatResponse)
def profile(db: DbSession, user: CurrentUser):
    return db.scalar(select(CatProfile).where(CatProfile.user_id == user.user_id))
