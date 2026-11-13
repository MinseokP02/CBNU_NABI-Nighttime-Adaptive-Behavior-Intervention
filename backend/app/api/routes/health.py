import httpx
from fastapi import APIRouter
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.api.deps import DbSession
from app.core.config import get_settings
from app.core.exceptions import AppError

router = APIRouter()


def _database_ok(db) -> bool:
    try:
        db.execute(text("SELECT 1"))
        return True
    except SQLAlchemyError:
        return False


def _llm_status() -> str:
    settings = get_settings()
    if not settings.llm_enabled:
        return "disabled"
    try:
        response = httpx.get(f"{settings.llm_server_url.rstrip('/')}/internal/v1/llm/health", timeout=2.0)
        return "ok" if response.status_code == 200 else "unavailable"
    except httpx.HTTPError:
        return "unavailable"


@router.get("/api/v1/health")
def health_v1(db: DbSession) -> dict:
    """API-19: server status including dependencies. LLM outage is degraded, not down (fallback replies)."""
    database = "ok" if _database_ok(db) else "unavailable"
    llm = _llm_status()
    status = "ok" if database == "ok" and llm != "unavailable" else ("down" if database != "ok" else "degraded")
    return {"status": status, "database": database, "llm": llm, "version": "1.0.0"}


@router.get("/health")
def liveness() -> dict:
    return {"status": "ok"}


@router.get("/ready")
def ready(db: DbSession) -> dict:
    if not _database_ok(db):
        raise AppError(503, "DATABASE_UNAVAILABLE", "Database is not ready")
    return {"status": "ready"}
