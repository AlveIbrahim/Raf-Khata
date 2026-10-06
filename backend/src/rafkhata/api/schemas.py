"""Request and response bodies for the REST API."""

from __future__ import annotations

import uuid
from datetime import date, datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

NotesLang = Literal["bn", "en", "mixed"]
_TIME_PATTERN = r"^([01]\d|2[0-3]):[0-5]\d$"


class ORMModel(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# ---------- auth / me ----------


class UserOut(ORMModel):
    id: uuid.UUID
    email: str
    name: str
    avatar_url: str | None
    university: str | None
    department: str | None
    batch: str | None
    section: str | None
    notes_lang: str
    onboarded: bool


class UserPatch(BaseModel):
    name: str | None = Field(default=None, max_length=200)
    university: str | None = Field(default=None, max_length=200)
    department: str | None = Field(default=None, max_length=200)
    batch: str | None = Field(default=None, max_length=50)
    section: str | None = Field(default=None, max_length=50)
    notes_lang: NotesLang | None = None
    onboarded: bool | None = None


class GoogleLoginIn(BaseModel):
    id_token: str = Field(min_length=10)


class DevLoginIn(BaseModel):
    email: str = Field(min_length=3, max_length=320, pattern=r"^[^@\s]+@[^@\s]+$")
    name: str = Field(default="", max_length=200)


class RefreshIn(BaseModel):
    refresh_token: str = Field(min_length=10)


class TokenOut(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
    user: UserOut


# ---------- sections (spaces) ----------


class SpaceIn(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    university: str | None = Field(default=None, max_length=200)
    section_label: str | None = Field(default=None, max_length=100)


class SpaceJoinIn(BaseModel):
    invite_code: str = Field(min_length=4, max_length=16)


class SpaceMemberOut(BaseModel):
    user_id: uuid.UUID
    name: str
    role: str
    joined_at: datetime


class SpaceOut(BaseModel):
    id: uuid.UUID
    name: str
    university: str | None
    section_label: str | None
    invite_code: str
    role: str
    member_count: int
    created_at: datetime


class SpaceDetailOut(SpaceOut):
    members: list[SpaceMemberOut]


# ---------- courses ----------


class RoutineSlotIO(BaseModel):
    weekday: int = Field(ge=0, le=6, description="0 = Monday ... 6 = Sunday")
    start_time: str = Field(pattern=_TIME_PATTERN)
    end_time: str = Field(pattern=_TIME_PATTERN)
    room: str | None = Field(default=None, max_length=100)

    model_config = ConfigDict(from_attributes=True)


class CourseIn(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    code: str | None = Field(default=None, max_length=50)
    teacher_name: str | None = Field(default=None, max_length=200)
    semester: str | None = Field(default=None, max_length=50)
    space_id: uuid.UUID | None = None
    glossary: list[str] = Field(default_factory=list, max_length=500)
    notes_lang: NotesLang | None = None
    routine: list[RoutineSlotIO] = Field(default_factory=list, max_length=20)

    @field_validator("glossary")
    @classmethod
    def _clean_glossary(cls, terms: list[str]) -> list[str]:
        return clean_terms(terms)


class CoursePatch(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=200)
    code: str | None = Field(default=None, max_length=50)
    teacher_name: str | None = Field(default=None, max_length=200)
    semester: str | None = Field(default=None, max_length=50)
    space_id: uuid.UUID | None = None
    glossary: list[str] | None = Field(default=None, max_length=500)
    notes_lang: NotesLang | None = None
    archived: bool | None = None

    @field_validator("glossary")
    @classmethod
    def _clean_glossary(cls, terms: list[str] | None) -> list[str] | None:
        return None if terms is None else clean_terms(terms)


class RoutineIn(BaseModel):
    slots: list[RoutineSlotIO] = Field(max_length=20)


class CourseOut(BaseModel):
    id: uuid.UUID
    title: str
    code: str | None
    teacher_name: str | None
    semester: str | None
    space_id: uuid.UUID | None
    space_name: str | None
    glossary: list[str]
    notes_lang: str | None
    archived: bool
    routine: list[RoutineSlotIO]
    is_owner: bool
    can_edit: bool
    consent_confirmed: bool
    lecture_count: int
    created_at: datetime
    updated_at: datetime


def clean_terms(terms: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for term in terms:
        term = " ".join(term.split())[:100]
        if term and term.lower() not in seen:
            seen.add(term.lower())
            out.append(term)
    return out


# ---------- lectures ----------


class LectureUpsertIn(BaseModel):
    course_id: uuid.UUID | None = None
    title: str = Field(default="", max_length=300)
    started_at: datetime | None = None
    consent_confirmed: bool = False
    notes_lang: NotesLang | None = None


class LectureOut(BaseModel):
    id: uuid.UUID
    course_id: uuid.UUID | None
    course_title: str | None
    space_id: uuid.UUID | None
    recorded_by: uuid.UUID
    recorded_by_name: str
    is_mine: bool
    title: str
    started_at: datetime | None
    duration_s: float
    status: str
    status_detail: str | None
    progress: int
    error: str | None
    notes_lang: str
    quality_score: float | None
    notes_langs: list[str]
    has_study: bool
    created_at: datetime
    updated_at: datetime
    processed_at: datetime | None


class UploadUrlIn(BaseModel):
    size_bytes: int = Field(gt=0)
    content_type: str = Field(default="audio/aac", max_length=50)


class UploadUrlOut(BaseModel):
    url: str
    method: str
    headers: dict[str, str]
    expires_at: datetime


class SegmentCompleteIn(BaseModel):
    size_bytes: int = Field(gt=0)
    duration_s: float = Field(ge=0, le=3600)


class PhotoIn(BaseModel):
    photo_id: uuid.UUID
    t_offset_s: float = Field(ge=0)
    content_type: Literal["image/jpeg", "image/png", "image/webp"] = "image/jpeg"
    size_bytes: int = Field(gt=0)


class PhotoOut(BaseModel):
    id: uuid.UUID
    t_offset_s: float
    url: str | None


class BookmarkIO(BaseModel):
    t_offset_s: float = Field(ge=0)
    kind: Literal["important", "confused", "note"]
    text: str | None = Field(default=None, max_length=500)


class BookmarksIn(BaseModel):
    bookmarks: list[BookmarkIO] = Field(max_length=500)


class FinalizeIn(BaseModel):
    segment_count: int = Field(ge=1)
    duration_s: float = Field(ge=0)


class AudioUrlOut(BaseModel):
    url: str
    expires_in: int


# ---------- content ----------


class TranscriptSegmentOut(BaseModel):
    id: str
    start: float
    end: float
    speaker: str | None = None
    text: str


class TranscriptOut(BaseModel):
    lecture_id: uuid.UUID
    version: int
    kind: str
    provider: str
    language: str | None
    can_edit: bool
    segments: list[TranscriptSegmentOut]
    bookmarks: list[BookmarkIO]
    photos: list[PhotoOut]
    updated_at: datetime


class SegmentEditIn(BaseModel):
    text: str = Field(min_length=1, max_length=5000)


class NotesOut(BaseModel):
    lecture_id: uuid.UUID
    lang: str
    version: int
    model: str | None
    content: dict[str, Any]
    html: str
    markdown: str
    created_at: datetime


class PendingOut(BaseModel):
    status: Literal["pending"] = "pending"
    lang: str


class RegenerateIn(BaseModel):
    lang: NotesLang


class StudyOut(BaseModel):
    lecture_id: uuid.UUID
    lang: str
    version: int
    model: str | None
    content: dict[str, Any]
    created_at: datetime


class FeedbackIn(BaseModel):
    kind: Literal["rating", "report"]
    rating: int | None = Field(default=None, ge=1, le=5)
    comment: str | None = Field(default=None, max_length=2000)
    lang: NotesLang | None = None


# ---------- other ----------


class DeadlineOut(BaseModel):
    id: uuid.UUID
    lecture_id: uuid.UUID
    course_id: uuid.UUID | None
    course_title: str | None
    kind: str
    title: str
    due_date: date | None
    due_text: str | None
    lecture_started_at: datetime | None


class SearchHitOut(BaseModel):
    lecture_id: uuid.UUID
    lecture_title: str
    course_title: str | None
    source: Literal["transcript", "notes"]
    snippet: str
    segment_id: str | None
    t_start: float | None


class DeviceIn(BaseModel):
    fcm_token: str = Field(min_length=10, max_length=500)
    platform: Literal["android", "ios"] = "android"
    app_version: str | None = Field(default=None, max_length=32)
    locale: str | None = Field(default=None, max_length=16)
