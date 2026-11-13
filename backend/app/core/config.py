from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import URL


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", case_sensitive=False)

    app_env: str = "development"
    debug: bool = False
    postgres_db: str = "nabi_db"
    postgres_user: str = "nabi_user"
    postgres_password: str = Field(min_length=1)
    db_host: str = "db"
    db_port: int = 5432
    database_url: str | None = None
    jwt_secret_key: str = Field(min_length=16)
    jwt_algorithm: str = "HS256"
    access_token_expire_minutes: int = 60
    jwt_issuer: str = "nabi-api"
    jwt_audience: str = "nabi-android"
    cors_origins: str = "http://localhost:3000"

    # LLM server (internal API). Disabled => deterministic fallback replies.
    llm_enabled: bool = False
    llm_server_url: str = "http://llm-server:8100"
    llm_internal_token: str = ""
    llm_timeout_seconds: float = 12.0
    # "fallback": answer with a safe fixed sentence (NFR-06). "error": return 503 LLM_UNAVAILABLE.
    llm_fail_mode: Literal["fallback", "error"] = "fallback"
    llm_history_messages: int = 6
    chat_rate_limit_per_minute: int = 20

    # Analysis engine (spec §4.3/4.4). Thresholds are MVP change-detection settings, not diagnosis.
    analysis_version: str = "1.0"
    baseline_window_days: int = 14
    baseline_min_valid_days: int = 10
    valid_min_time_in_bed_min: int = 180
    valid_max_time_in_bed_min: int = 960
    robust_z_warning_threshold: float = 2.0
    robust_z_high_threshold: float = 3.0
    irregular_schedule_range_min: int = 120

    @property
    def sqlalchemy_url(self) -> str | URL:
        if self.database_url:
            return self.database_url
        return URL.create(
            "postgresql+psycopg",
            username=self.postgres_user,
            password=self.postgres_password,
            host=self.db_host,
            port=self.db_port,
            database=self.postgres_db,
        )

    @property
    def parsed_cors_origins(self) -> list[str]:
        origins = [value.strip() for value in self.cors_origins.split(",") if value.strip()]
        if "*" in origins:
            raise ValueError("Wildcard CORS origins are not allowed with credentials")
        return origins


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
