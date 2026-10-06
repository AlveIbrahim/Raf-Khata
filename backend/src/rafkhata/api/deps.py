"""Shared FastAPI dependencies: settings, database session, storage, current user, access checks."""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from typing import Annotated

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.orm import Session

from rafkhata.config import Settings
from rafkhata.models import Course, Lecture, SpaceMember, User
from rafkhata.security import AuthError, GoogleVerifier, decode_access_token
from rafkhata.storage import Storage


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_db(request: Request) -> Iterator[Session]:
    session: Session = request.app.state.sessionmaker()
    try:
        yield session
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def get_storage(request: Request) -> Storage:
    return request.app.state.storage


def get_google_verifier(request: Request) -> GoogleVerifier:
    return request.app.state.google_verifier


SettingsDep = Annotated[Settings, Depends(get_settings)]
DbDep = Annotated[Session, Depends(get_db)]
StorageDep = Annotated[Storage, Depends(get_storage)]

_bearer = HTTPBearer(auto_error=False)


def get_current_user(
    db: DbDep,
    settings: SettingsDep,
    creds: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> User:
    if creds is None or creds.scheme.lower() != "bearer":
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "missing bearer token", {"WWW-Authenticate": "Bearer"})
    try:
        user_id = decode_access_token(settings, creds.credentials)
    except AuthError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc), {"WWW-Authenticate": "Bearer"}) from exc
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "user no longer exists")
    return user


UserDep = Annotated[User, Depends(get_current_user)]


def space_roles(db: Session, user_id: uuid.UUID) -> dict[uuid.UUID, str]:
    """{space_id: role} for every section the user belongs to."""
    rows = db.execute(select(SpaceMember.space_id, SpaceMember.role).where(SpaceMember.user_id == user_id)).all()
    return {space_id: role for space_id, role in rows}


def can_view_course(course: Course, user: User, roles: dict[uuid.UUID, str]) -> bool:
    return course.owner_id == user.id or (course.space_id is not None and course.space_id in roles)


def can_edit_course(course: Course, user: User, roles: dict[uuid.UUID, str]) -> bool:
    if course.owner_id == user.id:
        return True
    return course.space_id is not None and roles.get(course.space_id) in ("owner", "cr")


def can_view_lecture(lecture: Lecture, user: User, roles: dict[uuid.UUID, str]) -> bool:
    return lecture.recorded_by == user.id or (lecture.space_id is not None and lecture.space_id in roles)


def can_edit_lecture_content(lecture: Lecture, user: User, roles: dict[uuid.UUID, str]) -> bool:
    """Recorder, or the section's owner/CR, may correct the transcript."""
    if lecture.recorded_by == user.id:
        return True
    return lecture.space_id is not None and roles.get(lecture.space_id) in ("owner", "cr")


def load_course(db: Session, user: User, course_id: uuid.UUID, *, edit: bool = False) -> Course:
    course = db.get(Course, course_id)
    roles = space_roles(db, user.id)
    if course is None or not can_view_course(course, user, roles):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "course not found")
    if edit and not can_edit_course(course, user, roles):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "only the course owner or the section's CR can change it")
    return course


def load_lecture(db: Session, user: User, lecture_id: uuid.UUID, *, owner: bool = False) -> Lecture:
    """Fetch a lecture the user may see (404 otherwise). `owner=True` requires being the recorder."""
    lecture = db.get(Lecture, lecture_id)
    if lecture is None or not can_view_lecture(lecture, user, space_roles(db, user.id)):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "lecture not found")
    if owner and lecture.recorded_by != user.id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "only the person who recorded this lecture can do that")
    return lecture
