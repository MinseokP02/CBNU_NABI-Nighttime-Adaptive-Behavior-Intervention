from conftest import register_and_login

PAYLOAD = {"sleep_date": "2026-10-14", "bedtime": "2026-10-13T23:00:00+09:00", "wake_time": "2026-10-14T07:00:00+09:00", "sol": 20, "waso": 10, "tst": 450, "se": 93.75, "sleep_quality": 4, "source": "android"}


def test_create_and_duplicate_sleep(client):
    _, headers = register_and_login(client)
    assert client.post("/api/sleep-records", json=PAYLOAD, headers=headers).status_code == 201
    assert client.post("/api/sleep-records", json=PAYLOAD, headers=headers).status_code == 409


def test_sleep_math_validation(client):
    _, headers = register_and_login(client)
    bad = {**PAYLOAD, "tst": 600, "se": 50}
    assert client.post("/api/sleep-records", json=bad, headers=headers).status_code == 422
