"""Course glossary ("course memory"): terms learned from each lecture help with the next one."""

from __future__ import annotations

from rafkhata.pipeline.schemas import LectureNotes

MAX_TERMS = 300


def terms_from_notes(notes: LectureNotes) -> list[str]:
    terms = []
    for d in notes.definitions:
        term = " ".join(d.term.split())
        if term and len(term) <= 60 and len(term.split()) <= 6:
            terms.append(term)
    return terms


def merge_glossary(existing: list[str], new_terms: list[str], limit: int = MAX_TERMS) -> list[str]:
    """New terms first (most recently taught), no duplicates (case-insensitive), at most `limit`."""
    seen: set[str] = set()
    merged: list[str] = []
    for term in [*new_terms, *existing]:
        key = term.strip().lower()
        if key and key not in seen:
            seen.add(key)
            merged.append(term.strip())
    return merged[:limit]
