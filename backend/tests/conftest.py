"""Test fixtures.

By default tests use SQLite and local-disk storage in a temp directory. Set
RAFKHATA_TEST_DATABASE_URL to a PostgreSQL URL to run the same tests against Postgres; the schema
is then built with the Alembic migrations, which also checks them.
"""

from __future__ import annotations

import os
import uuid
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import text
from sqlalchemy.orm import Session

from rafkhata.api.app import create_app
from rafkhata.config import Settings
from rafkhata.db import Base
from rafkhata.security import AuthError, GoogleIdentity

TEST_PG_URL = os.environ.get("RAFKHATA_TEST_DATABASE_URL")
BACKEND_DIR = Path(__file__).resolve().parents[1]


def make_settings(tmp_path: Path, **overrides) -> Settings:
    values = dict(
        env="test",
        database_url=TEST_PG_URL or f"sqlite:///{tmp_path / 'test.db'}",
        public_base_url="http://testserver",
        jwt_secret="test-secret-" + "x" * 32,
        dev_login_enabled=True,
        google_client_ids=["test-web-client"],
        storage_backend="local",
        storage_local_dir=str(tmp_path / "storage"),
        work_dir=str(tmp_path / "work"),
        asr_primary="mock",
        asr_fallback="none",
        llm_provider="fake",
        fcm_project_id=None,
    )
    values.update(overrides)
    return Settings(_env_file=None, **values)


def _reset_schema(app: FastAPI) -> None:
    engine = app.state.engine
    if engine.dialect.name == "postgresql":
        from alembic import command
        from alembic.config import Config

        with engine.begin() as conn:
            conn.execute(text("DROP SCHEMA public CASCADE"))
            conn.execute(text("CREATE SCHEMA public"))
        with engine.begin() as conn:
            config = Config(str(BACKEND_DIR / "alembic.ini"))
            config.attributes["connection"] = conn
            command.upgrade(config, "head")
    else:
        Base.metadata.create_all(engine)


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return make_settings(tmp_path)


@pytest.fixture
def app(settings: Settings) -> Iterator[FastAPI]:
    app = create_app(settings)
    _reset_schema(app)

    def fake_google(token: str) -> GoogleIdentity:
        # Tokens look like "google:<sub>:<email>" in tests.
        try:
            _, sub, email = token.split(":", 2)
        except ValueError as exc:
            raise AuthError("bad test token") from exc
        return GoogleIdentity(sub=sub, email=email, name=email.split("@")[0], picture=None)

    app.state.google_verifier = fake_google
    yield app
    app.state.engine.dispose()


@pytest.fixture
def client(app: FastAPI) -> Iterator[TestClient]:
    with TestClient(app) as c:
        yield c


@pytest.fixture
def db(app: FastAPI) -> Iterator[Session]:
    session = app.state.sessionmaker()
    try:
        yield session
    finally:
        session.close()


def login(client: TestClient, email: str = "rahim@example.com", name: str = "Rahim") -> dict[str, str]:
    resp = client.post("/auth/dev-login", json={"email": email, "name": name})
    assert resp.status_code == 200, resp.text
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


@pytest.fixture
def alice(client: TestClient) -> dict[str, str]:
    return login(client, "alice@example.com", "Alice")


@pytest.fixture
def bob(client: TestClient) -> dict[str, str]:
    return login(client, "bob@example.com", "Bob")


def new_id() -> str:
    return str(uuid.uuid4())
