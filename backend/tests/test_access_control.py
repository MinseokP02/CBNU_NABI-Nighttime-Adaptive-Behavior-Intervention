from conftest import register_and_login


def test_cross_user_state_denied(client):
    _, headers = register_and_login(client, "one@example.com")
    other, _ = register_and_login(client, "two@example.com")
    response = client.get(f"/api/users/{other['user_id']}/state", headers=headers)
    assert response.status_code == 403


def test_private_endpoint_requires_token(client):
    assert client.get("/api/cat/profile").status_code == 401
