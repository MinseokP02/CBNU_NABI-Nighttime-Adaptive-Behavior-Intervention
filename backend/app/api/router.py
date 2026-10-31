from fastapi import APIRouter

from app.api.routes import (
    analysis,
    auth,
    behavior,
    cat,
    chat,
    health,
    intervention,
    mission,
    report,
    rewards,
    sleep,
    users,
)

API_PREFIX = "/api/v1"

api_router = APIRouter()
api_router.include_router(auth.router, prefix=f"{API_PREFIX}/auth", tags=["auth"])
api_router.include_router(users.router, prefix=f"{API_PREFIX}/users", tags=["users"])
api_router.include_router(sleep.router, prefix=f"{API_PREFIX}/sleep-records", tags=["sleep"])
api_router.include_router(behavior.router, prefix=f"{API_PREFIX}/behavior-records", tags=["behavior"])
api_router.include_router(analysis.router, prefix=f"{API_PREFIX}/analysis", tags=["analysis"])
api_router.include_router(intervention.router, prefix=f"{API_PREFIX}/interventions", tags=["intervention"])
api_router.include_router(mission.router, prefix=f"{API_PREFIX}/missions", tags=["missions"])
api_router.include_router(chat.router, prefix=f"{API_PREFIX}/chat", tags=["chat"])
api_router.include_router(cat.router, prefix=f"{API_PREFIX}/cat", tags=["cat"])
api_router.include_router(report.router, prefix=f"{API_PREFIX}/reports", tags=["reports"])
api_router.include_router(rewards.router, prefix=f"{API_PREFIX}/rewards", tags=["rewards"])
api_router.include_router(health.router, tags=["health"])
