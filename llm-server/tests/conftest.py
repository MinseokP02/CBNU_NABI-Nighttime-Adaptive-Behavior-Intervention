import os

os.environ["LLM_INTERNAL_TOKEN"] = "test-internal-token-123456"
os.environ["LLM_PROVIDER"] = "mock"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.config import get_settings  # noqa: E402
from app.main import app  # noqa: E402

TOKEN = {"X-Internal-Token": "test-internal-token-123456"}


@pytest.fixture
def settings():
    s = get_settings()
    snapshot = s.model_dump()
    yield s
    for key, value in snapshot.items():
        setattr(s, key, value)


@pytest.fixture
def client():
    with TestClient(app) as c:
        yield c


def generate_body(message: str = "어제도 잠을 잘 못 잤어.", category: str | None = "RELAXATION") -> dict:
    body = {
        "request_id": "req_1001",
        "user_message": message,
        "context": {
            "sleep_state": "DETERIORATING",
            "behavior_state": "AT_RISK",
            "risk_level": "MODERATE",
            "baseline_summary": {"median_sleep_min": 390, "median_efficiency": 0.86},
        },
        "cat_persona": "friendly_supportive",
    }
    if category:
        body["intervention"] = {"category": category, "instruction": "취침 전 짧은 이완 활동 안내"}
    return body
