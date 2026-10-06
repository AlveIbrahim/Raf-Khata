"""Transcript accuracy: CER, WER, technical-term recall, English-in-Latin recall, skipped/invented runs.

Both texts go through `normalize_for_match`, as in the riverbornai Bangla benchmark, so punctuation,
joiners, Bangla vs ASCII digits and Latin letter case don't count as errors.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path

import jiwer

from rafkhata.pipeline.normalize import latin_tokens, normalize_for_match


@dataclass
class Score:
    cer: float
    wer: float
    ref_words: int
    substitutions: int
    deletions: int
    insertions: int
    max_deleted_run: int  # longest stretch of reference words missing: skipped audio
    max_inserted_run: int  # longest stretch of extra words: hallucination or repetition loops
    term_recall: float | None
    terms_found: int
    terms_total: int
    latin_recall: float | None  # share of English words in the reference that came out in Latin script


def _count(term: str, text: str) -> int:
    return len(re.findall(rf"(?<!\S){re.escape(term)}(?!\S)", text))


def score_text(hyp: str, ref: str, glossary: list[str] | None = None) -> Score:
    h = normalize_for_match(hyp)
    r = normalize_for_match(ref)
    if not r:
        raise ValueError("reference transcript is empty")

    words = jiwer.process_words(r, h if h else "<empty>")
    chars = jiwer.process_characters(r, h if h else "<empty>")
    max_del = max_ins = 0
    for chunk in words.alignments[0]:
        if chunk.type == "delete":
            max_del = max(max_del, chunk.ref_end_idx - chunk.ref_start_idx)
        elif chunk.type == "insert":
            max_ins = max(max_ins, chunk.hyp_end_idx - chunk.hyp_start_idx)

    found = total = 0
    for term in {normalize_for_match(t) for t in glossary or [] if normalize_for_match(t)}:
        in_ref = _count(term, r)
        if in_ref:
            total += in_ref
            found += min(in_ref, _count(term, h))

    ref_latin = Counter(latin_tokens(ref))
    hyp_latin = Counter(latin_tokens(hyp))
    latin_total = sum(ref_latin.values())
    latin_hit = sum((ref_latin & hyp_latin).values())

    return Score(
        cer=round(chars.cer, 4),
        wer=round(words.wer, 4),
        ref_words=len(r.split()),
        substitutions=words.substitutions,
        deletions=words.deletions,
        insertions=words.insertions,
        max_deleted_run=max_del,
        max_inserted_run=max_ins,
        term_recall=round(found / total, 4) if total else None,
        terms_found=found,
        terms_total=total,
        latin_recall=round(latin_hit / latin_total, 4) if latin_total else None,
    )


def read_transcript_text(path: Path) -> str:
    """Plain text, or the `transcript.json` written by `rk process-file`."""
    if path.suffix == ".json":
        data = json.loads(path.read_text(encoding="utf-8"))
        segments = data["segments"] if isinstance(data, dict) else data
        return "\n".join(s["text"] for s in segments)
    return path.read_text(encoding="utf-8")


def score_files(hyp: Path, ref: Path, glossary: Path | None = None) -> int:
    terms = []
    if glossary is not None:
        terms = [line.strip() for line in glossary.read_text(encoding="utf-8").splitlines() if line.strip()]
    score = score_text(read_transcript_text(hyp), read_transcript_text(ref), terms)
    print(json.dumps(asdict(score), ensure_ascii=False, indent=2))
    return 0
