"""Upcoming CTs, assignments and quizzes announced in lectures."""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Annotated
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Query
from sqlalchemy import or_, select

from rafkhata.api.deps import DbDep, SettingsDep, UserDep, space_roles
from rafkhata.api.schemas import DeadlineOut
from rafkhata.db import utcnow
from rafkhata.models import Course, Deadline, Lecture

router = APIRouter(tags=["deadlines"])


@router.get("/deadlines", response_model=list[DeadlineOut])
def list_deadlines(
    user: UserDep,
    db: DbDep,
    settings: SettingsDep,
    include_past: Annotated[bool, Query()] = False,
) -> list[DeadlineOut]:
    roles = space_roles(db, user.id)
    today = datetime.now(ZoneInfo(settings.default_timezone)).date()
    stmt = (
        select(Deadline, Lecture, Course.title)
        .join(Lecture, Lecture.id == Deadline.lecture_id)
        .outerjoin(Course, Course.id == Deadline.course_id)
        .where(or_(Lecture.recorded_by == user.id, Lecture.space_id.in_(list(roles))))
    )
    if not include_past:
        # Dated items from today on; undated items only from lectures in the last two weeks.
        stmt = stmt.where(
            or_(
                Deadline.due_date >= today,
                Deadline.due_date.is_(None) & (Lecture.created_at >= utcnow() - timedelta(days=14)),
            )
        )
    rows = db.execute(stmt).all()
    rows.sort(key=lambda r: (r[0].due_date is None, r[0].due_date or today, r[0].created_at))
    return [
        DeadlineOut(
            id=d.id,
            lecture_id=d.lecture_id,
            course_id=d.course_id,
            course_title=course_title,
            kind=d.kind,
            title=d.title,
            due_date=d.due_date,
            due_text=d.due_text,
            lecture_started_at=lecture.started_at,
        )
        for d, lecture, course_title in rows
    ]
