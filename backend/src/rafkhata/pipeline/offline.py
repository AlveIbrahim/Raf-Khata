"""`rk process-file`: run the whole pipeline on one audio file and write the results to a folder.

No database or storage needed. Useful to try providers on your own recordings and to compare them
with `rk score`.
"""

from __future__ import annotations

import json
import tempfile
from datetime import date
from pathlib import Path

from rafkhata.config import get_settings
from rafkhata.pipeline.llm import LectureMeta, LLMUsage, make_llm
from rafkhata.pipeline.llm.base import format_time
from rafkhata.pipeline.render import render_html, render_markdown
from rafkhata.pipeline.schemas import Transcript
from rafkhata.pipeline.steps import correct_transcript, generate_notes, generate_study, prepare_audio, transcribe
from rafkhata.pricing import asr_cost, llm_cost

STANDALONE_HTML = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/KaTeX/0.16.22/katex.min.css">
<script defer src="https://cdnjs.cloudflare.com/ajax/libs/KaTeX/0.16.22/katex.min.js"></script>
<script defer src="https://cdnjs.cloudflare.com/ajax/libs/KaTeX/0.16.22/contrib/auto-render.min.js"
  onload="renderMathInElement(document.body,{{delimiters:[
    {{left:'\\\\[',right:'\\\\]',display:true}},{{left:'\\\\(',right:'\\\\)',display:false}}]}})"></script>
<style>
body{{font-family:'Noto Sans Bengali','Hind Siliguri',system-ui,sans-serif;max-width:760px;margin:2rem auto;
  padding:0 1rem;line-height:1.6;color:#1d2433}}
h1{{font-size:1.6rem}} h2{{font-size:1.2rem;margin-top:1.6rem;border-bottom:1px solid #dde3ee}}
.ts{{font-size:.75rem;color:#5b6b8c;text-decoration:none;margin-left:.3rem}}
.rk-exam-alerts .rk-alert{{background:#fff4e5;border-left:4px solid #e08a00;padding:.4rem .8rem;margin:.5rem 0}}
blockquote{{margin:.2rem 0;color:#6b5a3a}} dt{{font-weight:600}} dd{{margin:0 0 .6rem 0}}
</style></head><body>
{body}
</body></html>
"""


def _write_transcript(transcript: Transcript, out_dir: Path, name: str) -> None:
    (out_dir / f"{name}.json").write_text(transcript.model_dump_json(indent=2), encoding="utf-8")
    lines = [f"[{format_time(s.start)}] {s.speaker or 'T'}: {s.text}" for s in transcript.segments]
    (out_dir / f"{name}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def process_file(
    *,
    audio: Path,
    out_dir: Path,
    asr: str | None,
    llm: str | None,
    lang: str,
    glossary_file: Path | None,
    course_title: str,
    study: bool,
) -> int:
    settings = get_settings()
    asr_name = asr or settings.asr_primary
    llm_name = llm or settings.llm_provider
    glossary = []
    if glossary_file:
        glossary = [line.strip() for line in glossary_file.read_text(encoding="utf-8").splitlines() if line.strip()]
    meta = LectureMeta(course_title=course_title, lecture_date=date.today(), glossary=glossary)
    out_dir.mkdir(parents=True, exist_ok=True)
    costs: list[dict] = []

    def add_llm_cost(step: str, usage: LLMUsage) -> None:
        costs.append(
            {"step": step, "model": usage.model, **usage.model_dump(), "usd": llm_cost(usage, settings.llm_model)}
        )

    with tempfile.TemporaryDirectory() as tmp:
        prepared = prepare_audio([audio], Path(tmp), trim=settings.vad_trim, min_gap_s=settings.vad_trim_min_gap_s)
        print(
            f"audio: {format_time(prepared.duration_s)} · sent to ASR: {format_time(prepared.asr_duration_s)} "
            f"· quality score {prepared.quality['score']} (SNR {prepared.quality['snr_db']} dB)"
        )
        transcript, result, provider = transcribe(settings, prepared, [asr_name], meta)
    costs.append(
        {
            "step": "asr",
            "provider": provider,
            "seconds": result.billed_seconds,
            "usd": asr_cost(provider, result.billed_seconds),
        }
    )
    _write_transcript(transcript, out_dir, "transcript.raw")
    (out_dir / f"asr.{provider}.json").write_text(
        json.dumps(result.raw, ensure_ascii=False, indent=2, default=str), encoding="utf-8"
    )
    print(f"transcript: {len(transcript.segments)} lines from {provider}")

    client = make_llm(llm_name, settings)
    corrected, applied, rejected, usage = correct_transcript(client, transcript, meta)
    add_llm_cost("correct", usage)
    _write_transcript(corrected, out_dir, "transcript")
    (out_dir / "patches.json").write_text(
        json.dumps(
            {"applied": [p.model_dump() for p in applied], "rejected": [p.model_dump() for p in rejected]},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"correction: {len(applied)} fixes applied, {len(rejected)} rejected")

    notes, usage = generate_notes(client, corrected, meta, lang)
    add_llm_cost("notes", usage)
    (out_dir / "notes.json").write_text(notes.model_dump_json(indent=2), encoding="utf-8")
    (out_dir / "notes.md").write_text(render_markdown(notes, corrected, lang), encoding="utf-8")
    html = STANDALONE_HTML.format(title=notes.title, body=render_html(notes, corrected, lang))
    (out_dir / "notes.html").write_text(html, encoding="utf-8")
    print(f"notes: {notes.title!r}, {len(notes.sections)} sections, {len(notes.exam_alerts)} exam alerts")

    if study:
        pack, usage = generate_study(client, corrected, meta, lang)
        add_llm_cost("study", usage)
        (out_dir / "study.json").write_text(pack.model_dump_json(indent=2), encoding="utf-8")
        print(f"study pack: {len(pack.flashcards)} flashcards, {len(pack.mcqs)} MCQs, {len(pack.questions)} questions")

    total = round(sum(c["usd"] for c in costs), 4)
    (out_dir / "cost.json").write_text(json.dumps({"total_usd": total, "items": costs}, indent=2), encoding="utf-8")
    print(f"cost: ${total} · results in {out_dir}/")
    return 0
