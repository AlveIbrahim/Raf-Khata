"""Soniox async speech-to-text.

One multilingual model handles Bangla-English switching without configuration; language hints say
which languages to expect. The course glossary and title go in `context` to bias recognition of
technical terms.
"""

from __future__ import annotations

from typing import Any

from rafkhata.config import Settings
from rafkhata.pipeline.asr.base import ASRError, ASRRequest, ASRResult, build_segments, make_piece
from rafkhata.pipeline.schemas import Transcript


def tokens_to_pieces(tokens: list[Any]):
    pieces = []
    for token in tokens:
        text = getattr(token, "text", None) if not isinstance(token, dict) else token.get("text")
        start = getattr(token, "start_ms", None) if not isinstance(token, dict) else token.get("start_ms")
        end = getattr(token, "end_ms", None) if not isinstance(token, dict) else token.get("end_ms")
        speaker = getattr(token, "speaker", None) if not isinstance(token, dict) else token.get("speaker")
        if not text or start is None or end is None:
            continue
        pieces.append(make_piece(start / 1000.0, end / 1000.0, text, str(speaker) if speaker is not None else None))
    return pieces


class SonioxASR:
    name = "soniox"

    def __init__(self, settings: Settings) -> None:
        if not settings.soniox_api_key:
            raise ASRError("SONIOX_API_KEY is not set", permanent=True)
        from soniox import SonioxClient

        self.settings = settings
        self.client = SonioxClient(api_key=settings.soniox_api_key, timeout_sec=900)

    def transcribe(self, request: ASRRequest) -> ASRResult:
        from soniox.types.api import CreateTranscriptionConfig, StructuredContext

        context = None
        if request.glossary or request.context:
            context = StructuredContext(
                terms=request.glossary[:500] or None,
                text=request.context[:8000] or None,
            )
        config = CreateTranscriptionConfig(
            language_hints=["bn", "en"],
            enable_speaker_diarization=request.diarize,
            context=context,
        )
        try:
            result = self.client.stt.transcribe_and_wait_with_tokens(
                model=self.settings.soniox_model,
                file=request.audio_path,
                config=config,
                delete_after=True,
                wait_interval_sec=5.0,
                wait_timeout_sec=self.settings.asr_timeout_s,
            )
        except TimeoutError as exc:
            raise ASRError(f"Soniox transcription timed out: {exc}") from exc
        except Exception as exc:
            raise ASRError(f"Soniox request failed: {exc}") from exc

        pieces = tokens_to_pieces(list(result.tokens))
        segments = build_segments(pieces)
        transcript = Transcript(segments=segments, provider=self.name, model=self.settings.soniox_model, language="bn")
        billed = pieces[-1].end if pieces else 0.0
        return ASRResult(transcript=transcript, raw=result.model_dump(mode="json"), billed_seconds=billed)
