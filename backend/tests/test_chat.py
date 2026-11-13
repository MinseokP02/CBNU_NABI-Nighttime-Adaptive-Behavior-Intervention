import httpx
from conftest import API, register_and_login

from app.seed.seed_interventions import seed_catalog
from app.services import llm_client


def _llm_on(settings):
    settings.llm_enabled = True
    settings.llm_server_url = "http://llm.test:8100"
    settings.llm_internal_token = "internal-token-123456"


def _fake_llm(monkeypatch, body: dict | None = None, status: int = 200, capture: dict | None = None):
    def post(url, json, headers, timeout):
        if capture is not None:
            capture.update(url=url, json=json, headers=headers)
        payload = body or {
            "request_id": json["request_id"], "response": "잠드는 시간이 길어져서 힘들었겠다. 오늘은 편하게 쉬어 보자.",
            "intent": "SLEEP_COMPLAINT", "emotion": "EMPATHETIC", "cat_action": "SIT",
            "intervention_category": (json.get("intervention") or {}).get("category"),
            "safety_status": "PASS", "source": "llm", "model": "test",
        }
        return httpx.Response(status, json=payload, request=httpx.Request("POST", url))
    monkeypatch.setattr(llm_client, "_http_post", post)


def test_chat_fallback_when_llm_disabled(client, db):
    seed_catalog(db)
    _, headers = register_and_login(client)
    response = client.post(f"{API}/chat/messages", json={"message": "요즘 잠드는 데 시간이 너무 오래 걸려.", "client_context": {"screen": "home", "cat_animation": "idle"}}, headers=headers)
    assert response.status_code == 200
    body = response.json()
    assert body["conversation_id"].startswith("conv_") and body["message_id"].startswith("msg_")
    assert body["source"] == "fallback" and body["safety_status"] == "PASS"
    assert body["reply"] and body["cat_action"] == "SIT"


def test_chat_with_llm_server(client, db, settings, monkeypatch):
    seed_catalog(db)
    _llm_on(settings)
    captured: dict = {}
    _fake_llm(monkeypatch, capture=captured)
    _, headers = register_and_login(client)
    body = client.post(f"{API}/chat/messages", json={"message": "요즘 잠드는 데 시간이 너무 오래 걸려."}, headers=headers).json()
    assert body["source"] == "llm" and body["emotion"] == "EMPATHETIC"
    assert body["related_intervention_id"] is not None
    assert captured["url"].endswith("/internal/v1/llm/generate")
    assert captured["headers"]["X-Internal-Token"] == "internal-token-123456"
    sent = captured["json"]
    # No personal identifiers are forwarded.
    assert "user@example.com" not in str(sent) and set(sent["context"]) == {"sleep_state", "behavior_state", "risk_level", "risk_factors", "baseline_summary"}
    assert sent["intervention"]["category"] == "SLEEP_SCHEDULE"  # default while collecting

    second = client.post(f"{API}/chat/messages", json={"message": "고마워", "conversation_id": body["conversation_id"]}, headers=headers).json()
    assert second["conversation_id"] == body["conversation_id"]
    assert [turn["role"] for turn in captured["json"]["recent_history"]] == ["user", "assistant"]


def test_invalid_llm_payload_falls_back(client, db, settings, monkeypatch):
    seed_catalog(db)
    _llm_on(settings)
    _fake_llm(monkeypatch, body={"request_id": "x", "response": "hi", "intent": "X", "emotion": "ANGRY", "cat_action": "DANCE", "intervention_category": None, "safety_status": "PASS", "source": "llm"})
    _, headers = register_and_login(client)
    body = client.post(f"{API}/chat/messages", json={"message": "안녕"}, headers=headers).json()
    assert body["source"] == "fallback" and body["cat_action"] == "SIT"


def test_llm_outage_error_mode_and_crisis_guard(client, db, settings, monkeypatch):
    seed_catalog(db)
    _llm_on(settings)
    settings.llm_fail_mode = "error"

    def down(url, json, headers, timeout):
        raise httpx.ConnectError("down")

    monkeypatch.setattr(llm_client, "_http_post", down)
    _, headers = register_and_login(client)
    response = client.post(f"{API}/chat/messages", json={"message": "오늘 피곤해"}, headers=headers)
    assert response.status_code == 503 and response.json()["error"]["code"] == "LLM_UNAVAILABLE"
    crisis = client.post(f"{API}/chat/messages", json={"message": "죽고 싶어"}, headers=headers)
    assert crisis.status_code == 200
    assert crisis.json()["safety_status"] == "CRISIS" and "109" in crisis.json()["reply"]


def test_history_and_ownership(client, db):
    seed_catalog(db)
    _, headers = register_and_login(client, "a@example.com")
    _, other = register_and_login(client, "b@example.com")
    sent = client.post(f"{API}/chat/messages", json={"message": "안녕"}, headers=headers).json()
    history = client.get(f"{API}/chat/history", headers=headers).json()
    assert history["conversation_id"] == sent["conversation_id"]
    assert [m["role"] for m in history["messages"]] == ["user", "assistant"]
    assert history["messages"][1]["message_id"] == sent["message_id"]
    assert client.get(f"{API}/chat/history", params={"conversation_id": sent["conversation_id"]}, headers=other).status_code == 404
    hijack = client.post(f"{API}/chat/messages", json={"message": "hi", "conversation_id": sent["conversation_id"]}, headers=other)
    assert hijack.status_code == 404
    assert client.get(f"{API}/chat/history", headers=other).json() == {"conversation_id": None, "messages": []}


def test_chat_rate_limit(client, db, settings):
    seed_catalog(db)
    settings.chat_rate_limit_per_minute = 2
    _, headers = register_and_login(client)
    for _ in range(2):
        assert client.post(f"{API}/chat/messages", json={"message": "안녕"}, headers=headers).status_code == 200
    limited = client.post(f"{API}/chat/messages", json={"message": "안녕"}, headers=headers)
    assert limited.status_code == 429 and limited.json()["error"]["code"] == "RATE_LIMITED"


def test_chat_validation(client):
    _, headers = register_and_login(client)
    assert client.post(f"{API}/chat/messages", json={"message": ""}, headers=headers).status_code == 422
    assert client.post(f"{API}/chat/messages", json={"message": "x" * 1001}, headers=headers).status_code == 422
