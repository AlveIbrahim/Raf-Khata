"""Deterministic stand-in for Claude, for development and tests (no network, no cost).

It builds plausible notes straight from the transcript with simple keyword rules, so the app and
the pipeline can be exercised end to end. It does not translate. The output is not meant to be good.
"""

from __future__ import annotations

import re

from rafkhata.pipeline.dates import resolve_due_date
from rafkhata.pipeline.llm.base import LectureMeta, LLMUsage
from rafkhata.pipeline.schemas import (
    MCQ,
    Announcement,
    Bullet,
    ClassQuestion,
    CorrectionResult,
    Definition,
    ExamAlert,
    Flashcard,
    Formula,
    LectureNotes,
    NoteSection,
    Patch,
    Segment,
    StudyPack,
    Transcript,
    WrittenQuestion,
)

# English words commonly written in Bengali script by speech recognition.
TRANSLITERATIONS = {
    "অ্যালগরিদম": "algorithm",
    "ডেডলক": "deadlock",
    "ডেটাবেস": "database",
    "ডাটাবেস": "database",
    "সেমাফোর": "semaphore",
    "প্রসেস": "process",
    "রিসোর্স": "resource",
    "ফাংশন": "function",
    "ভেরিয়েবল": "variable",
}
_EXAM_WORDS = ("পরীক্ষা", "exam", "important", "মনে রাখ", "CT তে", "ইম্পরট্যান্ট")
_DEFINE_WORDS = (" হলো ", " হল ", " মানে ", " means ", " is defined as ")
_ANNOUNCE = {
    "ct": ("CT", "ক্লাস টেস্ট"),
    "quiz": ("quiz", "Quiz", "কুইজ"),
    "assignment": ("assignment", "Assignment", "অ্যাসাইনমেন্ট"),
    "exam": ("midterm", "final", "মিডটার্ম"),
}
_DUE_TEXT = re.compile(r"(আগামী\s+\S+বার|\S+বার|আগামীকাল|পরশু|পরের সপ্তাহ\S*|next\s+\w+|tomorrow|\d+\s*তারিখ|[০-৯]+\s*তারিখ)")
USAGE = LLMUsage(model="fake")


def _first_latin_term(text: str) -> str | None:
    match = re.search(r"[A-Za-z][A-Za-z'\-]+(?:\s+[A-Za-z][A-Za-z'\-]+)?", text)
    return match.group(0) if match else None


class FakeLLM:
    name = "fake"
    model = "fake"

    def correct(self, transcript: Transcript, meta: LectureMeta) -> tuple[CorrectionResult, LLMUsage]:
        patches = []
        for seg in transcript.segments:
            for wrong, right in TRANSLITERATIONS.items():
                if wrong in seg.text:
                    patches.append(Patch(segment_id=seg.id, find=wrong, replace=right, reason="english_word"))
        return CorrectionResult(patches=patches), USAGE

    def notes(self, transcript: Transcript, meta: LectureMeta, lang: str) -> tuple[LectureNotes, LLMUsage]:
        segs = transcript.segments
        teacher = [s for s in segs if s.speaker != "S"]
        title = meta.course_title or (teacher[0].text[:60] if teacher else "Lecture")

        sections = []
        for i in range(0, len(segs), 6):
            chunk = segs[i : i + 6]
            sections.append(
                NoteSection(
                    title=("Part" if lang == "en" else "অংশ") + f" {len(sections) + 1}",
                    start_segment_id=chunk[0].id,
                    bullets=[Bullet(text=s.text, source_segment_ids=[s.id]) for s in chunk if s.speaker != "S"],
                )
            )

        definitions = []
        for s in teacher:
            if any(word in f" {s.text} " for word in _DEFINE_WORDS):
                term = _first_latin_term(s.text) or s.text.split()[0]
                definitions.append(Definition(term=term, explanation=s.text, source_segment_ids=[s.id]))

        formulas = []
        for s in teacher:
            match = re.search(r"([A-Za-z][\w ]*=\s*[A-Za-z0-9][^।]*)", s.text)
            if match:
                latex = match.group(1).strip().replace("−", "-")
                formulas.append(Formula(latex=latex, meaning=s.text, source_segment_ids=[s.id]))

        exam_alerts = [
            ExamAlert(text=s.text, teacher_quote=s.text, source_segment_ids=[s.id])
            for s in teacher
            if any(w in s.text for w in _EXAM_WORDS) and not _announcement_kind(s)
        ]

        announcements = []
        for s in teacher:
            kind = _announcement_kind(s)
            if kind:
                due = _DUE_TEXT.search(s.text)
                due_text = due.group(0) if due else None
                due_date = resolve_due_date(None, due_text, meta.lecture_date) if meta.lecture_date else None
                announcements.append(
                    Announcement(
                        kind=kind,
                        text=s.text,
                        due_date=due_date.isoformat() if due_date else None,
                        due_text=due_text,
                        source_segment_ids=[s.id],
                    )
                )

        questions = []
        for i, s in enumerate(segs):
            if s.speaker == "S":
                answer = next((t for t in segs[i + 1 :] if t.speaker != "S"), None)
                questions.append(
                    ClassQuestion(
                        question=s.text,
                        answer=answer.text if answer else "Not answered",
                        source_segment_ids=[s.id] + ([answer.id] if answer else []),
                    )
                )

        notes = LectureNotes(
            title=title,
            summary=[s.text[:140] for s in teacher[:5]],
            sections=sections,
            definitions=definitions,
            formulas=formulas,
            examples=[],
            exam_alerts=exam_alerts,
            announcements=announcements,
            class_questions=questions,
            unclear_parts=[s.text for s in segs if "[অস্পষ্ট]" in s.text],
        )
        return notes, USAGE

    def study(self, transcript: Transcript, meta: LectureMeta, lang: str) -> tuple[StudyPack, LLMUsage]:
        notes, _ = self.notes(transcript, meta, lang)
        terms = [d.term for d in notes.definitions] or ["lecture"]
        flashcards = [
            Flashcard(front=d.term, back=d.explanation, source_segment_ids=d.source_segment_ids)
            for d in notes.definitions
        ]
        flashcards += [
            Flashcard(front=a.text[:80], back=a.teacher_quote, source_segment_ids=a.source_segment_ids)
            for a in notes.exam_alerts
        ]
        mcqs = []
        for i, d in enumerate(notes.definitions):
            distractors = [t for t in terms if t != d.term][:3]
            distractors += ["None of these", "All of these", "Not discussed"][: 3 - len(distractors)]
            options = distractors[:]
            answer_index = i % 4
            options.insert(answer_index, d.term)
            mcqs.append(
                MCQ(
                    question=f"কোনটির বর্ণনা: {d.explanation[:100]}",
                    options=options[:4],
                    answer_index=answer_index,
                    explanation=d.explanation,
                    source_segment_ids=d.source_segment_ids,
                )
            )
        questions = [
            WrittenQuestion(
                kind="short",
                question=f"{d.term} এর সংজ্ঞা দাও।",
                answer_points=[d.explanation],
                source_segment_ids=d.source_segment_ids,
            )
            for d in notes.definitions[:4]
        ]
        if notes.sections:
            questions.append(
                WrittenQuestion(
                    kind="broad",
                    question=f"{notes.title} উদাহরণসহ ব্যাখ্যা কর।",
                    answer_points=notes.summary[:5],
                    source_segment_ids=[notes.sections[0].start_segment_id],
                )
            )
        return StudyPack(flashcards=flashcards, mcqs=mcqs, questions=questions), USAGE


def _announcement_kind(segment: Segment) -> str | None:
    for kind, words in _ANNOUNCE.items():
        if any(w in segment.text for w in words):
            return kind
    return None
