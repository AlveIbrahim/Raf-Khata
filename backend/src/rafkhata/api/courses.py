"""Courses, their weekly routine and the recorder's teacher-permission confirmation."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from rafkhata.api.deps import DbDep, UserDep, can_edit_course, load_course, space_roles
from rafkhata.api.schemas import CourseIn, CourseOut, CoursePatch, RoutineIn, RoutineSlotIO
from rafkhata.models import Course, CourseConsent, Lecture, RoutineSlot, Space, User

router = APIRouter(prefix="/courses", tags=["courses"])


def course_out(db: Session, course: Course, user: User, roles: dict[uuid.UUID, str]) -> CourseOut:
    space_name = None
    if course.space_id is not None:
        space = db.get(Space, course.space_id)
        space_name = space.name if space else None
    lecture_count = db.scalar(select(func.count()).select_from(Lecture).where(Lecture.course_id == course.id)) or 0
    consent = db.get(CourseConsent, (course.id, user.id)) is not None
    return CourseOut(
        id=course.id,
        title=course.title,
        code=course.code,
        teacher_name=course.teacher_name,
        semester=course.semester,
        space_id=course.space_id,
        space_name=space_name,
        glossary=list(course.glossary or []),
        notes_lang=course.notes_lang,
        archived=course.archived,
        routine=[RoutineSlotIO.model_validate(slot) for slot in course.routine],
        is_owner=course.owner_id == user.id,
        can_edit=can_edit_course(course, user, roles),
        consent_confirmed=consent,
        lecture_count=lecture_count,
        created_at=course.created_at,
        updated_at=course.updated_at,
    )


def _check_space(space_id: uuid.UUID | None, roles: dict[uuid.UUID, str]) -> None:
    if space_id is not None and space_id not in roles:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "section not found")


def _replace_routine(course: Course, slots: list[RoutineSlotIO]) -> None:
    for slot in slots:
        if slot.end_time <= slot.start_time:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "a class must end after it starts")
    course.routine.clear()
    for slot in slots:
        course.routine.append(RoutineSlot(**slot.model_dump()))


@router.get("", response_model=list[CourseOut])
def list_courses(user: UserDep, db: DbDep, include_archived: bool = False) -> list[CourseOut]:
    roles = space_roles(db, user.id)
    stmt = select(Course).where(or_(Course.owner_id == user.id, Course.space_id.in_(list(roles))))
    if not include_archived:
        stmt = stmt.where(Course.archived.is_(False))
    courses = db.scalars(stmt.order_by(Course.title)).all()
    return [course_out(db, c, user, roles) for c in courses]


@router.post("", response_model=CourseOut, status_code=status.HTTP_201_CREATED)
def create_course(body: CourseIn, user: UserDep, db: DbDep) -> CourseOut:
    roles = space_roles(db, user.id)
    _check_space(body.space_id, roles)
    course = Course(
        owner_id=user.id,
        space_id=body.space_id,
        title=body.title.strip(),
        code=body.code,
        teacher_name=body.teacher_name,
        semester=body.semester,
        glossary=body.glossary,
        notes_lang=body.notes_lang,
    )
    db.add(course)
    _replace_routine(course, body.routine)
    db.commit()
    return course_out(db, course, user, roles)


@router.get("/{course_id}", response_model=CourseOut)
def get_course(course_id: uuid.UUID, user: UserDep, db: DbDep) -> CourseOut:
    course = load_course(db, user, course_id)
    return course_out(db, course, user, space_roles(db, user.id))


@router.patch("/{course_id}", response_model=CourseOut)
def patch_course(course_id: uuid.UUID, body: CoursePatch, user: UserDep, db: DbDep) -> CourseOut:
    course = load_course(db, user, course_id, edit=True)
    roles = space_roles(db, user.id)
    changes = body.model_dump(exclude_unset=True)
    if "space_id" in changes:
        _check_space(changes["space_id"], roles)
    for field, value in changes.items():
        if field == "title" and value is None:
            continue
        setattr(course, field, value)
    db.commit()
    return course_out(db, course, user, roles)


@router.delete("/{course_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_course(course_id: uuid.UUID, user: UserDep, db: DbDep) -> None:
    course = load_course(db, user, course_id)
    if course.owner_id != user.id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "only the course owner can delete it")
    db.delete(course)  # lectures keep existing without a course
    db.commit()


@router.put("/{course_id}/routine", response_model=CourseOut)
def put_routine(course_id: uuid.UUID, body: RoutineIn, user: UserDep, db: DbDep) -> CourseOut:
    course = load_course(db, user, course_id, edit=True)
    _replace_routine(course, body.slots)
    db.commit()
    return course_out(db, course, user, space_roles(db, user.id))


@router.post("/{course_id}/consent", response_model=CourseOut)
def confirm_consent(course_id: uuid.UUID, user: UserDep, db: DbDep) -> CourseOut:
    """The user confirms the teacher allowed recording this course."""
    course = load_course(db, user, course_id)
    if db.get(CourseConsent, (course.id, user.id)) is None:
        db.add(CourseConsent(course_id=course.id, user_id=user.id))
        db.commit()
    return course_out(db, course, user, space_roles(db, user.id))
