"""Application settings, read from environment variables (and `.env` in development)."""

from __future__ import annotations

from functools import lru_cache
from typing import Annotated, Literal

from pydantic import field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

INSECURE_DEV_SECRET = "dev-insecure-change-me"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    env: Literal["dev", "test", "prod"] = "dev"
    database_url: str = "postgresql+psycopg://rafkhata:rafkhata@localhost:5432/rafkhata"
    # Base URL clients use to reach this API; used for locally signed file URLs.
    public_base_url: str = "http://localhost:8000"

    # --- Auth ---
    jwt_secret: str = INSECURE_DEV_SECRET
    access_token_ttl_s: int = 3600
    refresh_token_ttl_days: int = 60
    # Google OAuth *Web* client IDs accepted as the ID-token audience (comma-separated in env).
    google_client_ids: Annotated[list[str], NoDecode] = []
    dev_login_enabled: bool = False

    # --- Storage ---
    storage_backend: Literal["local", "s3"] = "local"
    storage_local_dir: str = "./data/storage"
    s3_bucket: str = ""
    s3_endpoint_url: str | None = None
    s3_region: str = "auto"
    s3_access_key_id: str | None = None
    s3_secret_access_key: str | None = None
    upload_url_ttl_s: int = 3600
    download_url_ttl_s: int = 3600
    max_segment_bytes: int = 50 * 1024 * 1024
    max_photo_bytes: int = 15 * 1024 * 1024
    max_segments_per_lecture: int = 120  # 10 hours of 5-minute segments

    # --- Speech recognition ---
    asr_primary: Literal["sarvam", "soniox", "mock"] = "mock"
    asr_fallback: Literal["sarvam", "soniox", "mock", "none"] = "none"
    sarvam_api_key: str | None = None
    sarvam_model: str = "saaras:v3"
    sarvam_mode: str = "codemix"
    sarvam_language: str = "bn-IN"
    soniox_api_key: str | None = None
    soniox_model: str = "stt-async-v5"
    asr_timeout_s: int = 3 * 3600

    # --- LLM (notes) ---
    llm_provider: Literal["claude", "fake"] = "fake"
    anthropic_api_key: str | None = None
    llm_model: str = "claude-opus-5-5"
    llm_effort_correct: Literal["low", "medium", "high", "xhigh", "max"] = "low"
    llm_effort_notes: Literal["low", "medium", "high", "xhigh", "max"] = "medium"
    llm_effort_study: Literal["low", "medium", "high", "xhigh", "max"] = "low"
    llm_fallbacks: bool = True
    llm_max_tokens: int = 32000

    # --- Pipeline ---
    vad_trim: bool = True
    vad_trim_min_gap_s: float = 2.0
    raw_audio_retention_days: int = 30
    max_lecture_minutes: int = 300
    default_timezone: str = "Asia/Dhaka"
    work_dir: str = "./data/work"

    # --- Push notifications (FCM HTTP v1) ---
    fcm_project_id: str | None = None
    fcm_service_account_file: str | None = None

    # --- Worker ---
    worker_poll_interval_s: float = 2.0
    job_max_attempts: int = 4
    job_lease_s: int = 3 * 3600

    @field_validator("google_client_ids", mode="before")
    @classmethod
    def _split_client_ids(cls, value: object) -> object:
        if isinstance(value, str):
            return [part.strip() for part in value.split(",") if part.strip()]
        return value

    @model_validator(mode="after")
    def _check_production(self) -> Settings:
        if self.env == "prod":
            if self.jwt_secret == INSECURE_DEV_SECRET or len(self.jwt_secret) < 32:
                raise ValueError("JWT_SECRET must be set to a random value of at least 32 characters in prod")
            if self.dev_login_enabled:
                raise ValueError("DEV_LOGIN_ENABLED must be false in prod")
            if not self.google_client_ids:
                raise ValueError("GOOGLE_CLIENT_IDS must be set in prod")
        return self

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")


@lru_cache
def get_settings() -> Settings:
    return Settings()
