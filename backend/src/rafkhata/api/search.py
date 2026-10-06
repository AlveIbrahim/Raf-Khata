"""Search across the transcripts and notes of every lecture the user can see.

Text is matched after `normalize_for_match`, so Bangla digits, joiners, punctuation and Latin case
don't matter. A simple substring match is enough at MVP scale.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Query
from sqlalchemy import or_, select

from rafkhata.api.deps import DbDep, UserDep, space_roles
from rafkhata.api.schemas import SearchHitOut
from rafkhata.models import Course, Lecture, Note, Transcript
from rafkhata.pipeline.normalize import normalize_for_match

router = APIRouter(tags=["search"])


def _escape_like(value: str) -> str:
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _snippet(text: str, needle: str, width: int = 160) -> str:
    pos = normalize_for_match(text).find(needle)
    if pos < 0 or len(text) <= width:
        return text[:width]
    start = max(0, min(pos - width // 3, len(text) - width))
    return ("…" if start else "") + text[start : start + width] + ("…" if start + width < len(text) else "")


@router.get("/search", response_model=list[SearchHitOut])
def search(
    user: UserDep,
    db: DbDep,
    q: Annotated[str, Query(min_length=2, max_length=200)],
    limit: Annotated[int, Query(ge=1, le=50)] = 30,
) -> list[SearchHitOut]:
    needle = normalize_for_match(q)
    if len(needle) < 2:
        return []
    pattern = f"%{_escape_like(needle)}%"
    roles = space_roles(db, user.id)
    visible = or_(Lecture.recorded_by == user.id, Lecture.space_id.in_(list(roles)))
    hits: list[SearchHitOut] = []

    transcript_rows = db.execute(
        select(Transcript, Lecture, Course.title)
        .join(Lecture, Lecture.id == Transcript.lecture_id)
        .outerjoin(Course, Course.id == Lecture.course_id)
        .where(visible, Transcript.is_current.is_(True), Transcript.search_text.like(pattern, escape="\\"))
        .order_by(Lecture.created_at.desc())
        .limit(limit)
    ).all()
    for transcript, lecture, course_title in transcript_rows:
        for seg in transcript.segments:
            if needle in normalize_for_match(seg["text"]):
                hits.append(
                    SearchHitOut(
                        lecture_id=lecture.id,
                        lecture_title=lecture.title,
                        course_title=course_title,
                        source="transcript",
                        snippet=_snippet(seg["text"], needle),
                        segment_id=seg["id"],
                        t_start=seg["start"],
                    )
                )
                break

    note_rows = db.execute(
        select(Note, Lecture, Course.title)
        .join(Lecture, Lecture.id == Note.lecture_id)
        .outerjoin(Course, Course.id == Lecture.course_id)
        .where(visible, Note.is_current.is_(True), Note.search_text.like(pattern, escape="\\"))
        .order_by(Lecture.created_at.desc())
        .limit(limit)
    ).all()
    seen = {(h.lecture_id, h.source) for h in hits}
    for note, lecture, course_title in note_rows:
        if (lecture.id, "notes") in seen:
            continue
        seen.add((lecture.id, "notes"))
        hits.append(
            SearchHitOut(
                lecture_id=lecture.id,
                lecture_title=lecture.title,
                course_title=course_title,
                source="notes",
                snippet=_snippet(note.markdown, needle),
                segment_id=None,
                t_start=None,
            )
        )
    return hits[:limit]
