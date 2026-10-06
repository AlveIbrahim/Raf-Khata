"""The Gemini client builds the right request and handles responses: first with a stand-in client,
then through the real google-genai SDK with the network replaced by a mock transport."""

from __future__ import annotations

import json
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from conftest import make_settings
from google import genai
from google.genai import errors, types

from rafkhata.pipeline.llm.base import LectureMeta, LLMError, LLMRefusal
from rafkhata.pipeline.llm.gemini import GeminiLLM
from rafkhata.pipeline.schemas import (
    Bullet,
    CorrectionResult,
    LectureNotes,
    NoteSection,
    Patch,
    Segment,
    Transcript,
)
from rafkhata.pricing import llm_cost

TRANSCRIPT = Transcript(
    provider="test", segments=[Segment(id="s0001", start=0, end=3, text="আমরা অ্যালগরিদম শিখব", speaker="T")]
)
META = LectureMeta(course_title="Algorithms")
CORRECTION = CorrectionResult(
    patches=[Patch(segment_id="s0001", find="অ্যালগরিদম", replace="algorithm", reason="english_word")]
)


class FakeGenai:
    """Stand-in for genai.Client: records generate_content calls and returns a canned response."""

    def __init__(self, response: Any = None, error: Exception | None = None) -> None:
        self.calls: list[dict[str, Any]] = []
        self.response = response
        self.error = error
        self.models = SimpleNamespace(generate_content=self._generate)

    def _generate(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        if self.error is not None:
            raise self.error
        return self.response


def response(parsed: Any = None, *, text: str = "", finish: str = "STOP", blocked: str | None = None) -> Any:
    return SimpleNamespace(
        parsed=parsed,
        text=text,
        candidates=[SimpleNamespace(finish_reason=SimpleNamespace(name=finish))],
        prompt_feedback=SimpleNamespace(block_reason=blocked) if blocked else None,
        usage_metadata=SimpleNamespace(
            prompt_token_count=1000, cached_content_token_count=800, candidates_token_count=100, thoughts_token_count=50
        ),
    )


def gemini(tmp_path, client: Any, **overrides: Any) -> GeminiLLM:
    settings = make_settings(tmp_path, llm_provider="gemini", gemini_api_key="test-key", **overrides)
    return GeminiLLM(settings, client=client)


def test_request_shape_and_usage(tmp_path) -> None:
    client = FakeGenai(response(CORRECTION))
    llm = gemini(tmp_path, client)

    parsed, usage = llm.correct(TRANSCRIPT, META)
    assert parsed == CORRECTION
    # Cached prompt tokens are billed separately; thinking tokens count as output.
    assert (usage.input_tokens, usage.cache_read_input_tokens, usage.output_tokens) == (200, 800, 150)
    assert usage.model == "gemini-3.5-flash"
    assert llm_cost(usage) > 0

    call = client.calls[0]
    assert call["model"] == "gemini-3.5-flash"
    config = call["config"]
    assert config.response_schema is CorrectionResult
    assert config.response_mime_type == "application/json"
    assert "Raf-Khata" in config.system_instruction
    assert config.thinking_config is None
    context, task = call["contents"][0].parts
    assert "s0001 [00:00] T: আমরা অ্যালগরিদম শিখব" in context.text
    assert "patches" in task.text


def test_thinking_level_only_for_models_that_take_it(tmp_path) -> None:
    llm = gemini(tmp_path, FakeGenai(response(CORRECTION)), gemini_thinking_level="low")
    llm.correct(TRANSCRIPT, META)
    assert llm.client.calls[0]["config"].thinking_config.thinking_level == types.ThinkingLevel.LOW

    older = gemini(
        tmp_path, FakeGenai(response(CORRECTION)), gemini_thinking_level="low", gemini_model="gemini-2.5-flash"
    )
    older.correct(TRANSCRIPT, META)
    assert older.client.calls[0]["config"].thinking_config is None


def test_falls_back_to_parsing_the_text(tmp_path) -> None:
    llm = gemini(tmp_path, FakeGenai(response(None, text=CORRECTION.model_dump_json())))
    parsed, _ = llm.correct(TRANSCRIPT, META)
    assert parsed == CORRECTION


def test_safety_stops_are_permanent_refusals(tmp_path) -> None:
    with pytest.raises(LLMRefusal):
        gemini(tmp_path, FakeGenai(response(None, finish="SAFETY"))).correct(TRANSCRIPT, META)
    with pytest.raises(LLMRefusal):
        gemini(tmp_path, FakeGenai(response(None, blocked="PROHIBITED_CONTENT"))).correct(TRANSCRIPT, META)


def test_cut_off_answer_is_retryable(tmp_path) -> None:
    with pytest.raises(LLMError) as info:
        gemini(tmp_path, FakeGenai(response(None, finish="MAX_TOKENS"))).correct(TRANSCRIPT, META)
    assert not info.value.permanent


@pytest.mark.parametrize(
    ("code", "status", "permanent"),
    [(429, "RESOURCE_EXHAUSTED", False), (400, "INVALID_ARGUMENT", True), (403, "PERMISSION_DENIED", True)],
)
def test_api_errors(tmp_path, code: int, status: str, permanent: bool) -> None:
    error = errors.ClientError(code, {"error": {"code": code, "message": "nope", "status": status}})
    with pytest.raises(LLMError) as info:
        gemini(tmp_path, FakeGenai(error=error)).correct(TRANSCRIPT, META)
    assert info.value.permanent is permanent


def test_server_errors_are_retryable(tmp_path) -> None:
    error = errors.ServerError(503, {"error": {"code": 503, "message": "overloaded", "status": "UNAVAILABLE"}})
    with pytest.raises(LLMError) as info:
        gemini(tmp_path, FakeGenai(error=error)).correct(TRANSCRIPT, META)
    assert not info.value.permanent


def test_missing_key_is_a_clear_error(tmp_path) -> None:
    with pytest.raises(LLMError, match="GEMINI_API_KEY"):
        GeminiLLM(make_settings(tmp_path, llm_provider="gemini", gemini_api_key=None))


def test_round_trip_through_the_real_sdk(tmp_path) -> None:
    """The real SDK accepts our Pydantic schemas and parses the reply back into LectureNotes."""
    notes = LectureNotes(
        title="Deadlock",
        summary=["Four conditions cause deadlock."],
        sections=[
            NoteSection(
                title="Conditions",
                start_segment_id="s0001",
                bullets=[Bullet(text="mutual exclusion", source_segment_ids=["s0001"])],
            )
        ],
        definitions=[],
        formulas=[],
        examples=[],
        exam_alerts=[],
        announcements=[],
        class_questions=[],
        unclear_parts=[],
    )
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(
            200,
            json={
                "candidates": [
                    {
                        "content": {"role": "model", "parts": [{"text": notes.model_dump_json()}]},
                        "finishReason": "STOP",
                        "index": 0,
                    }
                ],
                "usageMetadata": {
                    "promptTokenCount": 1200,
                    "cachedContentTokenCount": 1000,
                    "candidatesTokenCount": 300,
                    "thoughtsTokenCount": 40,
                    "totalTokenCount": 1540,
                },
                "modelVersion": "gemini-3.5-flash",
            },
        )

    client = genai.Client(
        api_key="test-key",
        http_options=types.HttpOptions(httpx_client=httpx.Client(transport=httpx.MockTransport(handler))),
    )
    llm = gemini(tmp_path, client, gemini_thinking_level="medium")

    parsed, usage = llm.notes(TRANSCRIPT, META, "bn")
    assert parsed == notes
    assert (usage.input_tokens, usage.cache_read_input_tokens, usage.output_tokens) == (200, 1000, 340)

    request = seen[0]
    assert request.url.path.endswith("/models/gemini-3.5-flash:generateContent")
    assert request.headers["x-goog-api-key"] == "test-key"
    body = json.loads(request.content)
    generation = body["generationConfig"]
    assert generation["responseMimeType"] == "application/json"
    schema = json.dumps(generation.get("responseSchema") or generation.get("responseJsonSchema"))
    assert "exam_alerts" in schema and "source_segment_ids" in schema
    thinking = generation["thinkingConfig"]
    assert (thinking.get("thinkingLevel") or thinking.get("thinking_level")) == "MEDIUM"
    assert "Raf-Khata" in json.dumps(body["systemInstruction"], ensure_ascii=False)
    assert "আমরা অ্যালগরিদম শিখব" in body["contents"][0]["parts"][0]["text"]
