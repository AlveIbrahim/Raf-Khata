"""Pipeline steps that work on files and objects (no database), shared by the worker and `rk process-file`."""

from __future__ import annotations

import logging
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from rafkhata.config import Settings
from rafkhata.pipeline.asr import ASRError, ASRProvider, ASRRequest, ASRResult, make_asr
from rafkhata.pipeline.audio import (
    SAMPLE_RATE,
    TimeMap,
    concat_to_wav,
    energy_vad,
    quality_report,
    read_wav,
    trim_silences,
    write_wav,
)
from rafkhata.pipeline.llm import LectureMeta, LLMUsage, NotesLLM
from rafkhata.pipeline.normalize import normalize_text
from rafkhata.pipeline.schemas import (
    CorrectionResult,
    LectureNotes,
    Patch,
    Segment,
    StudyPack,
    Transcript,
    segment_id,
)

log = logging.getLogger(__name__)


@dataclass
class PreparedAudio:
    full_wav: Path  # whole lecture, 16 kHz mono, loudness-normalized
    asr_wav: Path  # long silences trimmed (or the same file)
    duration_s: float
    asr_duration_s: float
    time_map: TimeMap
    quality: dict[str, Any]


def prepare_audio(inputs: list[Path], work_dir: Path, *, trim: bool = True, min_gap_s: float = 2.0) -> PreparedAudio:
    work_dir.mkdir(parents=True, exist_ok=True)
    full = work_dir / "full.wav"
    concat_to_wav(inputs, full)
    samples = read_wav(full)
    duration = len(samples) / SAMPLE_RATE
    regions = energy_vad(samples)
    quality = quality_report(samples, regions)

    asr_wav, time_map, asr_duration = full, TimeMap.identity(duration), duration
    if trim and regions:
        trimmed, tmap = trim_silences(samples, regions, min_gap_s=min_gap_s)
        # Only worth a second file if it saves at least 3% of billed audio.
        if len(trimmed) < 0.97 * len(samples):
            asr_wav = work_dir / "asr.wav"
            write_wav(asr_wav, trimmed)
            time_map, asr_duration = tmap, len(trimmed) / SAMPLE_RATE
    quality["trimmed_s"] = round(duration - asr_duration, 1)
    return PreparedAudio(full, asr_wav, duration, asr_duration, time_map, quality)


def transcribe(
    settings: Settings,
    audio: PreparedAudio,
    providers: list[str],
    meta: LectureMeta,
    factory: Callable[[str, Settings], ASRProvider] = make_asr,
) -> tuple[Transcript, ASRResult, str]:
    """Try each provider in order; return the normalized transcript, the raw result and who produced it."""
    errors: list[str] = []
    permanent = True
    for name in providers:
        try:
            provider = factory(name, settings)
            context = " · ".join(x for x in (meta.course_title, meta.course_code, meta.teacher) if x)
            result = provider.transcribe(
                ASRRequest(audio_path=audio.asr_wav, glossary=meta.glossary, context=context, diarize=True)
            )
            if not result.transcript.segments:
                raise ASRError("the transcript is empty (no speech found?)")
            return finalize_transcript(result.transcript, audio.time_map, audio.duration_s), result, name
        except ASRError as exc:
            log.warning("ASR provider %s failed: %s", name, exc)
            errors.append(f"{name}: {exc}")
            permanent = permanent and exc.permanent
    raise ASRError("; ".join(errors) or "no speech-recognition provider configured", permanent=permanent)


def finalize_transcript(transcript: Transcript, time_map: TimeMap, duration_s: float) -> Transcript:
    """Map times back to the original recording, normalize text, label the teacher (T) and students (S)."""
    talk: dict[str | None, float] = defaultdict(float)
    for seg in transcript.segments:
        talk[seg.speaker] += max(seg.end - seg.start, 0.0)
    teacher = max(talk, key=lambda k: talk[k]) if talk else None

    segments: list[Segment] = []
    for seg in transcript.segments:
        text = normalize_text(seg.text)
        if not text:
            continue
        start = min(time_map.to_original(seg.start), duration_s)
        end = min(max(time_map.to_original(seg.end), start), duration_s)
        speaker = "T" if seg.speaker is None or seg.speaker == teacher else "S"
        segments.append(
            Segment(id=segment_id(len(segments)), start=round(start, 2), end=round(end, 2), text=text, speaker=speaker)
        )
    return transcript.model_copy(update={"segments": segments, "duration_s": duration_s})


def apply_patches(transcript: Transcript, patches: list[Patch]) -> tuple[Transcript, list[Patch], list[Patch]]:
    """Apply find/replace patches; a patch is rejected unless `find` occurs exactly in its line."""
    segments = {s.id: s.model_copy() for s in transcript.segments}
    applied: list[Patch] = []
    rejected: list[Patch] = []
    for patch in patches:
        seg = segments.get(patch.segment_id)
        if seg is None or not patch.find or patch.find == patch.replace or patch.find not in seg.text:
            rejected.append(patch)
            continue
        seg.text = normalize_text(seg.text.replace(patch.find, patch.replace, 1))
        applied.append(patch)
    ordered = [segments[s.id] for s in transcript.segments]
    return transcript.model_copy(update={"segments": ordered}), applied, rejected


def _clean_ids(value: Any, valid: set[str], fallback: str) -> Any:
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            if key == "source_segment_ids" and isinstance(item, list):
                out[key] = [i for i in item if i in valid]
            elif key == "start_segment_id" and isinstance(item, str):
                out[key] = item if item in valid else fallback
            else:
                out[key] = _clean_ids(item, valid, fallback)
        return out
    if isinstance(value, list):
        return [_clean_ids(v, valid, fallback) for v in value]
    return value


def clean_citations[M: BaseModel](model: M, transcript: Transcript) -> M:
    """Drop citations to lines that don't exist, so every timestamp link in the app works."""
    valid = {s.id for s in transcript.segments}
    fallback = transcript.segments[0].id if transcript.segments else ""
    return type(model).model_validate(_clean_ids(model.model_dump(), valid, fallback))


def correct_transcript(
    llm: NotesLLM, transcript: Transcript, meta: LectureMeta
) -> tuple[Transcript, list[Patch], list[Patch], LLMUsage]:
    result: CorrectionResult
    result, usage = llm.correct(transcript, meta)
    corrected, applied, rejected = apply_patches(transcript, result.patches)
    return corrected, applied, rejected, usage


def generate_notes(
    llm: NotesLLM, transcript: Transcript, meta: LectureMeta, lang: str
) -> tuple[LectureNotes, LLMUsage]:
    notes, usage = llm.notes(transcript, meta, lang)
    return clean_citations(notes, transcript), usage


def generate_study(llm: NotesLLM, transcript: Transcript, meta: LectureMeta, lang: str) -> tuple[StudyPack, LLMUsage]:
    pack, usage = llm.study(transcript, meta, lang)
    pack = clean_citations(pack, transcript)
    # Keep only well-formed MCQs.
    mcqs = [q for q in pack.mcqs if len(q.options) >= 2 and 0 <= q.answer_index < len(q.options)]
    return pack.model_copy(update={"mcqs": mcqs}), usage
