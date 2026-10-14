from functools import lru_cache

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
    llm_enabled: bool = False
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = "Qwen2.5-7B-Instruct"
    llm_timeout_seconds: float = 5.0
    baseline_min_days: int = 14
    recent_window_days: int = 7
    robust_z_warning_threshold: float = 2.0
    robust_z_high_threshold: float = 3.0

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
