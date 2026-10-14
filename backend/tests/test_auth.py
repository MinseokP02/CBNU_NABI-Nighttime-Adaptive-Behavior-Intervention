from conftest import register_and_login


def test_register_login_and_jwt(client):
    user, headers = register_and_login(client)
    response = client.get("/api/auth/me", headers=headers)
    assert response.status_code == 200
    assert response.json()["user_id"] == user["user_id"]
    assert "password_hash" not in response.json()


def test_bad_login_is_401(client):
    register_and_login(client)
    assert client.post("/api/auth/login", json={"email": "user@example.com", "password": "wrong"}).status_code == 401
