"""ORM models. See docs/architecture.md for how they relate."""

from __future__ import annotations

import uuid
from datetime import date, datetime
from typing import Any

from sqlalchemy import (
    BigInteger,
    Boolean,
    Date,
    Float,
    ForeignKey,
    Index,
    Integer,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    Uuid,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from rafkhata.db import Base, JSONType, UTCDateTime, utcnow

# Lecture.status values
LECTURE_CREATED = "created"
LECTURE_UPLOADING = "uploading"
LECTURE_QUEUED = "queued"
LECTURE_PROCESSING = "processing"
LECTURE_READY = "ready"
LECTURE_FAILED = "failed"

# Job.status values
JOB_QUEUED = "queued"
JOB_RUNNING = "running"
JOB_SUCCEEDED = "succeeded"
JOB_FAILED = "failed"

NOTE_LANGS = ("bn", "en", "mixed")


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False)


class User(TimestampMixin, Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(200), default="")
    google_sub: Mapped[str | None] = mapped_column(String(64), unique=True)
    avatar_url: Mapped[str | None] = mapped_column(String(1000))
    university: Mapped[str | None] = mapped_column(String(200))
    department: Mapped[str | None] = mapped_column(String(200))
    batch: Mapped[str | None] = mapped_column(String(50))
    section: Mapped[str | None] = mapped_column(String(50))
    notes_lang: Mapped[str] = mapped_column(String(8), default="bn")
    onboarded: Mapped[bool] = mapped_column(Boolean, default=False)


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    revoked_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    replaced_by_id: Mapped[uuid.UUID | None] = mapped_column(Uuid)
    user_agent: Mapped[str | None] = mapped_column(String(300))


class Space(TimestampMixin, Base):
    """A class section. Members see each other's lectures recorded in the section's courses."""

    __tablename__ = "spaces"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(200))
    university: Mapped[str | None] = mapped_column(String(200))
    section_label: Mapped[str | None] = mapped_column(String(100))
    invite_code: Mapped[str] = mapped_column(String(16), unique=True, index=True)
    created_by: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))

    members: Mapped[list[SpaceMember]] = relationship(back_populates="space", cascade="all, delete-orphan")


class SpaceMember(Base):
    __tablename__ = "space_members"

    space_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("spaces.id", ondelete="CASCADE"), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True, index=True)
    role: Mapped[str] = mapped_column(String(16), default="member")  # owner | cr | member
    joined_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    space: Mapped[Space] = relationship(back_populates="members")
    user: Mapped[User] = relationship()


class Course(TimestampMixin, Base):
    __tablename__ = "courses"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    owner_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    space_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("spaces.id", ondelete="SET NULL"), index=True)
    code: Mapped[str | None] = mapped_column(String(50))
    title: Mapped[str] = mapped_column(String(200))
    teacher_name: Mapped[str | None] = mapped_column(String(200))
    semester: Mapped[str | None] = mapped_column(String(50))
    glossary: Mapped[list[str]] = mapped_column(JSONType, default=list)
    notes_lang: Mapped[str | None] = mapped_column(String(8))
    archived: Mapped[bool] = mapped_column(Boolean, default=False)

    routine: Mapped[list[RoutineSlot]] = relationship(
        back_populates="course", cascade="all, delete-orphan", order_by="RoutineSlot.weekday"
    )


class CourseConsent(Base):
    """A recorder's confirmation that the teacher allowed recording this course."""

    __tablename__ = "course_consents"

    course_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("courses.id", ondelete="CASCADE"), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    confirmed_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class RoutineSlot(Base):
    __tablename__ = "routine_slots"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    course_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("courses.id", ondelete="CASCADE"), index=True)
    weekday: Mapped[int] = mapped_column(SmallInteger)  # 0 = Monday ... 6 = Sunday
    start_time: Mapped[str] = mapped_column(String(5))  # "HH:MM", local time
    end_time: Mapped[str] = mapped_column(String(5))
    room: Mapped[str | None] = mapped_column(String(100))

    course: Mapped[Course] = relationship(back_populates="routine")


class Lecture(TimestampMixin, Base):
    __tablename__ = "lectures"

    # Generated by the app so creation is idempotent and works offline.
    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    course_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("courses.id", ondelete="SET NULL"), index=True)
    space_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("spaces.id", ondelete="SET NULL"), index=True)
    recorded_by: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(300), default="")
    started_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    duration_s: Mapped[float] = mapped_column(Float, default=0.0)
    status: Mapped[str] = mapped_column(String(16), default=LECTURE_CREATED, index=True)
    status_detail: Mapped[str | None] = mapped_column(String(64))
    progress: Mapped[int] = mapped_column(SmallInteger, default=0)
    segment_count: Mapped[int | None] = mapped_column(Integer)
    consent_confirmed: Mapped[bool] = mapped_column(Boolean, default=False)
    notes_lang: Mapped[str] = mapped_column(String(8), default="bn")
    quality: Mapped[dict[str, Any] | None] = mapped_column(JSONType)
    audio_key: Mapped[str | None] = mapped_column(String(500))
    audio_duration_s: Mapped[float | None] = mapped_column(Float)
    pipeline_state: Mapped[dict[str, Any]] = mapped_column(JSONType, default=dict)
    error: Mapped[str | None] = mapped_column(Text)
    finalized_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    processed_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    cost_usd: Mapped[float] = mapped_column(Float, default=0.0)

    course: Mapped[Course | None] = relationship()
    recorder: Mapped[User] = relationship()
    segments: Mapped[list[LectureSegment]] = relationship(cascade="all, delete-orphan", order_by="LectureSegment.idx")


class LectureSegment(Base):
    """One uploaded 5-minute audio file of a lecture."""

    __tablename__ = "lecture_segments"

    lecture_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("lectures.id", ondelete="CASCADE"), primary_key=True)
    idx: Mapped[int] = mapped_column(Integer, primary_key=True)
    storage_key: Mapped[str] = mapped_column(String(500))
    size_bytes: Mapped[int | None] = mapped_column(BigInteger)
    duration_s: Mapped[float | None] = mapped_column(Float)
    uploaded_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Bookmark(Base):
    __tablename__ = "bookmarks"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    lecture_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("lectures.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    t_offset_s: Mapped[float] = mapped_column(Float)
    kind: Mapped[str] = mapped_column(String(16))  # important | confused | note
    text: Mapped[str | None] = mapped_column(String(500))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Photo(Base):
    __tablename__ = "photos"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    lecture_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("lectures.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    t_offset_s: Mapped[float] = mapped_column(Float)
    storage_key: Mapped[str] = mapped_column(String(500))
    content_type: Mapped[str] = mapped_column(String(50), default="image/jpeg")
    uploaded_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    ocr_text: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Transcript(Base):
    __tablename__ = "transcripts"
    __table_args__ = (UniqueConstraint("lecture_id", "version"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    lecture_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("lectures.id", ondelete="CASCADE"), index=True)
    version: Mapped[int] = mapped_column(Integer)
    kind: Mapped[str] = mapped_column(String(16))  # raw | corrected
    provider: Mapped[str] = mapped_column(String(32))
    model: Mapped[str | None] = mapped_column(String(64))
    is_current: Mapped[bool] = mapped_column(Boolean, default=False)
    language: Mapped[str | None] = mapped_column(String(16))
    # [{"id": "s0001", "start": 0.0, "end": 4.2, "speaker": "T", "text": "..."}]
    segments: Mapped[list[dict[str, Any]]] = mapped_column(JSONType, default=list)
    search_text: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, onupdate=utcnow)


class TranscriptEdit(Base):
    __tablename__ = "transcript_edits"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    lecture_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("lectures.id", ondelete="CASCADE"), index=True)
    transcript_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("transcripts.id", ondelete="CASCADE"))
    segment_id: Mapped[str] = mapped_column(String(16))
    user_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    before: Mapped[str] = mapped_column(Text)
    after: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Note(Base):
    __tablename__ = "notes"
    __table_args__ = (UniqueConstraint("lecture_id", "lang", "version"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    lecture_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("lectures.id", ondelete="CASCADE"), index=True)
    lang: Mapped[str] = mapped_column(String(8))
    version: Mapped[int] = mapped_column(Integer)
    model: Mapped[str | None] = mapped_column(String(64))
    content: Mapped[dict[str, Any]] = mapped_column(JSONType)
    html: Mapped[str] = mapped_column(Text, default="")
    markdown: Mapped[str] = mapped_column(Text, default="")
    search_text: Mapped[str] = mapped_column(Text, default="")
    is_current: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class StudyPack(Base):
    __tablename__ = "study_packs"
    __table_args__ = (UniqueConstraint("lecture_id", "lang", "version"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    lecture_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("lectures.id", ondelete="CASCADE"), index=True)
    lang: Mapped[str] = mapped_column(String(8))
    version: Mapped[int] = mapped_column(Integer)
    model: Mapped[str | None] = mapped_column(String(64))
    content: Mapped[dict[str, Any]] = mapped_column(JSONType)
    is_current: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Deadline(Base):
    """An announcement with a date (CT, assignment, quiz...) extracted from a lecture."""

    __tablename__ = "deadlines"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    lecture_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("lectures.id", ondelete="CASCADE"), index=True)
    course_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("courses.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(32))
    title: Mapped[str] = mapped_column(String(500))
    due_date: Mapped[date | None] = mapped_column(Date, index=True)
    due_text: Mapped[str | None] = mapped_column(String(200))
    source_segment_ids: Mapped[list[str]] = mapped_column(JSONType, default=list)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Job(Base):
    __tablename__ = "jobs"
    __table_args__ = (Index("ix_jobs_claim", "status", "run_after"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    kind: Mapped[str] = mapped_column(String(32), index=True)
    lecture_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("lectures.id", ondelete="CASCADE"), index=True)
    payload: Mapped[dict[str, Any]] = mapped_column(JSONType, default=dict)
    status: Mapped[str] = mapped_column(String(16), default=JOB_QUEUED)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    max_attempts: Mapped[int] = mapped_column(Integer, default=4)
    run_after: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    locked_by: Mapped[str | None] = mapped_column(String(100))
    locked_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    last_error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, onupdate=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(UTCDateTime)


class CostEntry(Base):
    __tablename__ = "cost_ledger"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    lecture_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("lectures.id", ondelete="SET NULL"), index=True)
    step: Mapped[str] = mapped_column(String(32))
    provider: Mapped[str] = mapped_column(String(32))
    model: Mapped[str | None] = mapped_column(String(64))
    units: Mapped[dict[str, Any]] = mapped_column(JSONType, default=dict)
    usd: Mapped[float] = mapped_column(Float, default=0.0)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Device(TimestampMixin, Base):
    __tablename__ = "devices"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    fcm_token: Mapped[str] = mapped_column(String(500), unique=True)
    platform: Mapped[str] = mapped_column(String(16), default="android")
    app_version: Mapped[str | None] = mapped_column(String(32))
    locale: Mapped[str | None] = mapped_column(String(16))


class Feedback(Base):
    __tablename__ = "feedback"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    lecture_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("lectures.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    kind: Mapped[str] = mapped_column(String(16))  # rating | report
    rating: Mapped[int | None] = mapped_column(SmallInteger)
    comment: Mapped[str | None] = mapped_column(Text)
    lang: Mapped[str | None] = mapped_column(String(8))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
