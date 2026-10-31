from conftest import API, register_and_login

SPEC_SLEEP = {
    "sleep_date": "2026-10-30",
    "bedtime": "2026-10-30T23:30:00+09:00",
    "sleep_onset_latency_min": 35,
    "wake_after_sleep_onset_min": 25,
    "final_awakening": "2026-10-31T06:50:00+09:00",
    "out_of_bed_time": "2026-10-31T07:10:00+09:00",
    "sleep_quality": 3,
}


def test_sleep_record_spec_example(client):
    _, headers = register_and_login(client)
    response = client.post(f"{API}/sleep-records", json=SPEC_SLEEP, headers=headers)
    assert response.status_code == 201
    body = response.json()
    # TIB = 23:30 -> 07:10 = 460; TST = (23:30 -> 06:50 = 440) - 35 - 25 = 380; SE = 380/460
    assert body["time_in_bed_min"] == 460
    assert body["total_sleep_time_min"] == 380
    assert body["sleep_efficiency"] == 0.8261
    assert body["status"] == "created"


def test_sleep_duplicate_is_409(client):
    _, headers = register_and_login(client)
    client.post(f"{API}/sleep-records", json=SPEC_SLEEP, headers=headers)
    response = client.post(f"{API}/sleep-records", json=SPEC_SLEEP, headers=headers)
    assert response.status_code == 409 and response.json()["error"]["code"] == "DUPLICATE_RECORD"


def test_sleep_validation_422(client):
    _, headers = register_and_login(client)
    cases = [
        {**SPEC_SLEEP, "final_awakening": "2026-10-30T23:00:00+09:00"},        # wake before bedtime
        {**SPEC_SLEEP, "out_of_bed_time": "2026-10-31T06:00:00+09:00"},        # out of bed before wake
        {**SPEC_SLEEP, "sleep_onset_latency_min": -5},                          # negative time
        {**SPEC_SLEEP, "sleep_onset_latency_min": 300, "wake_after_sleep_onset_min": 200},
        {**SPEC_SLEEP, "bedtime": "2026-10-30T23:30:00"},                       # no offset
        {**SPEC_SLEEP, "sleep_date": "2026-10-31"},                             # not the night date
        {**SPEC_SLEEP, "sleep_quality": 6},
        {**SPEC_SLEEP, "sleep_date": "2099-01-01", "bedtime": "2099-01-01T23:00:00+09:00", "final_awakening": "2099-01-02T07:00:00+09:00", "out_of_bed_time": "2099-01-02T07:10:00+09:00"},
    ]
    for case in cases:
        response = client.post(f"{API}/sleep-records", json=case, headers=headers)
        assert response.status_code == 422, case
        assert response.json()["error"]["code"] == "VALIDATION_ERROR"


def test_after_midnight_bedtime_belongs_to_previous_night(client):
    _, headers = register_and_login(client)
    payload = {**SPEC_SLEEP, "bedtime": "2026-10-31T01:10:00+09:00"}
    assert client.post(f"{API}/sleep-records", json=payload, headers=headers).status_code == 201


def test_sleep_list_and_isolation(client):
    _, headers = register_and_login(client, "a@example.com")
    _, other = register_and_login(client, "b@example.com")
    client.post(f"{API}/sleep-records", json=SPEC_SLEEP, headers=headers)
    body = client.get(f"{API}/sleep-records", params={"start_date": "2026-10-01", "end_date": "2026-10-31"}, headers=headers).json()
    assert body["count"] == 1 and body["items"][0]["total_sleep_time_min"] == 380
    assert client.get(f"{API}/sleep-records", headers=other).json()["count"] == 0
    assert client.get(f"{API}/sleep-records", params={"start_date": "2026-10-31", "end_date": "2026-10-01"}, headers=headers).status_code == 422


def test_behavior_record(client):
    _, headers = register_and_login(client)
    payload = {
        "record_date": "2026-10-30", "caffeine_after_cutoff": True, "screen_before_bed_min": 90,
        "nap_duration_min": 0, "exercise_duration_min": 30, "stress_level": 4, "relaxation_completed": False,
    }
    response = client.post(f"{API}/behavior-records", json=payload, headers=headers)
    assert response.status_code == 201
    body = response.json()
    assert body["record_id"] and body["status"] == "created" and body["stress_level"] == 4
    assert client.post(f"{API}/behavior-records", json=payload, headers=headers).status_code == 409
    listed = client.get(f"{API}/behavior-records", headers=headers).json()
    assert listed["count"] == 1 and listed["items"][0]["caffeine_after_cutoff"] is True


def test_behavior_validation(client):
    _, headers = register_and_login(client)
    for case in [
        {"record_date": "2026-10-30"},                                  # nothing recorded
        {"record_date": "2026-10-30", "stress_level": 6},
        {"record_date": "2026-10-30", "screen_before_bed_min": -1},
        {"record_date": "2026-10-30", "steps": 1000},                   # legacy field
    ]:
        assert client.post(f"{API}/behavior-records", json=case, headers=headers).status_code == 422, case
