from conftest import API, register_and_login

SPEC_PATHS = [
    "/auth/register", "/auth/login", "/users/me", "/users/me/state", "/sleep-records", "/behavior-records",
    "/analysis/baseline", "/analysis/risk", "/analysis/refresh", "/interventions/recommendations",
    "/missions/active", "/missions/{mission_id}/complete", "/chat/messages", "/chat/history",
    "/cat/state", "/reports/weekly", "/health",
]


def test_openapi_contains_all_spec_endpoints(client):
    paths = client.get("/openapi.json").json()["paths"]
    for path in SPEC_PATHS:
        assert f"{API}{path}" in paths, path


def test_health(client):
    assert client.get("/health").json() == {"status": "ok"}
    assert client.get("/ready").json() == {"status": "ready"}
    body = client.get(f"{API}/health").json()
    assert body["status"] == "ok" and body["database"] == "ok" and body["llm"] == "disabled"


def test_register_login_me(client):
    user, headers = register_and_login(client)
    response = client.get(f"{API}/users/me", headers=headers)
    assert response.status_code == 200
    assert response.json()["user_id"] == user["user_id"]
    assert "password_hash" not in response.json()
    assert response.headers["X-Request-ID"]


def test_error_codes(client):
    register_and_login(client)
    dup = client.post(f"{API}/auth/register", json={"email": "user@example.com", "password": "correct-horse-battery", "isi_score": 10})
    assert dup.status_code == 409 and dup.json()["error"]["code"] == "DUPLICATE_RECORD"
    bad = client.post(f"{API}/auth/login", json={"email": "user@example.com", "password": "wrong"})
    assert bad.status_code == 401 and bad.json()["error"]["code"] == "UNAUTHORIZED"
    missing = client.get(f"{API}/users/me")
    assert missing.status_code == 401 and missing.json()["error"]["code"] == "UNAUTHORIZED"
    expired = client.get(f"{API}/users/me", headers={"Authorization": "Bearer not-a-token"})
    assert expired.status_code == 401
    malformed = client.post(f"{API}/auth/login", content=b"{not json", headers={"Content-Type": "application/json"})
    assert malformed.status_code == 400 and malformed.json()["error"]["code"] == "INVALID_REQUEST"
    unknown = client.get(f"{API}/does-not-exist")
    assert unknown.status_code == 404 and unknown.json()["error"]["code"] == "RESOURCE_NOT_FOUND"


def test_user_state_for_new_user(client):
    _, headers = register_and_login(client)
    body = client.get(f"{API}/users/me/state", headers=headers).json()
    assert body["baseline_status"] == "collecting"
    assert body["risk_level"] == "UNKNOWN"
    assert body["cat"]["name"] == "Nabi"
