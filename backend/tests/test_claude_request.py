"""The Claude client builds the right request and handles responses, checked against a stand-in SDK client."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest
from conftest import make_settings

from rafkhata.pipeline.llm.base import LectureMeta, LLMError, LLMRefusal
from rafkhata.pipeline.llm.claude import FALLBACK_BETA, ClaudeLLM
from rafkhata.pipeline.schemas import CorrectionResult, Patch, Segment, StudyPack, Transcript


class _Stream:
    def __init__(self, message: Any) -> None:
        self.message = message

    def __enter__(self) -> _Stream:
        return self

    def __exit__(self, *exc: object) -> None:
        return None

    def get_final_message(self) -> Any:
        return self.message


class FakeAnthropic:
    def __init__(self, message: Any) -> None:
        self.calls: list[dict[str, Any]] = []
        self.message = message
        self.beta = SimpleNamespace(messages=SimpleNamespace(stream=self._stream))

    def _stream(self, **kwargs: Any) -> _Stream:
        self.calls.append(kwargs)
        return _Stream(self.message)


def message(parsed: Any, *, stop_reason: str = "end_turn", model: str = "claude-opus-5-5") -> SimpleNamespace:
    return SimpleNamespace(
        stop_reason=stop_reason,
        stop_details=SimpleNamespace(category="bio") if stop_reason == "refusal" else None,
        parsed_output=parsed,
        content=[],
        model=model,
        usage=SimpleNamespace(
            input_tokens=100, output_tokens=50, cache_creation_input_tokens=9000, cache_read_input_tokens=0
        ),
    )


TRANSCRIPT = Transcript(
    provider="test", segments=[Segment(id="s0001", start=0, end=3, text="আমরা অ্যালগরিদম শিখব", speaker="T")]
)
META = LectureMeta(course_title="Algorithms")


def test_request_shape_with_caching_effort_and_fallbacks(tmp_path) -> None:
    result = CorrectionResult(
        patches=[Patch(segment_id="s0001", find="অ্যালগরিদম", replace="algorithm", reason="english_word")]
    )
    client = FakeAnthropic(message(result))
    llm = ClaudeLLM(make_settings(tmp_path, llm_provider="claude", llm_model="claude-opus-5-5"), client=client)

    parsed, usage = llm.correct(TRANSCRIPT, META)
    assert parsed == result
    assert usage.cache_creation_input_tokens == 9000

    call = client.calls[0]
    assert call["model"] == "claude-opus-5-5"
    assert call["output_format"] is CorrectionResult
    assert call["output_config"] == {"effort": "low"}
    assert call["betas"] == [FALLBACK_BETA] and call["fallbacks"] == "default"
    context, task = call["messages"][0]["content"]
    assert context["cache_control"] == {"type": "ephemeral"}
    assert "s0001 [00:00] T: আমরা অ্যালগরিদম শিখব" in context["text"]
    assert "patches" in task["text"]
    assert "Raf-Khata" in call["system"][0]["text"]


def test_notes_and_study_share_the_cached_block(tmp_path) -> None:
    client = FakeAnthropic(message(StudyPack(flashcards=[], mcqs=[], questions=[])))
    llm = ClaudeLLM(make_settings(tmp_path, llm_provider="claude"), client=client)
    llm.study(TRANSCRIPT, META, "en")
    llm.study(TRANSCRIPT, META, "bn")
    first, second = (c["messages"][0]["content"] for c in client.calls)
    assert first[0] == second[0]  # identical cached prefix across languages
    assert first[1] != second[1]


def test_haiku_gets_no_effort_or_fallbacks(tmp_path) -> None:
    client = FakeAnthropic(message(CorrectionResult(patches=[])))
    llm = ClaudeLLM(make_settings(tmp_path, llm_provider="claude", llm_model="claude-haiku-4-5"), client=client)
    llm.correct(TRANSCRIPT, META)
    assert "output_config" not in client.calls[0]
    assert "fallbacks" not in client.calls[0]


def test_refusal_and_truncation(tmp_path) -> None:
    settings = make_settings(tmp_path, llm_provider="claude")
    with pytest.raises(LLMRefusal) as refused:
        ClaudeLLM(settings, client=FakeAnthropic(message(None, stop_reason="refusal"))).correct(TRANSCRIPT, META)
    assert refused.value.permanent is True
    assert "bio" in str(refused.value)

    with pytest.raises(LLMError) as cut:
        ClaudeLLM(settings, client=FakeAnthropic(message(None, stop_reason="max_tokens"))).correct(TRANSCRIPT, META)
    assert cut.value.permanent is False
