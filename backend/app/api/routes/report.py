from datetime import UTC, date, datetime
from zoneinfo import ZoneInfo

from fastapi import APIRouter

from app.api.deps import CurrentUser, DbSession
from app.schemas.report import WeeklyReportResponse
from app.services.report_service import weekly_report

router = APIRouter()


@router.get("/weekly", response_model=WeeklyReportResponse)
def weekly(db: DbSession, user: CurrentUser, end_date: date | None = None) -> dict:
    end = end_date or datetime.now(UTC).astimezone(ZoneInfo(user.timezone)).date()
    return weekly_report(db, user, end)
