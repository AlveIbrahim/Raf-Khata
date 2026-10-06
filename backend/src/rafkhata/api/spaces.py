"""Sections ("spaces"): a class section whose members share lectures and notes."""

from __future__ import annotations

import secrets
import uuid
from typing import Literal

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from rafkhata.api.deps import DbDep, UserDep
from rafkhata.api.schemas import SpaceDetailOut, SpaceIn, SpaceJoinIn, SpaceMemberOut, SpaceOut
from rafkhata.models import Space, SpaceMember, User

router = APIRouter(prefix="/spaces", tags=["sections"])

# No 0/O or 1/I so codes can be read out loud in class.
INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def new_invite_code(db: Session) -> str:
    while True:
        code = "".join(secrets.choice(INVITE_ALPHABET) for _ in range(8))
        if db.scalars(select(Space.id).where(Space.invite_code == code)).first() is None:
            return code


def _member(db: Session, space_id: uuid.UUID, user_id: uuid.UUID) -> SpaceMember | None:
    return db.get(SpaceMember, (space_id, user_id))


def _space_out(db: Session, space: Space, role: str) -> SpaceOut:
    count = db.scalar(select(func.count()).select_from(SpaceMember).where(SpaceMember.space_id == space.id)) or 0
    return SpaceOut(
        id=space.id,
        name=space.name,
        university=space.university,
        section_label=space.section_label,
        invite_code=space.invite_code,
        role=role,
        member_count=count,
        created_at=space.created_at,
    )


def _detail(db: Session, space: Space, role: str) -> SpaceDetailOut:
    rows = db.execute(
        select(SpaceMember, User)
        .join(User, User.id == SpaceMember.user_id)
        .where(SpaceMember.space_id == space.id)
        .order_by(SpaceMember.joined_at)
    ).all()
    members = [SpaceMemberOut(user_id=u.id, name=u.name, role=m.role, joined_at=m.joined_at) for m, u in rows]
    return SpaceDetailOut(**_space_out(db, space, role).model_dump(), members=members)


def _load_membership(db: Session, space_id: uuid.UUID, user_id: uuid.UUID) -> tuple[Space, SpaceMember]:
    space = db.get(Space, space_id)
    member = _member(db, space_id, user_id) if space else None
    if space is None or member is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "section not found")
    return space, member


@router.post("", response_model=SpaceDetailOut, status_code=status.HTTP_201_CREATED)
def create_space(body: SpaceIn, user: UserDep, db: DbDep) -> SpaceDetailOut:
    space = Space(
        name=body.name.strip(),
        university=(body.university or user.university),
        section_label=body.section_label,
        invite_code=new_invite_code(db),
        created_by=user.id,
    )
    db.add(space)
    db.flush()
    db.add(SpaceMember(space_id=space.id, user_id=user.id, role="owner"))
    db.commit()
    return _detail(db, space, "owner")


@router.get("", response_model=list[SpaceOut])
def list_spaces(user: UserDep, db: DbDep) -> list[SpaceOut]:
    rows = db.execute(
        select(Space, SpaceMember.role)
        .join(SpaceMember, SpaceMember.space_id == Space.id)
        .where(SpaceMember.user_id == user.id)
        .order_by(Space.name)
    ).all()
    return [_space_out(db, space, role) for space, role in rows]


@router.post("/join", response_model=SpaceDetailOut)
def join_space(body: SpaceJoinIn, user: UserDep, db: DbDep) -> SpaceDetailOut:
    code = body.invite_code.strip().upper()
    space = db.scalars(select(Space).where(Space.invite_code == code)).first()
    if space is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "no section with that invite code")
    member = _member(db, space.id, user.id)
    if member is None:
        member = SpaceMember(space_id=space.id, user_id=user.id, role="member")
        db.add(member)
        db.commit()
    return _detail(db, space, member.role)


@router.get("/{space_id}", response_model=SpaceDetailOut)
def get_space(space_id: uuid.UUID, user: UserDep, db: DbDep) -> SpaceDetailOut:
    space, member = _load_membership(db, space_id, user.id)
    return _detail(db, space, member.role)


@router.post("/{space_id}/invite-code", response_model=SpaceOut)
def rotate_invite_code(space_id: uuid.UUID, user: UserDep, db: DbDep) -> SpaceOut:
    space, member = _load_membership(db, space_id, user.id)
    if member.role not in ("owner", "cr"):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "only the owner or CR can change the invite code")
    space.invite_code = new_invite_code(db)
    db.commit()
    return _space_out(db, space, member.role)


class RoleIn(BaseModel):
    role: Literal["cr", "member"]


@router.patch("/{space_id}/members/{member_id}", response_model=SpaceDetailOut)
def set_member_role(
    space_id: uuid.UUID, member_id: uuid.UUID, body: RoleIn, user: UserDep, db: DbDep
) -> SpaceDetailOut:
    space, me = _load_membership(db, space_id, user.id)
    if me.role != "owner":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "only the section owner can change roles")
    target = _member(db, space_id, member_id)
    if target is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "member not found")
    if target.role == "owner":
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "the owner's role can't be changed")
    target.role = body.role
    db.commit()
    return _detail(db, space, me.role)


@router.delete("/{space_id}/members/me", status_code=status.HTTP_204_NO_CONTENT)
def leave_space(space_id: uuid.UUID, user: UserDep, db: DbDep) -> None:
    space, me = _load_membership(db, space_id, user.id)
    others = db.scalars(
        select(SpaceMember)
        .where(SpaceMember.space_id == space_id, SpaceMember.user_id != user.id)
        .order_by(SpaceMember.joined_at)
    ).all()
    if not others:
        db.delete(space)
    else:
        if me.role == "owner":
            heir = next((m for m in others if m.role == "cr"), others[0])
            heir.role = "owner"
        db.delete(me)
    db.commit()
