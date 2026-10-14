import asyncio

import httpx

from app.core.config import get_settings
from app.services.llm_service import personalize

INTERVENTION = {"category": "SLEEP_ROUTINE", "title": "Routine", "message": "차분히 기록해 보세요."}
RISK = {"sleep_state": "NORMAL_SLEEP", "behavior_state": "NORMAL_BEHAVIOR", "risk_level": "STABLE"}


def test_llm_disabled_fallback():
    settings = get_settings(); original = settings.llm_enabled; settings.llm_enabled = False
    try: result = asyncio.run(personalize(INTERVENTION, RISK, "Nabi", "CHAT"))
    finally: settings.llm_enabled = original
    assert result["source"] == "fallback"


def test_llm_timeout_fallback(monkeypatch):
    async def timeout(*args, **kwargs): raise httpx.ReadTimeout("timeout")
    settings = get_settings(); old = (settings.llm_enabled, settings.llm_base_url)
    settings.llm_enabled = True; settings.llm_base_url = "https://llm.invalid/v1"
    monkeypatch.setattr(httpx.AsyncClient, "post", timeout)
    try: result = asyncio.run(personalize(INTERVENTION, RISK, "Nabi", "CHAT"))
    finally: settings.llm_enabled, settings.llm_base_url = old
    assert result["source"] == "fallback"
