"""Speech-recognition providers."""

from __future__ import annotations

from rafkhata.config import Settings
from rafkhata.pipeline.asr.base import ASRError, ASRProvider, ASRRequest, ASRResult

__all__ = ["ASRError", "ASRProvider", "ASRRequest", "ASRResult", "make_asr"]


def make_asr(name: str, settings: Settings) -> ASRProvider:
    if name == "sarvam":
        from rafkhata.pipeline.asr.sarvam import SarvamASR

        return SarvamASR(settings)
    if name == "soniox":
        from rafkhata.pipeline.asr.soniox import SonioxASR

        return SonioxASR(settings)
    if name == "mock":
        from rafkhata.pipeline.asr.mock import MockASR

        return MockASR()
    raise ASRError(f"unknown speech-recognition provider: {name}", permanent=True)
