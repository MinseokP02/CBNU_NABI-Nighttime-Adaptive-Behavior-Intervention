from fastapi import APIRouter
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.api.deps import DbSession
from app.core.exceptions import AppError

router = APIRouter()


@router.get("/health")
def health() -> dict:
    return {"status": "ok"}


@router.get("/ready")
def ready(db: DbSession) -> dict:
    try:
        db.execute(text("SELECT 1"))
    except SQLAlchemyError:
        raise AppError(503, "DATABASE_UNAVAILABLE", "Database is not ready") from None
    return {"status": "ready"}
