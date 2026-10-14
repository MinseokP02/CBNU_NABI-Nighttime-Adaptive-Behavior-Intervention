from fastapi import APIRouter

from app.api.routes import (
    analysis,
    auth,
    behavior_records,
    cat,
    chat,
    health,
    missions,
    rewards,
    sleep_records,
    users,
)

api_router = APIRouter()
api_router.include_router(auth.router, prefix="/api/auth", tags=["auth"])
api_router.include_router(users.router, prefix="/api/users", tags=["users"])
api_router.include_router(sleep_records.router, prefix="/api/sleep-records", tags=["sleep"])
api_router.include_router(behavior_records.router, prefix="/api/behavior-records", tags=["behavior"])
api_router.include_router(analysis.router, prefix="/api/analysis", tags=["analysis"])
api_router.include_router(missions.router, prefix="/api/missions", tags=["missions"])
api_router.include_router(cat.router, prefix="/api/cat", tags=["cat"])
api_router.include_router(rewards.router, prefix="/api/rewards", tags=["rewards"])
api_router.include_router(chat.router, prefix="/api/chat", tags=["chat"])
api_router.include_router(health.router, tags=["health"])
