from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", case_sensitive=False)

    app_env: str = "development"
    # Shared secret the backend sends as X-Internal-Token (NFR-03 internal auth).
    llm_internal_token: str = Field(min_length=16)
    # "mock" = deterministic template responder (no model needed, dev/test).
    # "openai_compatible" = any /v1/chat/completions server (vLLM, Ollama, TGI, external API).
    llm_provider: Literal["mock", "openai_compatible"] = "mock"
    llm_inference_url: str = ""
    llm_inference_api_key: str = ""
    llm_model: str = "Qwen/Qwen2.5-7B-Instruct"
    llm_temperature: float = 0.4
    llm_max_tokens: int = 300
    llm_json_mode: bool = True
    # Total budget per request including one repair retry (NFR-02 P95 10s).
    llm_inference_timeout_seconds: float = 8.0
    llm_max_retries: int = 1
    llm_history_turns: int = 6


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
