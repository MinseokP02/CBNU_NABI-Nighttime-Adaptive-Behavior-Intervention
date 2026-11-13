from typing import Annotated

import jwt
from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.exceptions import AppError
from app.core.security import decode_access_token
from app.models.user import User

bearer = HTTPBearer(auto_error=False)
DbSession = Annotated[Session, Depends(get_db)]


def get_current_user(db: DbSession, credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]) -> User:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise AppError(401, "UNAUTHORIZED", "Bearer token is required")
    try:
        user_id = decode_access_token(credentials.credentials)
    except (jwt.PyJWTError, ValueError):
        raise AppError(401, "UNAUTHORIZED", "Access token is invalid or expired") from None
    user = db.get(User, user_id)
    if user is None:
        raise AppError(401, "UNAUTHORIZED", "Token subject no longer exists")
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]


def analysis_window(window: int = 7) -> int:
    """Risk window query param: only 3 or 7 days are allowed (spec §4.4)."""
    if window not in (3, 7):
        raise AppError(422, "VALIDATION_ERROR", "window must be 3 or 7", {"allowed": [3, 7]})
    return window


AnalysisWindow = Annotated[int, Depends(analysis_window)]
