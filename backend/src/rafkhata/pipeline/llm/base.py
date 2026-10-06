"""LLM interface for the three text tasks, and the prompt text they share."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from functools import cache
from importlib import resources
from typing import Protocol

from pydantic import BaseModel

from rafkhata.pipeline.schemas import CorrectionResult, LectureNotes, StudyPack, Transcript


class LLMError(RuntimeError):
    def __init__(self, message: str, *, permanent: bool = False) -> None:
        super().__init__(message)
        self.permanent = permanent


class LLMRefusal(LLMError):
    """The model declined (safety classifier), even after server-side fallback."""

    def __init__(self, message: str) -> None:
        super().__init__(message, permanent=True)


class LLMUsage(BaseModel):
    model: str
    input_tokens: int = 0
    output_tokens: int = 0
    cache_creation_input_tokens: int = 0
    cache_read_input_tokens: int = 0


@dataclass
class LectureMeta:
    course_title: str = ""
    course_code: str = ""
    teacher: str = ""
    lecture_date: date | None = None
    glossary: list[str] = field(default_factory=list)
    # (seconds into the recording, kind, optional text)
    bookmarks: list[tuple[float, str, str | None]] = field(default_factory=list)


class NotesLLM(Protocol):
    name: str
    model: str

    def correct(self, transcript: Transcript, meta: LectureMeta) -> tuple[CorrectionResult, LLMUsage]: ...

    def notes(self, transcript: Transcript, meta: LectureMeta, lang: str) -> tuple[LectureNotes, LLMUsage]: ...

    def study(self, transcript: Transcript, meta: LectureMeta, lang: str) -> tuple[StudyPack, LLMUsage]: ...


# ---------------------------------------------------------------------------
# Prompt text
# ---------------------------------------------------------------------------

LANGUAGE_INSTRUCTIONS = {
    "bn": "Write in Bangla (বাংলা, Bengali script). Keep technical terms in English (Latin script), "
    "e.g. 'deadlock হলো এমন একটি অবস্থা…'.",
    "en": "Write in clear, simple English. Translate the Bangla explanations; keep the teacher's examples.",
    "mixed": "Write the way the teacher spoke: Bangla sentences in Bengali script with English technical terms "
    "in Latin script (Banglish), e.g. 'Deadlock হয় যখন process গুলো একে অপরের resource এর জন্য wait করে।'",
}

_BOOKMARK_LABELS = {"important": "marked important", "confused": "marked as confusing", "note": "added a note"}


@cache
def load_prompt(name: str) -> str:
    return resources.files("rafkhata.pipeline.prompts").joinpath(f"{name}.md").read_text(encoding="utf-8")


def format_time(seconds: float) -> str:
    seconds = max(0, int(seconds))
    h, rest = divmod(seconds, 3600)
    m, s = divmod(rest, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def transcript_lines(transcript: Transcript) -> str:
    return "\n".join(
        f"{seg.id} [{format_time(seg.start)}] {seg.speaker or 'T'}: {seg.text}" for seg in transcript.segments
    )


def lecture_context(transcript: Transcript, meta: LectureMeta) -> str:
    """The large, stable block sent before the task. It is cached and reused across the notes and study calls."""
    details = []
    if meta.course_title or meta.course_code:
        details.append(f"Course: {meta.course_title} {f'({meta.course_code})' if meta.course_code else ''}".strip())
    if meta.teacher:
        details.append(f"Teacher: {meta.teacher}")
    if meta.lecture_date:
        details.append(f"Lecture date: {meta.lecture_date.isoformat()} ({meta.lecture_date.strftime('%A')})")
    if meta.glossary:
        details.append("Course glossary: " + ", ".join(meta.glossary[:300]))
    parts = ["<lecture_details>\n" + ("\n".join(details) or "(none)") + "\n</lecture_details>"]
    if meta.bookmarks:
        marks = "\n".join(
            f"[{format_time(t)}] student {_BOOKMARK_LABELS.get(kind, kind)}{f': {text}' if text else ''}"
            for t, kind, text in sorted(meta.bookmarks)
        )
        parts.append(f"<student_bookmarks>\n{marks}\n</student_bookmarks>")
    parts.append(f"<transcript>\n{transcript_lines(transcript)}\n</transcript>")
    return "\n\n".join(parts)


def task_prompt(name: str, lang: str | None = None) -> str:
    text = load_prompt(name)
    if lang is not None:
        text = text.replace("{language_instructions}", LANGUAGE_INSTRUCTIONS[lang])
    return text
