"""Data shapes shared by the pipeline: transcripts and the structured outputs Claude produces.

The LLM output models double as JSON schemas for structured outputs, so field descriptions are
written as instructions to the model.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Transcripts
# ---------------------------------------------------------------------------


class Segment(BaseModel):
    id: str
    start: float
    end: float
    text: str
    speaker: str | None = None  # provider label before normalization; "T"/"S" after


class Transcript(BaseModel):
    segments: list[Segment]
    provider: str
    model: str | None = None
    language: str | None = None
    duration_s: float | None = None

    def full_text(self) -> str:
        return "\n".join(s.text for s in self.segments)

    def by_id(self) -> dict[str, Segment]:
        return {s.id: s for s in self.segments}

    def to_db(self) -> list[dict[str, Any]]:
        return [s.model_dump() for s in self.segments]

    @classmethod
    def from_db(cls, segments: list[dict[str, Any]], provider: str, model: str | None = None) -> Transcript:
        return cls(segments=[Segment.model_validate(s) for s in segments], provider=provider, model=model)


def segment_id(index: int) -> str:
    return f"s{index + 1:04d}"


# ---------------------------------------------------------------------------
# Transcript correction (Claude returns patches only)
# ---------------------------------------------------------------------------


class Patch(BaseModel):
    segment_id: str = Field(description="Id of the transcript line to fix, e.g. 's0012'.")
    find: str = Field(description="Exact text currently in that line that is wrong (copy it character for character).")
    replace: str = Field(description="Corrected text to put in its place.")
    reason: Literal["term", "english_word", "name", "number", "other"] = Field(
        description="term = misheard technical term; english_word = English word written in Bangla script; "
        "name = person/place name; number = digits/units; other = anything else."
    )


class CorrectionResult(BaseModel):
    patches: list[Patch] = Field(description="Only clear recognition errors. Empty if nothing needs fixing.")


# ---------------------------------------------------------------------------
# Notes
# ---------------------------------------------------------------------------


def citations() -> Any:
    return Field(description="Ids of the transcript lines this item comes from, e.g. ['s0012', 's0013'].")


class Bullet(BaseModel):
    text: str
    source_segment_ids: list[str] = citations()


class NoteSection(BaseModel):
    title: str = Field(description="Topic heading.")
    start_segment_id: str = Field(description="Id of the line where this topic starts.")
    bullets: list[Bullet]


class Definition(BaseModel):
    term: str = Field(description="The term as the teacher said it; English terms in English.")
    explanation: str
    source_segment_ids: list[str] = citations()


class Formula(BaseModel):
    latex: str = Field(description="The formula in LaTeX, without surrounding $ signs.")
    meaning: str = Field(description="What it means and what each symbol stands for.")
    source_segment_ids: list[str] = citations()


class WorkedExample(BaseModel):
    title: str
    steps: list[str]
    source_segment_ids: list[str] = citations()


class ExamAlert(BaseModel):
    text: str = Field(description="What is likely to be examined, in the notes language.")
    teacher_quote: str = Field(description="The teacher's own words from the transcript.")
    source_segment_ids: list[str] = citations()


AnnouncementKind = Literal["ct", "quiz", "assignment", "exam", "presentation", "lab", "class_change", "other"]


class Announcement(BaseModel):
    kind: AnnouncementKind
    text: str = Field(description="What was announced, in the notes language.")
    due_date: str | None = Field(
        description="ISO date YYYY-MM-DD if the date can be worked out from the lecture date; otherwise null."
    )
    due_text: str | None = Field(description="The date or time exactly as said, e.g. 'আগামী রবিবার'.")
    source_segment_ids: list[str] = citations()


class ClassQuestion(BaseModel):
    question: str
    answer: str = Field(description="The answer given in class. Write 'উত্তর দেওয়া হয়নি' / 'Not answered' if none.")
    source_segment_ids: list[str] = citations()


class LectureNotes(BaseModel):
    title: str = Field(description="Short title for the lecture.")
    summary: list[str] = Field(description="3-7 bullet summary of the whole lecture.")
    sections: list[NoteSection] = Field(description="Detailed notes, one section per topic, in lecture order.")
    definitions: list[Definition]
    formulas: list[Formula]
    examples: list[WorkedExample]
    exam_alerts: list[ExamAlert] = Field(
        description="Things the teacher flagged as important or likely in a CT/exam. Empty if none."
    )
    announcements: list[Announcement] = Field(description="CT, quiz, assignment, exam or schedule announcements.")
    class_questions: list[ClassQuestion] = Field(description="Questions students asked in class.")
    unclear_parts: list[str] = Field(description="Places where the transcript is too unclear to take notes from.")


# ---------------------------------------------------------------------------
# Study pack
# ---------------------------------------------------------------------------


class Flashcard(BaseModel):
    front: str
    back: str
    source_segment_ids: list[str] = citations()


class MCQ(BaseModel):
    question: str
    options: list[str] = Field(description="Exactly 4 options.")
    answer_index: int = Field(description="0-based index of the correct option.")
    explanation: str
    source_segment_ids: list[str] = citations()


class WrittenQuestion(BaseModel):
    kind: Literal["short", "broad"]
    question: str = Field(description="Bangladeshi exam style, e.g. 'সংজ্ঞা দাও', 'পার্থক্য লেখ', 'ব্যাখ্যা কর'.")
    answer_points: list[str] = Field(description="Key points a full-mark answer must contain.")
    source_segment_ids: list[str] = citations()


class StudyPack(BaseModel):
    flashcards: list[Flashcard]
    mcqs: list[MCQ]
    questions: list[WrittenQuestion]
