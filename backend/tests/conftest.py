import os
from collections.abc import Generator

import pytest
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session, sessionmaker

from alembic import command

TEST_DATABASE_URL = os.getenv("TEST_DATABASE_URL", "")
if not TEST_DATABASE_URL or make_url(TEST_DATABASE_URL).database != "nabi_test_db":
    raise RuntimeError("Tests require TEST_DATABASE_URL pointing exactly to nabi_test_db")
os.environ["APP_ENV"] = "test"
os.environ.setdefault("POSTGRES_PASSWORD", "test-only")
os.environ.setdefault("JWT_SECRET_KEY", "test-only-secret-at-least-32-characters")

from app.core.database import get_db  # noqa: E402
from app.main import app  # noqa: E402

test_engine = create_engine(TEST_DATABASE_URL, pool_pre_ping=True)
TestingSession = sessionmaker(bind=test_engine, expire_on_commit=False)


@pytest.fixture(scope="session", autouse=True)
def migrated_database() -> Generator[None, None, None]:
    config = Config("alembic.ini")
    command.upgrade(config, "head")
    yield
    command.downgrade(config, "base")


@pytest.fixture(autouse=True)
def clean_database(migrated_database) -> None:
    with test_engine.begin() as connection:
        connection.execute(text("TRUNCATE reward_transactions, mission_results, mission_assignments, cat_profiles, risk_analyses, baselines, behavior_records, sleep_records, missions, interventions, users RESTART IDENTITY CASCADE"))


@pytest.fixture
def db() -> Generator[Session, None, None]:
    with TestingSession() as session:
        yield session


@pytest.fixture
def client() -> Generator[TestClient, None, None]:
    def override_db():
        with TestingSession() as session:
            yield session
    app.dependency_overrides[get_db] = override_db
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()


def register_and_login(client: TestClient, email: str = "user@example.com") -> tuple[dict, dict]:
    registered = client.post("/api/auth/register", json={"email": email, "password": "correct-horse-battery", "isi_score": 10}).json()
    token = client.post("/api/auth/login", json={"email": email, "password": "correct-horse-battery"}).json()["access_token"]
    return registered, {"Authorization": f"Bearer {token}"}
