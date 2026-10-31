import httpx
from conftest import TOKEN, generate_body


def test_health_without_token(client):
    body = client.get("/internal/v1/llm/health").json()
    assert body["status"] == "ok" and body["provider"] == "mock"


def test_internal_token_required(client):
    assert client.post("/internal/v1/llm/generate", json=generate_body()).status_code == 401
    bad = {"X-Internal-Token": "wrong-token-wrong-token"}
    assert client.post("/internal/v1/llm/generate", json=generate_body(), headers=bad).status_code == 401


def test_generate_matches_spec_contract(client):
    response = client.post("/internal/v1/llm/generate", json=generate_body(), headers=TOKEN)
    assert response.status_code == 200
    body = response.json()
    assert body["request_id"] == "req_1001"
    assert body["intent"] == "SLEEP_COMPLAINT"
    assert body["emotion"] == "EMPATHETIC"
    assert body["cat_action"] == "SIT"
    assert body["intervention_category"] == "RELAXATION"
    assert body["safety_status"] == "PASS"
    assert body["response"]


def test_generate_without_intervention(client):
    body = client.post("/internal/v1/llm/generate", json=generate_body("안녕", None), headers=TOKEN).json()
    assert body["intervention_category"] is None
    assert body["intent"] == "GREETING"


def test_crisis_input_skips_model(client, settings):
    settings.llm_provider = "openai_compatible"
    settings.llm_inference_url = "http://never-called.invalid/v1"
    body = client.post("/internal/v1/llm/generate", json=generate_body("요즘 너무 힘들어서 죽고 싶어"), headers=TOKEN).json()
    assert body["safety_status"] == "CRISIS"
    assert body["intent"] == "CRISIS"
    assert "109" in body["response"]
    assert body["source"] == "safety"


def test_invalid_enum_in_request_is_422(client):
    body = generate_body()
    body["context"]["risk_level"] = "EXTREME"
    response = client.post("/internal/v1/llm/generate", json=body, headers=TOKEN)
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


def test_unknown_fields_rejected(client):
    body = generate_body()
    body["context"]["email"] = "user@example.com"  # personal data must not be forwarded
    assert client.post("/internal/v1/llm/generate", json=body, headers=TOKEN).status_code == 422


def test_intervention_endpoint(client):
    body = {"request_id": "req_2", "intervention": {"category": "STIMULUS_CONTROL", "instruction": "잠이 오지 않으면 침대에서 나오기", "reason_codes": ["PROLONGED_SLEEP_ONSET"]}}
    result = client.post("/internal/v1/llm/intervention", json=body, headers=TOKEN).json()
    assert result["intervention_category"] == "STIMULUS_CONTROL"
    assert result["guidance"]


def test_mission_complete_is_happy(client):
    body = {"request_id": "req_3", "phase": "COMPLETE", "mission": {"title": "카페인 줄이기", "description": "오후 2시 이후 카페인 피하기"}, "reward_exp": 30}
    result = client.post("/internal/v1/llm/mission", json=body, headers=TOKEN).json()
    assert result["cat_action"] == "HAPPY"
    assert "30" in result["message"]


def test_safety_check_endpoint(client):
    r = client.post("/internal/v1/llm/safety-check", json={"request_id": "r", "text": "수면제 10mg 드세요", "direction": "OUTPUT"}, headers=TOKEN).json()
    assert r["safety_status"] == "MODIFIED" and not r["allowed"]
    r = client.post("/internal/v1/llm/safety-check", json={"request_id": "r", "text": "수면제 먹어도 돼?", "direction": "INPUT"}, headers=TOKEN).json()
    assert r["safety_status"] == "PASS" and "MEDICAL_QUESTION" in r["categories"]


def _fake_completion(contents: list[str]):
    calls = {"n": 0}

    async def post(self, url, json=None, headers=None, timeout=None):
        content = contents[min(calls["n"], len(contents) - 1)]
        calls["n"] += 1
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]}, request=httpx.Request("POST", url))

    return post, calls


def _use_remote(settings):
    settings.llm_provider = "openai_compatible"
    settings.llm_inference_url = "http://vllm.test/v1"


def test_remote_valid_output(client, settings, monkeypatch):
    _use_remote(settings)
    good = '{"response": "어제도 힘들었구나. 오늘은 자기 전에 몸의 긴장을 풀어 보자.", "intent": "SLEEP_COMPLAINT", "emotion": "EMPATHETIC", "cat_action": "SIT", "intervention_category": "RELAXATION"}'
    post, calls = _fake_completion([f"```json\n{good}\n```"])
    monkeypatch.setattr(httpx.AsyncClient, "post", post)
    body = client.post("/internal/v1/llm/generate", json=generate_body(), headers=TOKEN).json()
    assert body["source"] == "llm" and calls["n"] == 1
    assert body["response"].startswith("어제도")


def test_remote_invalid_then_repaired(client, settings, monkeypatch):
    _use_remote(settings)
    bad = '{"response": "음", "intent": "SLEEP_COMPLAINT", "emotion": "ANGRY", "cat_action": "DANCE", "intervention_category": "RELAXATION"}'
    good = '{"response": "오늘은 천천히 숨을 쉬어 보자.", "intent": "SLEEP_COMPLAINT", "emotion": "CALM", "cat_action": "COMFORT", "intervention_category": "RELAXATION"}'
    post, calls = _fake_completion([bad, good])
    monkeypatch.setattr(httpx.AsyncClient, "post", post)
    body = client.post("/internal/v1/llm/generate", json=generate_body(), headers=TOKEN).json()
    assert calls["n"] == 2 and body["source"] == "llm" and body["cat_action"] == "COMFORT"


def test_remote_category_switch_falls_back(client, settings, monkeypatch):
    _use_remote(settings)
    switched = '{"response": "수면 일정을 바꿔 보자.", "intent": "SLEEP_COMPLAINT", "emotion": "CALM", "cat_action": "SIT", "intervention_category": "SLEEP_SCHEDULE"}'
    post, _ = _fake_completion([switched])
    monkeypatch.setattr(httpx.AsyncClient, "post", post)
    body = client.post("/internal/v1/llm/generate", json=generate_body(), headers=TOKEN).json()
    assert body["source"] == "fallback"
    assert body["intervention_category"] == "RELAXATION"


def test_remote_medical_advice_is_replaced(client, settings, monkeypatch):
    _use_remote(settings)
    unsafe = '{"response": "멜라토닌 3mg을 복용해 보세요.", "intent": "MEDICAL_QUESTION", "emotion": "CALM", "cat_action": "SIT", "intervention_category": "RELAXATION"}'
    post, _ = _fake_completion([unsafe])
    monkeypatch.setattr(httpx.AsyncClient, "post", post)
    body = client.post("/internal/v1/llm/generate", json=generate_body("멜라토닌 먹어도 돼?"), headers=TOKEN).json()
    assert body["safety_status"] == "MODIFIED" and body["source"] == "fallback"
    assert "멜라토닌" not in body["response"]
    assert body["intent"] == "MEDICAL_QUESTION"


def test_remote_timeout_falls_back(client, settings, monkeypatch):
    _use_remote(settings)

    async def timeout(self, *args, **kwargs):
        raise httpx.ReadTimeout("timeout")

    monkeypatch.setattr(httpx.AsyncClient, "post", timeout)
    body = client.post("/internal/v1/llm/generate", json=generate_body(), headers=TOKEN).json()
    assert body["source"] == "fallback" and body["safety_status"] == "PASS"


def test_remote_http_error_falls_back(client, settings, monkeypatch):
    _use_remote(settings)

    async def error(self, url, **kwargs):
        return httpx.Response(500, request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx.AsyncClient, "post", error)
    assert client.post("/internal/v1/llm/generate", json=generate_body(), headers=TOKEN).json()["source"] == "fallback"
