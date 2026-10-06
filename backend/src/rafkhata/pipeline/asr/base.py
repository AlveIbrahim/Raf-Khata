"""Speech-recognition provider interface and helpers shared by the adapters."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from rafkhata.pipeline.schemas import Segment, Transcript, segment_id


class ASRError(RuntimeError):
    """The provider failed. `permanent=True` means retrying won't help (bad audio, bad key...)."""

    def __init__(self, message: str, *, permanent: bool = False) -> None:
        super().__init__(message)
        self.permanent = permanent


@dataclass
class ASRRequest:
    audio_path: Path  # 16 kHz mono WAV
    language: str = "bn"
    glossary: list[str] = field(default_factory=list)
    context: str = ""  # course title, teacher, topic
    diarize: bool = True
    num_speakers: int | None = None


@dataclass
class ASRResult:
    transcript: Transcript
    raw: Any
    billed_seconds: float


class ASRProvider(Protocol):
    name: str

    def transcribe(self, request: ASRRequest) -> ASRResult: ...


@dataclass
class _Piece:
    start: float
    end: float
    text: str
    speaker: str | None


_SENTENCE_END = ("।", "?", "!", ".", "॥")


def build_segments(pieces: list[_Piece], *, max_len_s: float = 25.0, pause_s: float = 0.8) -> list[Segment]:
    """Group small timed pieces (words/tokens/chunks) into readable transcript lines.

    A new line starts on a speaker change, a pause longer than `pause_s`, after a sentence end once the
    line is a few seconds long, or when the line would exceed `max_len_s`.
    """
    segments: list[Segment] = []
    current: list[_Piece] = []

    def flush() -> None:
        if not current:
            return
        text = "".join(p.text for p in current).strip()
        if text:
            segments.append(
                Segment(
                    id=segment_id(len(segments)),
                    start=round(current[0].start, 3),
                    end=round(current[-1].end, 3),
                    text=" ".join(text.split()),
                    speaker=current[0].speaker,
                )
            )
        current.clear()

    for piece in pieces:
        if current:
            last = current[-1]
            if (
                piece.speaker != last.speaker
                or piece.start - last.end > pause_s
                or piece.end - current[0].start > max_len_s
                or (last.text.strip().endswith(_SENTENCE_END) and last.end - current[0].start > 3.0)
            ):
                flush()
        current.append(piece)
    flush()
    return segments


def make_piece(start: float, end: float, text: str, speaker: str | None = None) -> _Piece:
    return _Piece(start=start, end=end, text=text, speaker=speaker)


def spans_to_segments(spans: list[tuple[float, float, str, str | None]]) -> list[Segment]:
    """Turn already sentence-level (start, end, text, speaker) spans into numbered segments."""
    out = []
    for start, end, text, speaker in spans:
        text = " ".join(text.split())
        if text:
            out.append(Segment(id=segment_id(len(out)), start=start, end=max(end, start), text=text, speaker=speaker))
    return out
