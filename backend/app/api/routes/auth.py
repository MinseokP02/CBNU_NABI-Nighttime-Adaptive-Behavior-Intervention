from fastapi import APIRouter, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.api.deps import CurrentUser, DbSession
from app.core.exceptions import AppError
from app.core.security import create_access_token, hash_password, verify_password
from app.models.cat_profile import CatProfile
from app.models.user import User
from app.schemas.auth import LoginRequest, RegisterRequest, TokenResponse
from app.schemas.user import UserResponse

router = APIRouter()


@router.post("/register", response_model=UserResponse, status_code=status.HTTP_201_CREATED)
def register(payload: RegisterRequest, db: DbSession) -> User:
    user = User(email=str(payload.email).lower(), password_hash=hash_password(payload.password), isi_score=payload.isi_score, timezone=payload.timezone)
    db.add(user)
    try:
        db.flush()
        db.add(CatProfile(user_id=user.user_id, name=payload.cat_name))
        db.commit()
        db.refresh(user)
    except IntegrityError:
        db.rollback()
        raise AppError(409, "EMAIL_EXISTS", "Email is already registered") from None
    return user


@router.post("/login", response_model=TokenResponse)
def login(payload: LoginRequest, db: DbSession) -> TokenResponse:
    user = db.scalar(select(User).where(User.email == str(payload.email).lower()))
    if user is None or not verify_password(payload.password, user.password_hash):
        raise AppError(401, "INVALID_CREDENTIALS", "Email or password is incorrect")
    return TokenResponse(access_token=create_access_token(user.user_id))


@router.get("/me", response_model=UserResponse)
def me(current_user: CurrentUser) -> User:
    return current_user
