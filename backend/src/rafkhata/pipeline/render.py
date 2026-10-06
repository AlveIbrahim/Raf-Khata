"""Render notes JSON as an HTML fragment (shown in the app) and as Markdown (shared/exported)."""

from __future__ import annotations

from jinja2 import Environment, PackageLoader, StrictUndefined, select_autoescape

from rafkhata.pipeline.llm.base import format_time
from rafkhata.pipeline.normalize import normalize_for_match
from rafkhata.pipeline.schemas import LectureNotes, Transcript

HEADINGS = {
    "bn": {
        "summary": "সারসংক্ষেপ",
        "exam_alerts": "পরীক্ষার জন্য গুরুত্বপূর্ণ",
        "announcements": "ঘোষণা ও ডেডলাইন",
        "definitions": "সংজ্ঞা ও পরিভাষা",
        "formulas": "সূত্র",
        "examples": "উদাহরণ",
        "class_questions": "ক্লাসের প্রশ্নোত্তর",
        "unclear": "অস্পষ্ট অংশ",
    },
    "en": {
        "summary": "Summary",
        "exam_alerts": "Important for exams",
        "announcements": "Announcements & deadlines",
        "definitions": "Definitions & terms",
        "formulas": "Formulas",
        "examples": "Worked examples",
        "class_questions": "Questions asked in class",
        "unclear": "Unclear parts",
    },
}
HEADINGS["mixed"] = HEADINGS["bn"]

KINDS = {
    "bn": {
        "ct": "CT",
        "quiz": "কুইজ",
        "assignment": "অ্যাসাইনমেন্ট",
        "exam": "পরীক্ষা",
        "presentation": "প্রেজেন্টেশন",
        "lab": "ল্যাব",
        "class_change": "ক্লাসের পরিবর্তন",
        "other": "অন্যান্য",
    },
    "en": {
        "ct": "CT",
        "quiz": "Quiz",
        "assignment": "Assignment",
        "exam": "Exam",
        "presentation": "Presentation",
        "lab": "Lab",
        "class_change": "Class change",
        "other": "Other",
    },
}
KINDS["mixed"] = KINDS["bn"]

_env = Environment(
    loader=PackageLoader("rafkhata.pipeline", "templates"),
    autoescape=select_autoescape(["html", "j2"], default_for_string=True, default=True),
    undefined=StrictUndefined,
    trim_blocks=False,
)


def _first_time(transcript: Transcript):
    starts = {s.id: s.start for s in transcript.segments}

    def first_time(ids: list[str]) -> float | None:
        for i in ids or []:
            if i in starts:
                return starts[i]
        return None

    return first_time


def render_html(notes: LectureNotes, transcript: Transcript, lang: str) -> str:
    template = _env.get_template("notes.html.j2")
    return template.render(
        notes=notes,
        h=HEADINGS[lang],
        kinds=KINDS[lang],
        html_lang="en" if lang == "en" else "bn",
        first_time=_first_time(transcript),
        fmt=format_time,
    ).strip()


def render_markdown(notes: LectureNotes, transcript: Transcript, lang: str) -> str:
    h = HEADINGS[lang]
    kinds = KINDS[lang]
    first_time = _first_time(transcript)

    def ts(ids: list[str]) -> str:
        t = first_time(ids)
        return f" [{format_time(t)}]" if t is not None else ""

    out = [f"# {notes.title}", ""]
    if notes.summary:
        out += [f"## {h['summary']}", *[f"- {s}" for s in notes.summary], ""]
    if notes.exam_alerts:
        out.append(f"## {h['exam_alerts']}")
        for a in notes.exam_alerts:
            out.append(f"- **{a.text}**{ts(a.source_segment_ids)}")
            if a.teacher_quote:
                out.append(f"  > {a.teacher_quote}")
        out.append("")
    if notes.announcements:
        out.append(f"## {h['announcements']}")
        for a in notes.announcements:
            due = " · ".join(x for x in (a.due_date, a.due_text) if x)
            out.append(
                f"- **{kinds.get(a.kind, a.kind)}:** {a.text}{f' ({due})' if due else ''}{ts(a.source_segment_ids)}"
            )
        out.append("")
    for s in notes.sections:
        out.append(f"## {s.title}{ts([s.start_segment_id])}")
        out += [f"- {b.text}" for b in s.bullets]
        out.append("")
    if notes.definitions:
        out.append(f"## {h['definitions']}")
        out += [f"- **{d.term}:** {d.explanation}" for d in notes.definitions]
        out.append("")
    if notes.formulas:
        out.append(f"## {h['formulas']}")
        for f in notes.formulas:
            out += [f"$$ {f.latex} $$", f"{f.meaning}", ""]
    if notes.examples:
        out.append(f"## {h['examples']}")
        for e in notes.examples:
            out.append(f"### {e.title}")
            out += [f"{i}. {step}" for i, step in enumerate(e.steps, 1)]
            out.append("")
    if notes.class_questions:
        out.append(f"## {h['class_questions']}")
        for q in notes.class_questions:
            out += [f"- **{q.question}**", f"  {q.answer}"]
        out.append("")
    if notes.unclear_parts:
        out.append(f"## {h['unclear']}")
        out += [f"- {u}" for u in notes.unclear_parts]
        out.append("")
    return "\n".join(out).strip() + "\n"


def notes_search_text(markdown: str) -> str:
    return normalize_for_match(markdown)
