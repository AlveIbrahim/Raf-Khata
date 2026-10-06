"""Sign-in: Google ID token -> our access + refresh tokens. Refresh tokens rotate on every use."""

from __future__ import annotations

from datetime import timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from rafkhata.api.deps import DbDep, SettingsDep, get_google_verifier
from rafkhata.api.schemas import DevLoginIn, GoogleLoginIn, RefreshIn, TokenOut, UserOut
from rafkhata.config import Settings
from rafkhata.db import utcnow
from rafkhata.models import RefreshToken, User
from rafkhata.security import AuthError, GoogleVerifier, create_access_token, hash_token, new_refresh_token

router = APIRouter(prefix="/auth", tags=["auth"])


def issue_tokens(db: Session, settings: Settings, user: User, user_agent: str | None) -> TokenOut:
    access, expires_in = create_access_token(settings, user.id)
    raw, token_hash = new_refresh_token()
    db.add(
        RefreshToken(
            user_id=user.id,
            token_hash=token_hash,
            expires_at=utcnow() + timedelta(days=settings.refresh_token_ttl_days),
            user_agent=(user_agent or "")[:300] or None,
        )
    )
    db.commit()
    return TokenOut(access_token=access, refresh_token=raw, expires_in=expires_in, user=UserOut.model_validate(user))


@router.post("/google", response_model=TokenOut)
def google_login(
    body: GoogleLoginIn,
    request: Request,
    db: DbDep,
    settings: SettingsDep,
    verify: Annotated[GoogleVerifier, Depends(get_google_verifier)],
) -> TokenOut:
    try:
        identity = verify(body.id_token)
    except AuthError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc)) from exc

    user = db.scalars(select(User).where(User.google_sub == identity.sub)).first()
    if user is None:
        user = db.scalars(select(User).where(User.email == identity.email)).first()
        if user is not None and user.google_sub not in (None, identity.sub):
            raise HTTPException(status.HTTP_409_CONFLICT, "email already linked to another Google account")
    if user is None:
        user = User(email=identity.email, name=identity.name, google_sub=identity.sub, avatar_url=identity.picture)
        db.add(user)
    else:
        user.google_sub = identity.sub
        user.name = user.name or identity.name
        user.avatar_url = identity.picture or user.avatar_url
    db.flush()
    return issue_tokens(db, settings, user, request.headers.get("user-agent"))


@router.post("/dev-login", response_model=TokenOut, include_in_schema=False)
def dev_login(body: DevLoginIn, request: Request, db: DbDep, settings: SettingsDep) -> TokenOut:
    """Development/CI only: sign in with just an email."""
    if not settings.dev_login_enabled:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "not found")
    email = body.email.lower()
    user = db.scalars(select(User).where(User.email == email)).first()
    if user is None:
        user = User(email=email, name=body.name or email.split("@")[0])
        db.add(user)
        db.flush()
    return issue_tokens(db, settings, user, request.headers.get("user-agent"))


@router.post("/refresh", response_model=TokenOut)
def refresh(body: RefreshIn, request: Request, db: DbDep, settings: SettingsDep) -> TokenOut:
    token = db.scalars(select(RefreshToken).where(RefreshToken.token_hash == hash_token(body.refresh_token))).first()
    if token is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "unknown refresh token")
    if token.revoked_at is not None:
        # A rotated-out token was used again: assume it leaked and sign the user out everywhere.
        db.execute(
            update(RefreshToken)
            .where(RefreshToken.user_id == token.user_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=utcnow())
        )
        db.commit()
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "refresh token reuse detected; sign in again")
    if token.expires_at <= utcnow():
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "refresh token expired")
    user = db.get(User, token.user_id)
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "user no longer exists")

    out = issue_tokens(db, settings, user, request.headers.get("user-agent"))
    new_token = db.scalars(select(RefreshToken).where(RefreshToken.token_hash == hash_token(out.refresh_token))).one()
    token.revoked_at = utcnow()
    token.replaced_by_id = new_token.id
    db.commit()
    return out


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(body: RefreshIn, db: DbDep) -> None:
    token = db.scalars(select(RefreshToken).where(RefreshToken.token_hash == hash_token(body.refresh_token))).first()
    if token is not None and token.revoked_at is None:
        token.revoked_at = utcnow()
        db.commit()
