"""Database-backed processing jobs: process a lecture end to end, regenerate notes in another language.

Each step stores its result before the next starts, so a retried job resumes where it stopped and
paid steps (speech recognition, Claude) are not repeated.
"""

from __future__ import annotations

import json
import logging
import shutil
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session, sessionmaker

from rafkhata import jobs
from rafkhata.config import Settings
from rafkhata.db import utcnow
from rafkhata.models import (
    LECTURE_PROCESSING,
    LECTURE_READY,
    Bookmark,
    CostEntry,
    Course,
    Deadline,
    Device,
    Lecture,
    LectureSegment,
    Note,
    SpaceMember,
    StudyPack,
)
from rafkhata.models import Transcript as TranscriptRow
from rafkhata.notify import Notifier, make_notifier
from rafkhata.pipeline.asr import ASRProvider, make_asr
from rafkhata.pipeline.audio import encode_playback
from rafkhata.pipeline.dates import resolve_due_date
from rafkhata.pipeline.glossary import merge_glossary, terms_from_notes
from rafkhata.pipeline.llm import LectureMeta, LLMUsage, NotesLLM, make_llm
from rafkhata.pipeline.normalize import normalize_for_match
from rafkhata.pipeline.render import notes_search_text, render_html, render_markdown
from rafkhata.pipeline.schemas import LectureNotes, Transcript
from rafkhata.pipeline.steps import correct_transcript, generate_notes, generate_study, prepare_audio, transcribe
from rafkhata.pricing import asr_cost, llm_cost
from rafkhata.storage import Storage, asr_raw_key, audio_key, segments_prefix

log = logging.getLogger(__name__)


class PermanentError(RuntimeError):
    permanent = True


@dataclass
class PipelineContext:
    settings: Settings
    sessionmaker: sessionmaker[Session]
    storage: Storage
    llm: NotesLLM
    notifier: Notifier
    asr_factory: Callable[[str, Settings], ASRProvider] = field(default=make_asr)


def make_context(settings: Settings, factory: sessionmaker[Session], storage: Storage) -> PipelineContext:
    return PipelineContext(
        settings=settings,
        sessionmaker=factory,
        storage=storage,
        llm=make_llm(settings.llm_provider, settings),
        notifier=make_notifier(settings),
    )


def asr_providers(settings: Settings) -> list[str]:
    names = [settings.asr_primary]
    if settings.asr_fallback not in ("none", settings.asr_primary):
        names.append(settings.asr_fallback)
    return names


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def _step(db: Session, lecture: Lecture, name: str, progress: int) -> None:
    lecture.status = LECTURE_PROCESSING
    lecture.status_detail = name
    lecture.progress = progress
    db.commit()


def _cost(db: Session, lecture: Lecture, step: str, provider: str, model: str | None, units: dict, usd: float) -> None:
    db.add(CostEntry(lecture_id=lecture.id, step=step, provider=provider, model=model, units=units, usd=usd))
    lecture.cost_usd = round((lecture.cost_usd or 0.0) + usd, 6)


def _llm_cost(db: Session, ctx: PipelineContext, lecture: Lecture, step: str, usage: LLMUsage) -> None:
    _cost(db, lecture, step, ctx.llm.name, usage.model, usage.model_dump(), llm_cost(usage, ctx.llm.model))


def lecture_meta(db: Session, lecture: Lecture, settings: Settings) -> LectureMeta:
    course = db.get(Course, lecture.course_id) if lecture.course_id else None
    started = lecture.started_at or lecture.created_at
    bookmarks = db.scalars(
        select(Bookmark).where(Bookmark.lecture_id == lecture.id, Bookmark.user_id == lecture.recorded_by)
    ).all()
    return LectureMeta(
        course_title=course.title if course else lecture.title,
        course_code=(course.code or "") if course else "",
        teacher=(course.teacher_name or "") if course else "",
        lecture_date=started.astimezone(ZoneInfo(settings.default_timezone)).date(),
        glossary=list(course.glossary or []) if course else [],
        bookmarks=[(b.t_offset_s, b.kind, b.text) for b in bookmarks],
    )


def _transcript_row(db: Session, lecture_id: uuid.UUID, kind: str | None = None) -> TranscriptRow | None:
    stmt = select(TranscriptRow).where(TranscriptRow.lecture_id == lecture_id)
    if kind is None:
        stmt = stmt.where(TranscriptRow.is_current.is_(True))
    else:
        stmt = stmt.where(TranscriptRow.kind == kind)
    return db.scalars(stmt.order_by(TranscriptRow.version.desc())).first()


def _as_transcript(row: TranscriptRow) -> Transcript:
    return Transcript.from_db(row.segments, row.provider, row.model)


def _search_text(transcript: Transcript) -> str:
    return normalize_for_match(" ".join(s.text for s in transcript.segments))


def _next_version(db: Session, model: Any, lecture_id: uuid.UUID, lang: str) -> int:
    current = db.scalar(select(func.max(model.version)).where(model.lecture_id == lecture_id, model.lang == lang))
    return (current or 0) + 1


# ---------------------------------------------------------------------------
# notes + study for one language
# ---------------------------------------------------------------------------


def write_notes_and_study(
    ctx: PipelineContext,
    db: Session,
    lecture: Lecture,
    transcript: Transcript,
    meta: LectureMeta,
    lang: str,
    *,
    force: bool = False,
    progress: tuple[int, int] = (60, 85),
) -> LectureNotes:
    note = db.scalars(
        select(Note).where(Note.lecture_id == lecture.id, Note.lang == lang, Note.is_current.is_(True))
    ).first()
    if note is None or force:
        if lecture.status != LECTURE_READY:
            _step(db, lecture, "notes", progress[0])
        notes, usage = generate_notes(ctx.llm, transcript, meta, lang)
        _llm_cost(db, ctx, lecture, f"notes_{lang}", usage)
        markdown = render_markdown(notes, transcript, lang)
        for old in db.scalars(select(Note).where(Note.lecture_id == lecture.id, Note.lang == lang)).all():
            old.is_current = False
        note = Note(
            lecture_id=lecture.id,
            lang=lang,
            version=_next_version(db, Note, lecture.id, lang),
            model=usage.model,
            content=notes.model_dump(),
            html=render_html(notes, transcript, lang),
            markdown=markdown,
            search_text=notes_search_text(markdown),
            is_current=True,
        )
        db.add(note)
        db.commit()
    notes = LectureNotes.model_validate(note.content)

    pack = db.scalars(
        select(StudyPack).where(
            StudyPack.lecture_id == lecture.id, StudyPack.lang == lang, StudyPack.is_current.is_(True)
        )
    ).first()
    if pack is None or force:
        if lecture.status != LECTURE_READY:
            _step(db, lecture, "study", progress[1])
        study, usage = generate_study(ctx.llm, transcript, meta, lang)
        _llm_cost(db, ctx, lecture, f"study_{lang}", usage)
        for old in db.scalars(
            select(StudyPack).where(StudyPack.lecture_id == lecture.id, StudyPack.lang == lang)
        ).all():
            old.is_current = False
        db.add(
            StudyPack(
                lecture_id=lecture.id,
                lang=lang,
                version=_next_version(db, StudyPack, lecture.id, lang),
                model=usage.model,
                content=study.model_dump(),
                is_current=True,
            )
        )
        db.commit()
    return notes


def index_deadlines(db: Session, lecture: Lecture, notes: LectureNotes, meta: LectureMeta) -> None:
    db.execute(delete(Deadline).where(Deadline.lecture_id == lecture.id))
    for a in notes.announcements:
        db.add(
            Deadline(
                lecture_id=lecture.id,
                course_id=lecture.course_id,
                kind=a.kind,
                title=a.text[:500],
                due_date=resolve_due_date(a.due_date, a.due_text, meta.lecture_date),
                due_text=(a.due_text or None) and a.due_text[:200],
                source_segment_ids=a.source_segment_ids,
            )
        )
    db.commit()


def update_course_glossary(db: Session, lecture: Lecture, notes: LectureNotes) -> None:
    if lecture.course_id is None:
        return
    course = db.get(Course, lecture.course_id)
    if course is not None:
        course.glossary = merge_glossary(list(course.glossary or []), terms_from_notes(notes))
        db.commit()


def notify_ready(ctx: PipelineContext, db: Session, lecture: Lecture) -> None:
    user_ids = {lecture.recorded_by}
    if lecture.space_id is not None:
        user_ids |= set(db.scalars(select(SpaceMember.user_id).where(SpaceMember.space_id == lecture.space_id)))
    devices = db.scalars(select(Device).where(Device.user_id.in_(user_ids))).all()
    if not devices:
        return
    course = db.get(Course, lecture.course_id) if lecture.course_id else None
    title = "Notes ready" if lecture.notes_lang == "en" else "নোট তৈরি ✓"
    body = " · ".join(x for x in (course.title if course else None, lecture.title) if x) or "Lecture"
    try:
        invalid = ctx.notifier.send(
            [d.fcm_token for d in devices], title, body, {"type": "notes_ready", "lecture_id": str(lecture.id)}
        )
    except Exception:  # notifications are best-effort
        log.exception("push notification failed")
        return
    if invalid:
        db.execute(delete(Device).where(Device.fcm_token.in_(invalid)))
        db.commit()


# ---------------------------------------------------------------------------
# jobs
# ---------------------------------------------------------------------------


def process_lecture(ctx: PipelineContext, lecture_id: uuid.UUID) -> None:
    s = ctx.settings
    work = Path(s.work_dir) / str(lecture_id)
    with ctx.sessionmaker() as db:
        lecture = db.get(Lecture, lecture_id)
        if lecture is None:
            log.info("lecture %s was deleted; nothing to do", lecture_id)
            return
        meta = lecture_meta(db, lecture, s)
        try:
            raw = _transcript_row(db, lecture.id, kind="raw")
            if raw is None:
                raw = _assemble_and_transcribe(ctx, db, lecture, meta, work)

            corrected = _transcript_row(db, lecture.id, kind="corrected")
            if corrected is None:
                _step(db, lecture, "correct", 45)
                fixed, applied, rejected, usage = correct_transcript(ctx.llm, _as_transcript(raw), meta)
                _llm_cost(db, ctx, lecture, "correct", usage)
                raw.is_current = False
                corrected = TranscriptRow(
                    lecture_id=lecture.id,
                    version=raw.version + 1,
                    kind="corrected",
                    provider=raw.provider,
                    model=raw.model,
                    is_current=True,
                    language=raw.language,
                    segments=fixed.to_db(),
                    search_text=_search_text(fixed),
                )
                db.add(corrected)
                lecture.pipeline_state = {
                    **(lecture.pipeline_state or {}),
                    "patches": {"applied": len(applied), "rejected": len(rejected)},
                }
                db.commit()

            current = _transcript_row(db, lecture.id) or corrected
            notes = write_notes_and_study(ctx, db, lecture, _as_transcript(current), meta, lecture.notes_lang)
            index_deadlines(db, lecture, notes, meta)
            update_course_glossary(db, lecture, notes)

            lecture.status = LECTURE_READY
            lecture.status_detail = None
            lecture.progress = 100
            lecture.error = None
            lecture.processed_at = utcnow()
            jobs.enqueue(
                db,
                jobs.PURGE_SEGMENTS,
                lecture_id=lecture.id,
                run_after=utcnow() + timedelta(days=s.raw_audio_retention_days),
            )
            db.commit()
            notify_ready(ctx, db, lecture)
        finally:
            shutil.rmtree(work, ignore_errors=True)


def _assemble_and_transcribe(
    ctx: PipelineContext, db: Session, lecture: Lecture, meta: LectureMeta, work: Path
) -> TranscriptRow:
    s = ctx.settings
    _step(db, lecture, "assemble", 5)
    segments = db.scalars(
        select(LectureSegment)
        .where(LectureSegment.lecture_id == lecture.id, LectureSegment.uploaded_at.is_not(None))
        .order_by(LectureSegment.idx)
    ).all()
    if not segments:
        raise PermanentError("no uploaded audio for this lecture")
    paths = []
    for seg in segments:
        dest = work / f"seg_{seg.idx:04d}.aac"
        ctx.storage.download_file(seg.storage_key, dest)
        paths.append(dest)

    audio = prepare_audio(paths, work / "prep", trim=s.vad_trim, min_gap_s=s.vad_trim_min_gap_s)
    playback = work / "audio.m4a"
    encode_playback(audio.full_wav, playback)
    ctx.storage.put_file(audio_key(lecture.id), playback, "audio/mp4")
    lecture.audio_key = audio_key(lecture.id)
    lecture.audio_duration_s = round(audio.duration_s, 2)
    lecture.quality = audio.quality
    _step(db, lecture, "transcribe", 15)

    transcript, result, provider = transcribe(s, audio, asr_providers(s), meta, ctx.asr_factory)
    ctx.storage.put_bytes(
        asr_raw_key(lecture.id, provider),
        json.dumps(result.raw, ensure_ascii=False, default=str).encode(),
        "application/json",
    )
    _cost(
        db,
        lecture,
        "asr",
        provider,
        transcript.model,
        {"billed_seconds": round(result.billed_seconds, 1), "audio_seconds": round(audio.duration_s, 1)},
        asr_cost(provider, result.billed_seconds),
    )
    raw = TranscriptRow(
        lecture_id=lecture.id,
        version=1,
        kind="raw",
        provider=provider,
        model=transcript.model,
        is_current=True,
        language=transcript.language,
        segments=transcript.to_db(),
        search_text=_search_text(transcript),
    )
    db.add(raw)
    db.commit()
    return raw


def regenerate_notes(ctx: PipelineContext, lecture_id: uuid.UUID, lang: str) -> None:
    with ctx.sessionmaker() as db:
        lecture = db.get(Lecture, lecture_id)
        if lecture is None:
            return
        if lecture.status != LECTURE_READY:
            raise RuntimeError("lecture is not processed yet")
        row = _transcript_row(db, lecture.id)
        if row is None:
            raise PermanentError("lecture has no transcript")
        meta = lecture_meta(db, lecture, ctx.settings)
        notes = write_notes_and_study(ctx, db, lecture, _as_transcript(row), meta, lang, force=True)
        if lang == lecture.notes_lang:
            index_deadlines(db, lecture, notes, meta)


def purge_segments(ctx: PipelineContext, lecture_id: uuid.UUID) -> None:
    """Delete the uploaded 5-minute segments once the retention period has passed (playback audio stays)."""
    ctx.storage.delete_prefix(segments_prefix(lecture_id))
