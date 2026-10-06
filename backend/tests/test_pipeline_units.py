from __future__ import annotations

from datetime import date

import pytest

from rafkhata.pipeline.asr.base import build_segments, make_piece
from rafkhata.pipeline.asr.sarvam import parse_sarvam_output
from rafkhata.pipeline.asr.soniox import tokens_to_pieces
from rafkhata.pipeline.audio import TimeMap
from rafkhata.pipeline.dates import resolve_due_date
from rafkhata.pipeline.glossary import merge_glossary, terms_from_notes
from rafkhata.pipeline.llm.base import LectureMeta, LLMUsage, lecture_context, task_prompt
from rafkhata.pipeline.llm.fake import FakeLLM
from rafkhata.pipeline.metrics import score_text
from rafkhata.pipeline.render import render_html, render_markdown
from rafkhata.pipeline.schemas import (
    Bullet,
    LectureNotes,
    NoteSection,
    Patch,
    Segment,
    Transcript,
)
from rafkhata.pipeline.steps import apply_patches, clean_citations, finalize_transcript
from rafkhata.pricing import asr_cost, llm_cost


def tr(*texts: str, speakers: list[str | None] | None = None) -> Transcript:
    speakers = speakers or ["T"] * len(texts)
    segs = [
        Segment(id=f"s{i + 1:04d}", start=i * 5.0, end=i * 5.0 + 4.0, text=t, speaker=speakers[i])
        for i, t in enumerate(texts)
    ]
    return Transcript(segments=segs, provider="test")


# ---------- transcript post-processing ----------


def test_finalize_maps_times_labels_speakers_and_drops_empty() -> None:
    raw = Transcript(
        provider="x",
        segments=[
            Segment(id="a", start=0.0, end=4.0, text="আজকে  deadlock ।", speaker="spk1"),
            Segment(id="b", start=4.5, end=5.0, text="  ", speaker="spk2"),
            Segment(id="c", start=5.0, end=6.0, text="স্যার একটা প্রশ্ন?", speaker="spk2"),
            Segment(id="d", start=6.5, end=12.0, text="হ্যাঁ বলো।", speaker="spk1"),
        ],
    )
    tmap = TimeMap([(0.0, 0.0, 5.0), (5.0, 30.0, 10.0)])
    out = finalize_transcript(raw, tmap, duration_s=60.0)
    assert [s.id for s in out.segments] == ["s0001", "s0002", "s0003"]
    assert [s.speaker for s in out.segments] == ["T", "S", "T"]
    assert out.segments[0].text == "আজকে deadlock।"
    assert out.segments[1].start == 30.0
    assert out.segments[2].end == 37.0


def test_apply_patches_only_exact_matches() -> None:
    t = tr("আমরা অ্যালগরিদম শিখব", "ডেটাবেস ও ডেটাবেস")
    patches = [
        Patch(segment_id="s0001", find="অ্যালগরিদম", replace="algorithm", reason="english_word"),
        Patch(segment_id="s0002", find="ডেটাবেস", replace="database", reason="english_word"),
        Patch(segment_id="s0002", find="missing", replace="x", reason="other"),
        Patch(segment_id="s9999", find="a", replace="b", reason="other"),
    ]
    fixed, applied, rejected = apply_patches(t, patches)
    assert fixed.segments[0].text == "আমরা algorithm শিখব"
    assert fixed.segments[1].text == "database ও ডেটাবেস"  # first occurrence only
    assert len(applied) == 2 and len(rejected) == 2
    assert t.segments[0].text == "আমরা অ্যালগরিদম শিখব"  # input untouched


def test_clean_citations_drops_unknown_ids() -> None:
    t = tr("a", "b")
    notes = LectureNotes(
        title="x",
        summary=[],
        sections=[
            NoteSection(
                title="t", start_segment_id="s0099", bullets=[Bullet(text="b", source_segment_ids=["s0002", "s0042"])]
            )
        ],
        definitions=[],
        formulas=[],
        examples=[],
        exam_alerts=[],
        announcements=[],
        class_questions=[],
        unclear_parts=[],
    )
    cleaned = clean_citations(notes, t)
    assert cleaned.sections[0].start_segment_id == "s0001"
    assert cleaned.sections[0].bullets[0].source_segment_ids == ["s0002"]


# ---------- dates ----------

TUESDAY = date(2026, 10, 6)


@pytest.mark.parametrize(
    ("iso", "text", "expected"),
    [
        ("2026-10-11", None, date(2026, 10, 11)),
        ("2025-01-01", "আগামী রবিবার", date(2026, 10, 11)),  # implausible ISO ignored
        (None, "আগামী রবিবার", date(2026, 10, 11)),
        (None, "next Sunday", date(2026, 10, 11)),
        # The Bangladeshi week starts on Saturday, so on a Tuesday the coming Sunday is already "next week".
        (None, "পরের সপ্তাহের রবিবার", date(2026, 10, 11)),
        (None, "আগামীকাল", date(2026, 10, 7)),
        (None, "পরশু", date(2026, 10, 8)),
        (None, "১২ তারিখ", date(2026, 10, 12)),
        (None, "3 তারিখ", date(2026, 11, 3)),
        (None, "15 অক্টোবর", date(2026, 10, 15)),
        (None, "March 2", date(2027, 3, 2)),
        (None, "12/11", date(2026, 11, 12)),
        (None, "শীঘ্রই", None),
        (None, None, None),
    ],
)
def test_resolve_due_date(iso, text, expected) -> None:
    assert resolve_due_date(iso, text, TUESDAY) == expected


def test_next_week_from_saturday() -> None:
    saturday = date(2026, 10, 3)
    assert resolve_due_date(None, "আগামী রবিবার", saturday) == date(2026, 10, 4)
    assert resolve_due_date(None, "পরের সপ্তাহের রবিবার", saturday) == date(2026, 10, 11)


# ---------- metrics ----------


def test_score_identical_and_normalized() -> None:
    s = score_text("T: Deadlock এর ৪টা condition আছে।", "deadlock এর 4টা Condition আছে", ["deadlock", "condition"])
    assert s.cer == 0 and s.wer == 0
    assert s.term_recall == 1.0
    assert s.latin_recall == 1.0


def test_score_errors_terms_and_runs() -> None:
    ref = "আজকে আমরা deadlock নিয়ে কথা বলব এবং semaphore দেখব তারপর ছুটি"
    hyp = "আজকে আমরা ডেডলক নিয়ে কথা তারপর ছুটি"
    s = score_text(hyp, ref, ["deadlock", "semaphore"])
    assert s.wer > 0.3
    assert s.terms_total == 2 and s.terms_found == 0
    assert s.latin_recall == 0.0
    assert s.max_deleted_run >= 3  # "বলব এবং semaphore দেখব" skipped
    with pytest.raises(ValueError):
        score_text("x", "  ")


# ---------- ASR output parsing ----------


def test_parse_sarvam_diarized_and_chunks() -> None:
    diarized = {
        "transcript": "...",
        "diarized_transcript": {
            "entries": [
                {"transcript": "আজকে deadlock", "start_time_seconds": 0.5, "end_time_seconds": 3.0, "speaker_id": "0"},
                {"transcript": "প্রশ্ন আছে", "start_time_seconds": 3.5, "end_time_seconds": 5.0, "speaker_id": "1"},
            ]
        },
    }
    assert parse_sarvam_output(diarized)[1] == (3.5, 5.0, "প্রশ্ন আছে", "1")
    chunks = {
        "transcript": "a b",
        "timestamps": {"words": ["a", "b"], "start_time_seconds": [0, 1], "end_time_seconds": [1, 2]},
    }
    assert parse_sarvam_output(chunks) == [(0.0, 1.0, "a", None), (1.0, 2.0, "b", None)]
    assert parse_sarvam_output({"transcript": "only text"}) == [(0.0, 0.0, "only text", None)]
    assert parse_sarvam_output({"transcript": ""}) == []


def test_soniox_tokens_grouped_into_lines() -> None:
    tokens = [
        {"text": "আজকে", "start_ms": 0, "end_ms": 400, "speaker": "1"},
        {"text": " dead", "start_ms": 450, "end_ms": 700, "speaker": "1"},
        {"text": "lock", "start_ms": 700, "end_ms": 900, "speaker": "1"},
        {"text": " পড়ব।", "start_ms": 950, "end_ms": 3500, "speaker": "1"},
        {"text": "স্যার", "start_ms": 3600, "end_ms": 4000, "speaker": "2"},
        {"text": " হ্যাঁ", "start_ms": 6000, "end_ms": 6300, "speaker": "1"},
    ]
    segments = build_segments(tokens_to_pieces(tokens))
    assert [s.text for s in segments] == ["আজকে deadlock পড়ব।", "স্যার", "হ্যাঁ"]
    assert segments[0].start == 0.0 and segments[0].end == 3.5


def test_build_segments_splits_long_lines() -> None:
    pieces = [make_piece(i, i + 0.9, f" w{i}", "A") for i in range(60)]
    segments = build_segments(pieces, max_len_s=25.0)
    assert all(s.end - s.start <= 25.0 for s in segments)
    assert len(segments) >= 3


# ---------- prompts, fake LLM, rendering ----------


def test_lecture_context_layout() -> None:
    t = tr("আজকে deadlock", "প্রশ্ন", speakers=["T", "S"])
    meta = LectureMeta(
        course_title="Operating Systems",
        course_code="CSE 313",
        lecture_date=TUESDAY,
        glossary=["deadlock"],
        bookmarks=[(5.0, "important", None)],
    )
    ctx = lecture_context(t, meta)
    assert "Course: Operating Systems (CSE 313)" in ctx
    assert "Lecture date: 2026-10-06 (Tuesday)" in ctx
    assert "s0002 [00:05] S: প্রশ্ন" in ctx
    assert "[00:05] student marked important" in ctx
    assert "{language_instructions}" not in task_prompt("notes", "bn")
    assert "Bangla" in task_prompt("study", "bn")


def test_fake_llm_and_render() -> None:
    from rafkhata.pipeline.asr.mock import DEFAULT_SCRIPT

    lines = [line.split(":", 1)[1].strip() for line in DEFAULT_SCRIPT]
    speakers = ["S" if line.startswith("S:") else "T" for line in DEFAULT_SCRIPT]
    t = tr(*lines, speakers=speakers)
    meta = LectureMeta(course_title="OS", lecture_date=TUESDAY)
    llm = FakeLLM()

    patches, _ = llm.correct(t, meta)
    assert any(p.replace == "algorithm" for p in patches.patches)

    notes, usage = llm.notes(t, meta, "bn")
    assert usage.model == "fake"
    assert notes.exam_alerts and notes.announcements and notes.class_questions and notes.formulas
    ct = next(a for a in notes.announcements if a.kind == "ct")
    assert ct.due_date == "2026-10-11"

    html = render_html(notes, t, "bn")
    assert html.startswith('<article class="rk-notes" lang="bn">')
    assert "পরীক্ষার জন্য গুরুত্বপূর্ণ" in html
    assert 'href="rafkhata://seek?t=' in html
    assert "\\[" in html  # display maths for KaTeX
    md = render_markdown(notes, t, "en")
    assert "## Important for exams" in md

    study, _ = llm.study(t, meta, "bn")
    assert study.flashcards and study.questions
    assert all(0 <= q.answer_index < len(q.options) for q in study.mcqs)

    assert merge_glossary(["Deadlock", "process"], terms_from_notes(notes))[0] in {d.term for d in notes.definitions}


def test_render_escapes_html() -> None:
    t = tr("x")
    notes = LectureNotes(
        title="<script>alert(1)</script>",
        summary=["a < b & c"],
        sections=[],
        definitions=[],
        formulas=[],
        examples=[],
        exam_alerts=[],
        announcements=[],
        class_questions=[],
        unclear_parts=[],
    )
    html = render_html(notes, t, "en")
    assert "<script>" not in html
    assert "&lt;script&gt;" in html and "a &lt; b &amp; c" in html


def test_merge_glossary() -> None:
    assert merge_glossary(["a", "B"], ["b", "c"], limit=3) == ["b", "c", "a"]


def test_pricing() -> None:
    assert asr_cost("soniox", 3600) == 0.10
    usage = LLMUsage(
        model="claude-opus-5-5", input_tokens=1_000_000, output_tokens=100_000, cache_read_input_tokens=1_000_000
    )
    assert llm_cost(usage) == pytest.approx(4.0 + 2.0 + 0.20)
    assert llm_cost(LLMUsage(model="unknown"), "claude-haiku-4-5") == 0.0
