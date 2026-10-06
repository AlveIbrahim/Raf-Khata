"""The signed-in user's profile, data export and account deletion."""

from __future__ import annotations

from fastapi import APIRouter, status
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from sqlalchemy import select

from rafkhata import jobs
from rafkhata.api.deps import DbDep, StorageDep, UserDep
from rafkhata.api.schemas import UserOut, UserPatch
from rafkhata.db import utcnow
from rafkhata.models import (
    Bookmark,
    Course,
    Lecture,
    Note,
    Space,
    SpaceMember,
    StudyPack,
    Transcript,
)
from rafkhata.storage import lecture_prefix

router = APIRouter(prefix="/me", tags=["me"])


@router.get("", response_model=UserOut)
def get_me(user: UserDep) -> UserOut:
    return UserOut.model_validate(user)


@router.patch("", response_model=UserOut)
def patch_me(body: UserPatch, user: UserDep, db: DbDep) -> UserOut:
    for field, value in body.model_dump(exclude_unset=True).items():
        if value is not None:
            setattr(user, field, value.strip() if isinstance(value, str) else value)
    db.commit()
    return UserOut.model_validate(user)


@router.delete("", status_code=status.HTTP_204_NO_CONTENT)
def delete_me(user: UserDep, db: DbDep) -> None:
    """Delete the account and everything the user recorded. Sections they own pass to another member."""
    for lecture_id in db.scalars(select(Lecture.id).where(Lecture.recorded_by == user.id)).all():
        jobs.enqueue(db, jobs.DELETE_PREFIX, payload={"prefix": lecture_prefix(lecture_id)}, dedupe=False)

    for membership in db.scalars(select(SpaceMember).where(SpaceMember.user_id == user.id)).all():
        others = db.scalars(
            select(SpaceMember)
            .where(SpaceMember.space_id == membership.space_id, SpaceMember.user_id != user.id)
            .order_by(SpaceMember.joined_at)
        ).all()
        if not others:
            db.delete(db.get(Space, membership.space_id))
            continue
        heir = next((m for m in others if m.role in ("owner", "cr")), others[0])
        if membership.role == "owner":
            heir.role = "owner"
        # Section courses survive with a new owner so classmates keep their lectures.
        for course in db.scalars(
            select(Course).where(Course.owner_id == user.id, Course.space_id == membership.space_id)
        ).all():
            course.owner_id = heir.user_id

    db.delete(user)
    db.commit()


@router.get("/export")
def export_me(user: UserDep, db: DbDep, storage: StorageDep) -> JSONResponse:
    """Everything stored about the user, as one JSON file. Audio links are valid for 24 hours."""
    memberships = db.scalars(select(SpaceMember).where(SpaceMember.user_id == user.id)).all()
    spaces = []
    for m in memberships:
        space = db.get(Space, m.space_id)
        if space is not None:
            spaces.append({"id": space.id, "name": space.name, "section_label": space.section_label, "role": m.role})

    courses = [
        {
            "id": c.id,
            "title": c.title,
            "code": c.code,
            "teacher_name": c.teacher_name,
            "semester": c.semester,
            "glossary": c.glossary,
            "routine": [
                {"weekday": s.weekday, "start_time": s.start_time, "end_time": s.end_time, "room": s.room}
                for s in c.routine
            ],
        }
        for c in db.scalars(select(Course).where(Course.owner_id == user.id)).all()
    ]

    lectures = []
    for lecture in db.scalars(select(Lecture).where(Lecture.recorded_by == user.id).order_by(Lecture.created_at)):
        transcript = db.scalars(
            select(Transcript).where(Transcript.lecture_id == lecture.id, Transcript.is_current.is_(True))
        ).first()
        notes = db.scalars(select(Note).where(Note.lecture_id == lecture.id, Note.is_current.is_(True))).all()
        study = db.scalars(select(StudyPack).where(StudyPack.lecture_id == lecture.id, StudyPack.is_current.is_(True)))
        bookmarks = db.scalars(select(Bookmark).where(Bookmark.lecture_id == lecture.id, Bookmark.user_id == user.id))
        lectures.append(
            {
                "id": lecture.id,
                "course_id": lecture.course_id,
                "title": lecture.title,
                "started_at": lecture.started_at,
                "duration_s": lecture.duration_s,
                "status": lecture.status,
                "audio_url": storage.presign_get(lecture.audio_key, 24 * 3600) if lecture.audio_key else None,
                "transcript": transcript.segments if transcript else None,
                "notes": {n.lang: n.content for n in notes},
                "study": {s.lang: s.content for s in study.all()},
                "bookmarks": [{"t_offset_s": b.t_offset_s, "kind": b.kind, "text": b.text} for b in bookmarks.all()],
            }
        )

    data = {
        "exported_at": utcnow(),
        "user": UserOut.model_validate(user).model_dump(),
        "spaces": spaces,
        "courses": courses,
        "lectures": lectures,
    }
    return JSONResponse(
        content=jsonable_encoder(data),
        headers={"Content-Disposition": 'attachment; filename="raf-khata-export.json"'},
    )
