from conftest import API, register_and_login

ROUTE = [[36.6285, 127.4563], [36.6301, 127.4590], [36.6285, 127.4563]]
PATH = [[36.6285, 127.4563], [36.6290, 127.4571], [36.6301, 127.4590]]


def walk(**overrides) -> dict:
    body = {
        "started_at": "2026-11-20T18:00:00+09:00",
        "ended_at": "2026-11-20T18:31:30+09:00",
        "distance_m": 2140.5,
        "target_minutes": 30,
        "planned_distance_m": 2300,
        "planned_route": ROUTE,
        "path": PATH,
        "proposal_id": "overlay-walk-20261120",
    }
    body.update(overrides)
    return body


def test_create_list_and_detail(client):
    _, headers = register_and_login(client)
    created = client.post(f"{API}/walks", json=walk(), headers=headers)
    assert created.status_code == 201
    body = created.json()
    assert body["status"] == "created" and body["duration_sec"] == 1890 and body["path"] == PATH

    client.post(f"{API}/walks", json=walk(started_at="2026-11-21T07:00:00+09:00", ended_at="2026-11-21T07:20:00+09:00",
                                         distance_m=1500, path=[], planned_route=[]), headers=headers)
    listed = client.get(f"{API}/walks", headers=headers).json()
    assert listed["count"] == 2
    assert "path" not in listed["items"][0]  # list stays small
    assert listed["summary"] == {"walks": 2, "total_distance_m": 3640.5, "total_duration_sec": 3090}

    detail = client.get(f"{API}/walks/{body['walk_id']}", headers=headers).json()
    assert detail["planned_route"] == ROUTE and detail["proposal_id"] == "overlay-walk-20261120"


def test_date_filter_uses_user_timezone(client):
    _, headers = register_and_login(client)  # registered with the default timezone (Asia/Seoul)
    client.post(f"{API}/walks", json=walk(started_at="2026-11-20T23:30:00+09:00", ended_at="2026-11-20T23:50:00+09:00"), headers=headers)
    on_day = client.get(f"{API}/walks?start_date=2026-11-20&end_date=2026-11-20", headers=headers).json()
    next_day = client.get(f"{API}/walks?start_date=2026-11-21&end_date=2026-11-21", headers=headers).json()
    assert on_day["count"] == 1 and next_day["count"] == 0


def test_walks_are_private(client):
    _, owner = register_and_login(client, "owner@example.com")
    _, other = register_and_login(client, "other@example.com")
    walk_id = client.post(f"{API}/walks", json=walk(), headers=owner).json()["walk_id"]
    assert client.get(f"{API}/walks", headers=other).json()["count"] == 0
    assert client.get(f"{API}/walks/{walk_id}", headers=other).status_code == 404
    assert client.delete(f"{API}/walks/{walk_id}", headers=other).status_code == 404
    assert client.delete(f"{API}/walks/{walk_id}", headers=owner).status_code == 204
    assert client.get(f"{API}/walks/{walk_id}", headers=owner).status_code == 404


def test_validation(client):
    _, headers = register_and_login(client)
    cases = [
        walk(ended_at="2026-11-20T17:59:00+09:00"),           # ends before it starts
        walk(started_at="2026-11-20T18:00:00"),                # no timezone
        walk(ended_at="2026-11-21T01:00:01+09:00"),            # longer than 6 hours
        walk(path=[[91.0, 127.0]]),                            # latitude out of range
        walk(path=[[36.6, 127.4, 3.0]]),                       # not a [lat, lon] pair
        walk(distance_m=-1),
        walk(unknown_field=1),
    ]
    for body in cases:
        response = client.post(f"{API}/walks", json=body, headers=headers)
        assert response.status_code == 422, body
        assert response.json()["error"]["code"] == "VALIDATION_ERROR"
    assert client.get(f"{API}/walks?start_date=2026-11-21&end_date=2026-11-20", headers=headers).status_code == 422
    assert client.get(f"{API}/walks").status_code == 401
