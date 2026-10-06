"""Sarvam AI batch speech-to-text (saaras v3 in `codemix` mode by default).

`codemix` keeps English words in Latin script and Bangla in Bengali script, which is exactly the
transcript style Raf-Khata wants. The batch API takes files up to 2 hours and supports diarization
and chunk-level timestamps. `saaras:v4` also accepts up to 50 key terms. Longer audio is split into
parts that are sent in the same job.
"""

from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Any

from rafkhata.config import Settings
from rafkhata.pipeline.asr.base import ASRError, ASRRequest, ASRResult, spans_to_segments
from rafkhata.pipeline.audio import SAMPLE_RATE, read_wav, write_wav
from rafkhata.pipeline.schemas import Transcript

MAX_PART_S = 100 * 60  # stay well under the 2-hour batch limit

Span = tuple[float, float, str, str | None]


def parse_sarvam_output(data: dict[str, Any]) -> list[Span]:
    """Diarized entries if present, else timestamped chunks, else the whole transcript as one line."""
    diarized = (data.get("diarized_transcript") or {}).get("entries") or []
    if diarized:
        return [
            (
                float(e.get("start_time_seconds") or 0.0),
                float(e.get("end_time_seconds") or 0.0),
                str(e.get("transcript") or ""),
                str(e.get("speaker_id")) if e.get("speaker_id") is not None else None,
            )
            for e in diarized
        ]
    ts = data.get("timestamps") or {}
    chunks = ts.get("words") or []
    starts = ts.get("start_time_seconds") or []
    ends = ts.get("end_time_seconds") or []
    if chunks and len(chunks) == len(starts) == len(ends):
        return [(float(s), float(e), str(c), None) for c, s, e in zip(chunks, starts, ends, strict=True)]
    text = str(data.get("transcript") or "")
    return [(0.0, 0.0, text, None)] if text else []


def split_audio(audio_path: Path, out_dir: Path, max_part_s: float = MAX_PART_S) -> list[tuple[Path, float]]:
    """Split a long WAV into parts of at most `max_part_s`. Returns (path, offset_seconds) pairs."""
    samples = read_wav(audio_path)
    duration = len(samples) / SAMPLE_RATE
    if duration <= max_part_s:
        return [(audio_path, 0.0)]
    parts = []
    step = int(max_part_s * SAMPLE_RATE)
    for i, start in enumerate(range(0, len(samples), step)):
        path = out_dir / f"part_{i:03d}.wav"
        write_wav(path, samples[start : start + step])
        parts.append((path, start / SAMPLE_RATE))
    return parts


class SarvamASR:
    name = "sarvam"

    def __init__(self, settings: Settings) -> None:
        if not settings.sarvam_api_key:
            raise ASRError("SARVAM_API_KEY is not set", permanent=True)
        from sarvamai import SarvamAI

        self.settings = settings
        self.client = SarvamAI(api_subscription_key=settings.sarvam_api_key, timeout=120)

    def transcribe(self, request: ASRRequest) -> ASRResult:
        s = self.settings
        model = s.sarvam_model
        with tempfile.TemporaryDirectory() as tmp:
            parts = split_audio(request.audio_path, Path(tmp))
            try:
                job = self.client.speech_to_text_job.create_job(
                    model=model,
                    mode=s.sarvam_mode if model.startswith("saaras") else None,
                    language_code=s.sarvam_language,
                    with_diarization=request.diarize,
                    with_timestamps=True,
                    num_speakers=request.num_speakers,
                    keyterms=request.glossary[:50] if model == "saaras:v4" and request.glossary else None,
                )
                job.upload_files([str(p) for p, _ in parts], timeout=900)
                job.start()
                status = job.wait_until_complete(poll_interval=10, timeout=s.asr_timeout_s)
            except TimeoutError as exc:
                raise ASRError(f"Sarvam job timed out: {exc}") from exc
            except Exception as exc:  # the SDK raises its own ApiError types
                raise ASRError(f"Sarvam request failed: {exc}") from exc

            if str(status.job_state).lower() != "completed":
                raise ASRError(f"Sarvam job ended as {status.job_state}")
            results = job.get_file_results()
            if results.get("failed"):
                first = results["failed"][0]
                raise ASRError(f"Sarvam could not transcribe the audio: {first.get('error_message')}")

            out_dir = Path(tmp) / "out"
            job.download_outputs(str(out_dir))
            spans: list[Span] = []
            raw: list[dict[str, Any]] = []
            for path, offset in parts:
                output = out_dir / f"{path.name}.json"
                if not output.exists():
                    raise ASRError(f"Sarvam returned no output for {path.name}")
                data = json.loads(output.read_text(encoding="utf-8"))
                raw.append(data)
                spans += [(a + offset, b + offset, text, spk) for a, b, text, spk in parse_sarvam_output(data)]

        segments = spans_to_segments(spans)
        transcript = Transcript(
            segments=segments,
            provider=self.name,
            model=f"{model}/{s.sarvam_mode}" if model.startswith("saaras") else model,
            language=(raw[0].get("language_code") if raw else None) or "bn",
        )
        billed = max((seg.end for seg in segments), default=0.0)
        return ASRResult(transcript=transcript, raw=raw, billed_seconds=billed)
