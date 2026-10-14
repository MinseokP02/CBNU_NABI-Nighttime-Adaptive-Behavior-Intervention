def test_health_and_ready(client):
    assert client.get("/health").json() == {"status": "ok"}
    assert client.get("/ready").json() == {"status": "ready"}


def test_openapi_contains_core_routes(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert "/api/auth/register" in paths
    assert "/api/analysis/risk" in paths
    assert "/api/missions/{assignment_id}/complete" in paths
