"""Lecture content: transcript (with edits), notes per language, study pack, feedback."""

from __future__ import annotations

import uuid
from typing import Annotated

from fastapi import APIRouter, HTTPException, Query, status
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.orm import Session
from sqlalchemy.orm.attributes import flag_modified

from rafkhata import jobs
from rafkhata.api.deps import (
    DbDep,
    SettingsDep,
    StorageDep,
    UserDep,
    can_edit_lecture_content,
    load_lecture,
    space_roles,
)
from rafkhata.api.schemas import (
    BookmarkIO,
    FeedbackIn,
    NotesLang,
    NotesOut,
    PendingOut,
    PhotoOut,
    RegenerateIn,
    SegmentEditIn,
    StudyOut,
    TranscriptOut,
    TranscriptSegmentOut,
)
from rafkhata.models import LECTURE_READY, Bookmark, Feedback, Note, Photo, StudyPack, Transcript, TranscriptEdit
from rafkhata.pipeline.normalize import normalize_for_match, normalize_text

router = APIRouter(prefix="/lectures", tags=["content"])


def current_transcript(db: Session, lecture_id: uuid.UUID) -> Transcript | None:
    return db.scalars(
        select(Transcript)
        .where(Transcript.lecture_id == lecture_id, Transcript.is_current.is_(True))
        .order_by(Transcript.version.desc())
    ).first()


def _pending(lang: str) -> JSONResponse:
    return JSONResponse(status_code=status.HTTP_202_ACCEPTED, content=PendingOut(lang=lang).model_dump())


@router.get("/{lecture_id}/transcript", response_model=TranscriptOut)
def get_transcript(
    lecture_id: uuid.UUID, user: UserDep, db: DbDep, storage: StorageDep, settings: SettingsDep
) -> TranscriptOut:
    lecture = load_lecture(db, user, lecture_id)
    transcript = current_transcript(db, lecture.id)
    if transcript is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "transcript not ready yet")
    bookmarks = db.scalars(
        select(Bookmark)
        .where(Bookmark.lecture_id == lecture.id, Bookmark.user_id == user.id)
        .order_by(Bookmark.t_offset_s)
    ).all()
    photos = db.scalars(
        select(Photo).where(Photo.lecture_id == lecture.id, Photo.uploaded_at.is_not(None)).order_by(Photo.t_offset_s)
    ).all()
    return TranscriptOut(
        lecture_id=lecture.id,
        version=transcript.version,
        kind=transcript.kind,
        provider=transcript.provider,
        language=transcript.language,
        can_edit=can_edit_lecture_content(lecture, user, space_roles(db, user.id)),
        segments=[TranscriptSegmentOut.model_validate(s) for s in transcript.segments],
        bookmarks=[BookmarkIO(t_offset_s=b.t_offset_s, kind=b.kind, text=b.text) for b in bookmarks],
        photos=[
            PhotoOut(
                id=p.id, t_offset_s=p.t_offset_s, url=storage.presign_get(p.storage_key, settings.download_url_ttl_s)
            )
            for p in photos
        ],
        updated_at=transcript.updated_at,
    )


@router.patch("/{lecture_id}/transcript/segments/{segment_id}", response_model=TranscriptSegmentOut)
def edit_segment(
    lecture_id: uuid.UUID, segment_id: str, body: SegmentEditIn, user: UserDep, db: DbDep
) -> TranscriptSegmentOut:
    lecture = load_lecture(db, user, lecture_id)
    if not can_edit_lecture_content(lecture, user, space_roles(db, user.id)):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "only the recorder or the section's CR can edit the transcript")
    transcript = current_transcript(db, lecture.id)
    if transcript is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "transcript not ready yet")
    segments = [dict(s) for s in transcript.segments]
    for segment in segments:
        if segment["id"] == segment_id:
            new_text = normalize_text(body.text)
            db.add(
                TranscriptEdit(
                    lecture_id=lecture.id,
                    transcript_id=transcript.id,
                    segment_id=segment_id,
                    user_id=user.id,
                    before=segment["text"],
                    after=new_text,
                )
            )
            segment["text"] = new_text
            transcript.segments = segments
            transcript.search_text = normalize_for_match(" ".join(s["text"] for s in segments))
            flag_modified(transcript, "segments")
            db.commit()
            return TranscriptSegmentOut.model_validate(segment)
    raise HTTPException(status.HTTP_404_NOT_FOUND, "segment not found")


@router.get("/{lecture_id}/notes", response_model=NotesOut, responses={202: {"model": PendingOut}})
def get_notes(
    lecture_id: uuid.UUID, user: UserDep, db: DbDep, lang: Annotated[NotesLang | None, Query()] = None
) -> NotesOut | JSONResponse:
    lecture = load_lecture(db, user, lecture_id)
    lang = lang or lecture.notes_lang
    note = db.scalars(
        select(Note)
        .where(Note.lecture_id == lecture.id, Note.lang == lang, Note.is_current.is_(True))
        .order_by(Note.version.desc())
    ).first()
    if note is None:
        if jobs.pending_job(db, jobs.REGENERATE_NOTES, lecture.id, lang=lang) or (
            lecture.status != LECTURE_READY and lang == lecture.notes_lang
        ):
            return _pending(lang)
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"no notes in '{lang}' yet; request them with /notes/regenerate")
    return NotesOut(
        lecture_id=lecture.id,
        lang=note.lang,
        version=note.version,
        model=note.model,
        content=note.content,
        html=note.html,
        markdown=note.markdown,
        created_at=note.created_at,
    )


@router.post("/{lecture_id}/notes/regenerate", status_code=status.HTTP_202_ACCEPTED, response_model=PendingOut)
def regenerate_notes(
    lecture_id: uuid.UUID, body: RegenerateIn, user: UserDep, db: DbDep, settings: SettingsDep
) -> PendingOut:
    lecture = load_lecture(db, user, lecture_id)
    if lecture.status != LECTURE_READY:
        raise HTTPException(status.HTTP_409_CONFLICT, "the lecture is still being processed")
    jobs.enqueue(
        db,
        jobs.REGENERATE_NOTES,
        lecture_id=lecture.id,
        payload={"lang": body.lang},
        max_attempts=settings.job_max_attempts,
    )
    db.commit()
    return PendingOut(lang=body.lang)


@router.get("/{lecture_id}/study", response_model=StudyOut, responses={202: {"model": PendingOut}})
def get_study(
    lecture_id: uuid.UUID, user: UserDep, db: DbDep, lang: Annotated[NotesLang | None, Query()] = None
) -> StudyOut | JSONResponse:
    lecture = load_lecture(db, user, lecture_id)
    lang = lang or lecture.notes_lang
    pack = db.scalars(
        select(StudyPack)
        .where(StudyPack.lecture_id == lecture.id, StudyPack.lang == lang, StudyPack.is_current.is_(True))
        .order_by(StudyPack.version.desc())
    ).first()
    if pack is None:
        if jobs.pending_job(db, jobs.REGENERATE_NOTES, lecture.id, lang=lang) or lecture.status != LECTURE_READY:
            return _pending(lang)
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"no study pack in '{lang}'")
    return StudyOut(
        lecture_id=lecture.id,
        lang=pack.lang,
        version=pack.version,
        model=pack.model,
        content=pack.content,
        created_at=pack.created_at,
    )


@router.post("/{lecture_id}/feedback", status_code=status.HTTP_204_NO_CONTENT)
def feedback(lecture_id: uuid.UUID, body: FeedbackIn, user: UserDep, db: DbDep) -> None:
    lecture = load_lecture(db, user, lecture_id)
    if body.kind == "rating" and body.rating is None:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "rating is required")
    db.add(
        Feedback(
            lecture_id=lecture.id,
            user_id=user.id,
            kind=body.kind,
            rating=body.rating,
            comment=body.comment,
            lang=body.lang,
        )
    )
    db.commit()
