"""Access tokens (JWT), refresh tokens and Google ID-token verification."""

from __future__ import annotations

import hashlib
import secrets
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import jwt

from rafkhata.config import Settings

ALGORITHM = "HS256"


class AuthError(Exception):
    """Raised when a credential is missing, invalid or expired."""


def create_access_token(settings: Settings, user_id: uuid.UUID) -> tuple[str, int]:
    now = datetime.now(UTC)
    payload = {
        "sub": str(user_id),
        "typ": "access",
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(seconds=settings.access_token_ttl_s)).timestamp()),
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=ALGORITHM), settings.access_token_ttl_s


def decode_access_token(settings: Settings, token: str) -> uuid.UUID:
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=[ALGORITHM], options={"require": ["exp", "sub"]})
    except jwt.PyJWTError as exc:
        raise AuthError("invalid or expired access token") from exc
    if payload.get("typ") != "access":
        raise AuthError("not an access token")
    try:
        return uuid.UUID(payload["sub"])
    except ValueError as exc:
        raise AuthError("invalid subject") from exc


def hash_token(raw: str) -> str:
    return hashlib.sha256(raw.encode()).hexdigest()


def new_refresh_token() -> tuple[str, str]:
    """Return (raw token for the client, hash to store)."""
    raw = secrets.token_urlsafe(48)
    return raw, hash_token(raw)


@dataclass(frozen=True)
class GoogleIdentity:
    sub: str
    email: str
    name: str
    picture: str | None


GoogleVerifier = Callable[[str], GoogleIdentity]


def make_google_verifier(settings: Settings) -> GoogleVerifier:
    """Verify a Google ID token from Android Credential Manager against our Web client IDs."""

    def verify(token: str) -> GoogleIdentity:
        if not settings.google_client_ids:
            raise AuthError("Google sign-in is not configured (GOOGLE_CLIENT_IDS)")
        from google.auth.transport import requests as google_requests
        from google.oauth2 import id_token

        try:
            info = id_token.verify_oauth2_token(
                token, google_requests.Request(), audience=settings.google_client_ids, clock_skew_in_seconds=10
            )
        except ValueError as exc:
            raise AuthError(f"invalid Google ID token: {exc}") from exc
        if not info.get("email") or not info.get("email_verified"):
            raise AuthError("Google account email is not verified")
        return GoogleIdentity(
            sub=str(info["sub"]),
            email=str(info["email"]).lower(),
            name=str(info.get("name") or ""),
            picture=info.get("picture"),
        )

    return verify
