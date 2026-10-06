"""LLM backends for transcript correction, notes and study packs."""

from __future__ import annotations

from rafkhata.config import Settings
from rafkhata.pipeline.llm.base import LectureMeta, LLMError, LLMRefusal, LLMUsage, NotesLLM

__all__ = ["LLMError", "LLMRefusal", "LLMUsage", "LectureMeta", "NotesLLM", "make_llm"]


def make_llm(name: str, settings: Settings) -> NotesLLM:
    if name == "claude":
        from rafkhata.pipeline.llm.claude import ClaudeLLM

        return ClaudeLLM(settings)
    if name == "fake":
        from rafkhata.pipeline.llm.fake import FakeLLM

        return FakeLLM()
    raise LLMError(f"unknown LLM provider: {name}", permanent=True)
