"""FastAPI application factory."""

from __future__ import annotations

import logging

from fastapi import FastAPI
from sqlalchemy import text

from rafkhata import __version__
from rafkhata.api import auth, content, courses, deadlines, devices, files, lectures, me, search, spaces
from rafkhata.config import Settings, get_settings
from rafkhata.db import make_engine, make_sessionmaker
from rafkhata.security import make_google_verifier
from rafkhata.storage import make_storage

log = logging.getLogger(__name__)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    app = FastAPI(
        title="Raf-Khata API",
        version=__version__,
        description="Record Bangla/Banglish lectures, get transcripts, notes and study packs.",
    )
    engine = make_engine(settings.database_url)
    app.state.settings = settings
    app.state.engine = engine
    app.state.sessionmaker = make_sessionmaker(engine)
    app.state.storage = make_storage(settings)
    app.state.google_verifier = make_google_verifier(settings)

    if settings.dev_login_enabled:
        log.warning("DEV_LOGIN_ENABLED is on: anyone can sign in with just an email address")

    for router in (
        auth.router,
        me.router,
        spaces.router,
        courses.router,
        lectures.router,
        content.router,
        search.router,
        deadlines.router,
        devices.router,
        files.router,
    ):
        app.include_router(router)

    @app.get("/healthz", tags=["health"])
    def healthz() -> dict[str, object]:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        return {"ok": True, "version": __version__}

    return app
