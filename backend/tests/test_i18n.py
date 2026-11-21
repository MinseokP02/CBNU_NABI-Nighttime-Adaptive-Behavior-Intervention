"""User-facing server text follows the app language (Accept-Language)."""
import httpx
import pytest
from conftest import API, register_and_login
from test_chat import _fake_llm, _llm_on

from app.core.i18n import (
    INTERVENTIONS_EN,
    MISSIONS_EN,
    language,
    localize_mission,
    localize_recommendation,
)
from app.seed.seed_interventions import CATALOG, seed_catalog
from app.services import llm_client

EN = {"Accept-Language": "en-US,en;q=0.9"}


def _hangul(s: str) -> bool:
    return any("가" <= ch <= "힣" for ch in s)


@pytest.mark.parametrize(
    ("header", "lang"),
    [(None, "ko"), ("ko-KR", "ko"), ("en-US,en;q=0.9", "en"), ("EN", "en"), ("fr-FR,en;q=0.5", "ko")],
)
def test_language_header(header, lang):
    assert language(header) == lang


def test_every_catalog_entry_has_english():
    for code, _category, _title, _instruction, _message, _prohibited, missions in CATALOG:
        assert code in INTERVENTIONS_EN, code
        assert not any(_hangul(s) for s in INTERVENTIONS_EN[code])
        for mission in missions:
            assert mission[0] in MISSIONS_EN, mission[0]
            assert not any(_hangul(s) for s in MISSIONS_EN[mission[0]])


def test_localize_helpers_leave_korean_untouched():
    rec = {"code": "RX_BREATHING", "intervention_id": 5, "title": "자기 전 이완 호흡", "message": "m"}
    assert localize_recommendation(rec, "ko") is rec
    assert localize_recommendation(rec, "en")["title"] == "Relaxing breaths before bed"
    assert localize_recommendation({"code": "X", "intervention_id": None, "title": "t", "message": "m"}, "en")["title"] == "Today's sleep habit"
    assert localize_mission({"title": "unknown", "description": "d"}, "en") == {"title": "unknown", "description": "d"}


def test_recommendation_and_missions_in_english(client, db):
    seed_catalog(db)
    _, headers = register_and_login(client)
    ko = client.get(f"{API}/interventions/recommendations", headers=headers).json()
    en = client.get(f"{API}/interventions/recommendations", headers=headers | EN).json()
    assert ko["code"] == en["code"] and _hangul(ko["title"]) and not _hangul(en["title"]) and not _hangul(en["message"])
    missions = client.get(f"{API}/missions/active", headers=headers | EN).json()
    for m in missions:
        assert not _hangul(m["title"]) and not _hangul(m["description"])


def test_chat_fallback_and_crisis_in_english(client, db, settings, monkeypatch):
    seed_catalog(db)
    _, headers = register_and_login(client)
    reply = client.post(f"{API}/chat/messages", json={"message": "I couldn't sleep"}, headers=headers | EN).json()
    assert reply["source"] == "fallback" and reply["reply"].startswith("Thanks for telling me.") and not _hangul(reply["reply"])

    _llm_on(settings)
    settings.llm_fail_mode = "error"

    def down(url, json, headers, timeout):
        raise httpx.ConnectError("down")

    monkeypatch.setattr(llm_client, "_http_post", down)
    crisis = client.post(f"{API}/chat/messages", json={"message": "I want to kill myself"}, headers=headers | EN)
    assert crisis.status_code == 200 and crisis.json()["safety_status"] == "CRISIS"
    assert "109" in crisis.json()["reply"] and not _hangul(crisis.json()["reply"])


def test_language_is_forwarded_to_llm_server(client, db, settings, monkeypatch):
    seed_catalog(db)
    _llm_on(settings)
    captured: dict = {}
    _fake_llm(monkeypatch, capture=captured)
    _, headers = register_and_login(client)
    client.post(f"{API}/chat/messages", json={"message": "hello"}, headers=headers | EN)
    assert captured["json"]["language"] == "en"
    client.post(f"{API}/chat/messages", json={"message": "안녕"}, headers=headers)
    assert captured["json"]["language"] == "ko"
