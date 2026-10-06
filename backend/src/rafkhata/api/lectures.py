"""Lectures: idempotent creation, segment/photo upload via presigned URLs, finalize, listing, deletion.

Upload flow used by the app (each step can be retried safely):
1. PUT  /lectures/{id}                              create (client-generated UUID) or update
2. POST /lectures/{id}/segments/{n}/upload-url      get a presigned PUT URL, upload the 5-minute file
3. POST /lectures/{id}/segments/{n}/complete        confirm the upload
4. POST /lectures/{id}/photos (+ /complete), PUT /lectures/{id}/bookmarks
5. POST /lectures/{id}/finalize                     all segments present -> processing job queued
"""

from __future__ import annotations

import uuid
from datetime import datetime

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import delete, or_, select
from sqlalchemy.orm import Session

from rafkhata import jobs
from rafkhata.api.deps import DbDep, SettingsDep, StorageDep, UserDep, load_course, load_lecture, space_roles
from rafkhata.api.schemas import (
    AudioUrlOut,
    BookmarkIO,
    BookmarksIn,
    FinalizeIn,
    LectureOut,
    LectureUpsertIn,
    PhotoIn,
    SegmentCompleteIn,
    UploadUrlIn,
    UploadUrlOut,
)
from rafkhata.db import utcnow
from rafkhata.models import (
    LECTURE_CREATED,
    LECTURE_FAILED,
    LECTURE_QUEUED,
    LECTURE_UPLOADING,
    Bookmark,
    Course,
    CourseConsent,
    Lecture,
    LectureSegment,
    Note,
    Photo,
    StudyPack,
    User,
)
from rafkhata.storage import lecture_prefix, photo_key, segment_key

router = APIRouter(prefix="/lectures", tags=["lectures"])

_EDITABLE_STATES = (LECTURE_CREATED, LECTURE_UPLOADING)
_PHOTO_EXT = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}


def lecture_out(db: Session, lecture: Lecture, user: User) -> LectureOut:
    course = db.get(Course, lecture.course_id) if lecture.course_id else None
    recorder = db.get(User, lecture.recorded_by)
    langs = db.scalars(
        select(Note.lang).where(Note.lecture_id == lecture.id, Note.is_current.is_(True)).order_by(Note.lang)
    ).all()
    has_study = db.scalars(select(StudyPack.id).where(StudyPack.lecture_id == lecture.id).limit(1)).first() is not None
    quality = (lecture.quality or {}).get("score")
    return LectureOut(
        id=lecture.id,
        course_id=lecture.course_id,
        course_title=course.title if course else None,
        space_id=lecture.space_id,
        recorded_by=lecture.recorded_by,
        recorded_by_name=recorder.name if recorder else "",
        is_mine=lecture.recorded_by == user.id,
        title=lecture.title,
        started_at=lecture.started_at,
        duration_s=lecture.duration_s,
        status=lecture.status,
        status_detail=lecture.status_detail,
        progress=lecture.progress,
        error=lecture.error,
        notes_lang=lecture.notes_lang,
        quality_score=quality,
        notes_langs=list(langs),
        has_study=has_study,
        created_at=lecture.created_at,
        updated_at=lecture.updated_at,
        processed_at=lecture.processed_at,
    )


def _require_editable(lecture: Lecture) -> None:
    if lecture.status not in _EDITABLE_STATES:
        raise HTTPException(status.HTTP_409_CONFLICT, f"lecture is already {lecture.status}")


@router.put("/{lecture_id}", response_model=LectureOut)
def upsert_lecture(lecture_id: uuid.UUID, body: LectureUpsertIn, user: UserDep, db: DbDep) -> LectureOut:
    course = load_course(db, user, body.course_id) if body.course_id else None
    lecture = db.get(Lecture, lecture_id)
    if lecture is not None and lecture.recorded_by != user.id:
        raise HTTPException(status.HTTP_409_CONFLICT, "lecture id already in use")

    consent = body.consent_confirmed
    if course is not None and consent and db.get(CourseConsent, (course.id, user.id)) is None:
        db.add(CourseConsent(course_id=course.id, user_id=user.id))
    notes_lang = body.notes_lang or (course.notes_lang if course else None) or user.notes_lang

    if lecture is None:
        lecture = Lecture(
            id=lecture_id,
            recorded_by=user.id,
            course_id=course.id if course else None,
            space_id=course.space_id if course else None,
            title=body.title.strip(),
            started_at=body.started_at,
            consent_confirmed=consent,
            notes_lang=notes_lang,
            pipeline_state={},
        )
        db.add(lecture)
    else:
        if lecture.status in _EDITABLE_STATES:
            lecture.course_id = course.id if course else None
            lecture.space_id = course.space_id if course else None
            lecture.started_at = body.started_at or lecture.started_at
            lecture.notes_lang = notes_lang
        lecture.title = body.title.strip() or lecture.title
        lecture.consent_confirmed = lecture.consent_confirmed or consent
    db.commit()
    return lecture_out(db, lecture, user)


@router.post("/{lecture_id}/segments/{idx}/upload-url", response_model=UploadUrlOut)
def segment_upload_url(
    lecture_id: uuid.UUID,
    idx: int,
    body: UploadUrlIn,
    user: UserDep,
    db: DbDep,
    storage: StorageDep,
    settings: SettingsDep,
) -> UploadUrlOut:
    lecture = load_lecture(db, user, lecture_id, owner=True)
    _require_editable(lecture)
    if not 0 <= idx < settings.max_segments_per_lecture:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "segment index out of range")
    if body.size_bytes > settings.max_segment_bytes:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "segment too large")

    key = segment_key(lecture.id, idx)
    segment = db.get(LectureSegment, (lecture.id, idx))
    if segment is None:
        db.add(LectureSegment(lecture_id=lecture.id, idx=idx, storage_key=key))
    lecture.status = LECTURE_UPLOADING
    db.commit()
    presigned = storage.presign_put(key, body.content_type, settings.upload_url_ttl_s)
    return UploadUrlOut(**presigned.__dict__)


@router.post("/{lecture_id}/segments/{idx}/complete", status_code=status.HTTP_204_NO_CONTENT)
def segment_complete(
    lecture_id: uuid.UUID, idx: int, body: SegmentCompleteIn, user: UserDep, db: DbDep, storage: StorageDep
) -> None:
    lecture = load_lecture(db, user, lecture_id, owner=True)
    segment = db.get(LectureSegment, (lecture.id, idx))
    if segment is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "request an upload URL for this segment first")
    stored = storage.size(segment.storage_key)
    if stored is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "segment file not found in storage; upload it again")
    segment.size_bytes = stored
    segment.duration_s = body.duration_s
    segment.uploaded_at = utcnow()
    db.commit()


@router.post("/{lecture_id}/photos", response_model=UploadUrlOut)
def photo_upload_url(
    lecture_id: uuid.UUID,
    body: PhotoIn,
    user: UserDep,
    db: DbDep,
    storage: StorageDep,
    settings: SettingsDep,
) -> UploadUrlOut:
    lecture = load_lecture(db, user, lecture_id, owner=True)
    if body.size_bytes > settings.max_photo_bytes:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "photo too large")
    photo = db.get(Photo, body.photo_id)
    if photo is not None and photo.lecture_id != lecture.id:
        raise HTTPException(status.HTTP_409_CONFLICT, "photo id already in use")
    key = photo_key(lecture.id, body.photo_id, _PHOTO_EXT[body.content_type])
    if photo is None:
        db.add(
            Photo(
                id=body.photo_id,
                lecture_id=lecture.id,
                user_id=user.id,
                t_offset_s=body.t_offset_s,
                storage_key=key,
                content_type=body.content_type,
            )
        )
        db.commit()
    presigned = storage.presign_put(key, body.content_type, settings.upload_url_ttl_s)
    return UploadUrlOut(**presigned.__dict__)


@router.post("/{lecture_id}/photos/{photo_id}/complete", status_code=status.HTTP_204_NO_CONTENT)
def photo_complete(lecture_id: uuid.UUID, photo_id: uuid.UUID, user: UserDep, db: DbDep, storage: StorageDep) -> None:
    lecture = load_lecture(db, user, lecture_id, owner=True)
    photo = db.get(Photo, photo_id)
    if photo is None or photo.lecture_id != lecture.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "photo not found")
    if storage.size(photo.storage_key) is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "photo file not found in storage; upload it again")
    photo.uploaded_at = utcnow()
    db.commit()


@router.put("/{lecture_id}/bookmarks", response_model=list[BookmarkIO])
def put_bookmarks(lecture_id: uuid.UUID, body: BookmarksIn, user: UserDep, db: DbDep) -> list[BookmarkIO]:
    """Replace the current user's bookmarks on this lecture."""
    lecture = load_lecture(db, user, lecture_id)
    db.execute(delete(Bookmark).where(Bookmark.lecture_id == lecture.id, Bookmark.user_id == user.id))
    for b in body.bookmarks:
        db.add(Bookmark(lecture_id=lecture.id, user_id=user.id, t_offset_s=b.t_offset_s, kind=b.kind, text=b.text))
    db.commit()
    return body.bookmarks


@router.post("/{lecture_id}/finalize", response_model=LectureOut)
def finalize_lecture(
    lecture_id: uuid.UUID, body: FinalizeIn, user: UserDep, db: DbDep, settings: SettingsDep
) -> LectureOut:
    lecture = load_lecture(db, user, lecture_id, owner=True)
    if lecture.status not in _EDITABLE_STATES:
        return lecture_out(db, lecture, user)  # already finalized: idempotent
    if body.duration_s > settings.max_lecture_minutes * 60:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "lecture is longer than the allowed maximum")
    uploaded = {
        s.idx
        for s in db.scalars(
            select(LectureSegment).where(
                LectureSegment.lecture_id == lecture.id, LectureSegment.uploaded_at.is_not(None)
            )
        )
    }
    missing = [i for i in range(body.segment_count) if i not in uploaded]
    if missing:
        raise HTTPException(status.HTTP_409_CONFLICT, {"message": "segments missing", "missing": missing})

    lecture.segment_count = body.segment_count
    lecture.duration_s = body.duration_s
    lecture.status = LECTURE_QUEUED
    lecture.status_detail = None
    lecture.progress = 0
    lecture.error = None
    lecture.finalized_at = utcnow()
    jobs.enqueue(db, jobs.PROCESS_LECTURE, lecture_id=lecture.id, max_attempts=settings.job_max_attempts)
    db.commit()
    return lecture_out(db, lecture, user)


@router.post("/{lecture_id}/retry", response_model=LectureOut)
def retry_lecture(lecture_id: uuid.UUID, user: UserDep, db: DbDep, settings: SettingsDep) -> LectureOut:
    lecture = load_lecture(db, user, lecture_id, owner=True)
    if lecture.status != LECTURE_FAILED:
        raise HTTPException(status.HTTP_409_CONFLICT, "only failed lectures can be retried")
    lecture.status = LECTURE_QUEUED
    lecture.error = None
    jobs.enqueue(db, jobs.PROCESS_LECTURE, lecture_id=lecture.id, max_attempts=settings.job_max_attempts)
    db.commit()
    return lecture_out(db, lecture, user)


@router.get("", response_model=list[LectureOut])
def list_lectures(
    user: UserDep,
    db: DbDep,
    course_id: uuid.UUID | None = None,
    space_id: uuid.UUID | None = None,
    before: datetime | None = None,
    limit: int = Query(default=50, ge=1, le=200),
) -> list[LectureOut]:
    roles = space_roles(db, user.id)
    stmt = select(Lecture).where(or_(Lecture.recorded_by == user.id, Lecture.space_id.in_(list(roles))))
    if course_id is not None:
        stmt = stmt.where(Lecture.course_id == course_id)
    if space_id is not None:
        stmt = stmt.where(Lecture.space_id == space_id)
    if before is not None:
        stmt = stmt.where(Lecture.created_at < before)
    lectures = db.scalars(stmt.order_by(Lecture.created_at.desc()).limit(limit)).all()
    return [lecture_out(db, lecture, user) for lecture in lectures]


@router.get("/{lecture_id}", response_model=LectureOut)
def get_lecture(lecture_id: uuid.UUID, user: UserDep, db: DbDep) -> LectureOut:
    return lecture_out(db, load_lecture(db, user, lecture_id), user)


@router.get("/{lecture_id}/audio-url", response_model=AudioUrlOut)
def audio_url(
    lecture_id: uuid.UUID, user: UserDep, db: DbDep, storage: StorageDep, settings: SettingsDep
) -> AudioUrlOut:
    lecture = load_lecture(db, user, lecture_id)
    if not lecture.audio_key:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "audio not ready yet")
    ttl = settings.download_url_ttl_s
    return AudioUrlOut(url=storage.presign_get(lecture.audio_key, ttl), expires_in=ttl)


@router.delete("/{lecture_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_lecture(lecture_id: uuid.UUID, user: UserDep, db: DbDep) -> None:
    lecture = load_lecture(db, user, lecture_id, owner=True)
    jobs.enqueue(db, jobs.DELETE_PREFIX, payload={"prefix": lecture_prefix(lecture.id)}, dedupe=False)
    db.delete(lecture)
    db.commit()
