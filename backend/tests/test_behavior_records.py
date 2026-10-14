from conftest import register_and_login


def test_behavior_creation_and_duplicate(client):
    _, headers = register_and_login(client)
    payload = {"record_date": "2026-10-14", "screen_time": 200, "nighttime_screen_time": 30, "steps": 6000}
    assert client.post("/api/behavior-records", json=payload, headers=headers).status_code == 201
    assert client.post("/api/behavior-records", json=payload, headers=headers).status_code == 409


def test_behavior_validation(client):
    _, headers = register_and_login(client)
    payload = {"record_date": "2026-10-14", "screen_time": 10, "nighttime_screen_time": 30, "steps": -1}
    assert client.post("/api/behavior-records", json=payload, headers=headers).status_code == 422
