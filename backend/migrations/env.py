"""Alembic environment: uses DATABASE_URL from rafkhata settings."""

from __future__ import annotations

from alembic import context

from rafkhata import models  # noqa: F401  (registers tables on Base.metadata)
from rafkhata.config import get_settings
from rafkhata.db import Base, UTCDateTime, make_engine

target_metadata = Base.metadata


def render_item(type_, obj, autogen_context):
    """Render our UTCDateTime as a plain timezone-aware DateTime in migrations."""
    if type_ == "type" and isinstance(obj, UTCDateTime):
        return "sa.DateTime(timezone=True)"
    return False


def run_migrations_offline() -> None:
    context.configure(
        url=get_settings().database_url,
        target_metadata=target_metadata,
        literal_binds=True,
        render_item=render_item,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = context.config.attributes.get("connection")
    if connectable is None:
        engine = make_engine(get_settings().database_url)
        with engine.connect() as connection:
            _run(connection)
        engine.dispose()
    else:
        _run(connectable)


def _run(connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata, render_item=render_item)
    with context.begin_transaction():
        context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
