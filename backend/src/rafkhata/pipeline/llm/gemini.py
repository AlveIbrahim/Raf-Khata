"""Gemini implementation of the notes LLM, using a Google AI Studio (Gemini API) key.

Same prompts and output schemas as the Claude backend:
- `system_instruction`: the shared Raf-Khata rules.
- One user turn: lecture details and transcript first, then the task. The transcript block is
  byte-for-byte the same in the correction, notes and study calls, so Gemini's implicit caching can
  bill the repeats at the cached-token rate.
- Structured output: the Pydantic schema is passed as `response_schema` and parsed back.

Note: on Google's free tier, prompts and responses may be used to improve Google's products. Use a
billing-enabled project for real lectures (see docs/deployment.md).
"""

from __future__ import annotations

from typing import Any, TypeVar

from pydantic import BaseModel, ValidationError

from rafkhata.config import Settings
from rafkhata.pipeline.llm.base import (
    LectureMeta,
    LLMError,
    LLMRefusal,
    LLMUsage,
    lecture_context,
    load_prompt,
    task_prompt,
)
from rafkhata.pipeline.schemas import CorrectionResult, LectureNotes, StudyPack, Transcript

T = TypeVar("T", bound=BaseModel)

# Finish reasons that mean the model declined; retrying the same lecture won't help. (A blocked
# prompt, reported in prompt_feedback, is always treated as a refusal.)
REFUSAL_FINISH_REASONS = {"SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION"}


class GeminiLLM:
    name = "gemini"

    def __init__(self, settings: Settings, client: Any | None = None) -> None:
        if client is None:
            if not settings.gemini_api_key:
                raise LLMError("GEMINI_API_KEY is not set", permanent=True)
            from google import genai
            from google.genai import types

            client = genai.Client(
                api_key=settings.gemini_api_key,
                http_options=types.HttpOptions(
                    timeout=settings.gemini_timeout_s * 1000,
                    retry_options=types.HttpRetryOptions(attempts=3),
                ),
            )
        self.client = client
        self.settings = settings
        self.model = settings.gemini_model

    # -- public tasks --------------------------------------------------------

    def correct(self, transcript: Transcript, meta: LectureMeta) -> tuple[CorrectionResult, LLMUsage]:
        return self._structured(
            lecture_context(transcript, meta),
            task_prompt("correct"),
            CorrectionResult,
            max_tokens=min(self.settings.llm_max_tokens, 16000),
        )

    def notes(self, transcript: Transcript, meta: LectureMeta, lang: str) -> tuple[LectureNotes, LLMUsage]:
        return self._structured(
            lecture_context(transcript, meta),
            task_prompt("notes", lang),
            LectureNotes,
            max_tokens=self.settings.llm_max_tokens,
        )

    def study(self, transcript: Transcript, meta: LectureMeta, lang: str) -> tuple[StudyPack, LLMUsage]:
        return self._structured(
            lecture_context(transcript, meta),
            task_prompt("study", lang),
            StudyPack,
            max_tokens=min(self.settings.llm_max_tokens, 24000),
        )

    # -- request ---------------------------------------------------------------

    def build_request(self, context: str, task: str, schema: type[T], *, max_tokens: int) -> dict[str, Any]:
        from google.genai import types

        level = self.settings.gemini_thinking_level
        config = types.GenerateContentConfig(
            system_instruction=load_prompt("system"),
            response_mime_type="application/json",
            response_schema=schema,
            max_output_tokens=max_tokens,
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
            # Gemini 2.x models take a thinking budget instead of a level; they keep their default.
            thinking_config=(
                types.ThinkingConfig(thinking_level=level.upper())
                if level != "default" and not self.model.startswith("gemini-2")
                else None
            ),
        )
        contents = [types.Content(role="user", parts=[types.Part(text=context), types.Part(text=task)])]
        return {"model": self.model, "contents": contents, "config": config}

    def _structured(self, context: str, task: str, schema: type[T], *, max_tokens: int) -> tuple[T, LLMUsage]:
        from google.genai import errors

        request = self.build_request(context, task, schema, max_tokens=max_tokens)
        try:
            response = self.client.models.generate_content(**request)
        except errors.ClientError as exc:
            if exc.code == 429:
                raise LLMError(f"Gemini rate limit or daily quota reached: {exc.message}") from exc
            if exc.code in (401, 403) or "API key" in str(exc.message):
                raise LLMError("GEMINI_API_KEY is missing or invalid", permanent=True) from exc
            raise LLMError(f"Gemini rejected the request ({exc.code}): {exc.message}", permanent=True) from exc
        except errors.APIError as exc:
            raise LLMError(f"Gemini API error {exc.code}: {exc.message}") from exc
        except OSError as exc:
            raise LLMError(f"could not reach the Gemini API: {exc}") from exc
        except Exception as exc:  # httpx/httpx2 transport errors don't share an importable base class
            if type(exc).__module__.split(".")[0] in ("httpx", "httpx2", "httpcore"):
                raise LLMError(f"could not reach the Gemini API: {exc}") from exc
            raise

        feedback = getattr(response, "prompt_feedback", None)
        blocked = _name(getattr(feedback, "block_reason", None)) if feedback else None
        if blocked and blocked != "BLOCKED_REASON_UNSPECIFIED":
            raise LLMRefusal(f"Gemini blocked this lecture (reason: {blocked})")

        candidates = getattr(response, "candidates", None) or []
        finish = _name(getattr(candidates[0], "finish_reason", None)) if candidates else None
        if finish == "MAX_TOKENS":
            raise LLMError(f"Gemini's answer was cut off at {max_tokens} tokens")
        if finish in REFUSAL_FINISH_REASONS:
            raise LLMRefusal(f"Gemini declined to process this lecture (reason: {finish})")

        parsed = getattr(response, "parsed", None)
        if not isinstance(parsed, schema):
            text = getattr(response, "text", None) or ""
            try:
                parsed = schema.model_validate_json(text)
            except ValidationError as exc:
                raise LLMError(f"Gemini returned output that doesn't match the schema: {exc}") from exc

        return parsed, self._usage(response)

    def _usage(self, response: Any) -> LLMUsage:
        meta = getattr(response, "usage_metadata", None)
        prompt = getattr(meta, "prompt_token_count", None) or 0
        cached = getattr(meta, "cached_content_token_count", None) or 0
        output = getattr(meta, "candidates_token_count", None) or 0
        thoughts = getattr(meta, "thoughts_token_count", None) or 0
        return LLMUsage(
            model=self.model,
            input_tokens=max(prompt - cached, 0),
            cache_read_input_tokens=cached,
            # Thinking tokens are billed as output.
            output_tokens=output + thoughts,
        )


def _name(value: Any) -> str | None:
    """Enum or string reason as its upper-case name."""
    if value is None:
        return None
    return str(getattr(value, "name", value)).upper()
