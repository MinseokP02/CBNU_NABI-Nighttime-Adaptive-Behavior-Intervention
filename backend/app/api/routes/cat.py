from fastapi import APIRouter

from app.api.deps import CurrentUser, DbSession
from app.schemas.cat import CatStateResponse
from app.services.cat_service import cat_state

router = APIRouter()


@router.get("/state", response_model=CatStateResponse)
def state(db: DbSession, user: CurrentUser) -> dict:
    return cat_state(db, user)
