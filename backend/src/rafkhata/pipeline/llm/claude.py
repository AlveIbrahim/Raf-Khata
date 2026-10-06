"""Claude implementation of the notes LLM.

Every call is a single structured-output request:
- `system`: the shared Raf-Khata rules.
- First user block: lecture details and transcript, marked for prompt caching so the notes and
  study-pack calls (and other languages within a few minutes) re-read it cheaply.
- Second user block: the task.

Responses are streamed so long notes never hit HTTP timeouts. Safety-classifier declines are retried
server-side on Anthropic's recommended fallback model (`fallbacks: "default"`).
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

FALLBACK_BETA = "server-side-fallback-2026-07-01"
# Models that accept `fallbacks: "default"`.
FALLBACK_MODELS = {"claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-fable-5"}
# Models that reject `output_config.effort`.
NO_EFFORT_MODELS = {"claude-haiku-4-5", "claude-sonnet-4-5"}


class ClaudeLLM:
    name = "claude"

    def __init__(self, settings: Settings, client: Any | None = None) -> None:
        if client is None:
            import anthropic

            client = (
                anthropic.Anthropic(api_key=settings.anthropic_api_key)
                if settings.anthropic_api_key
                else (anthropic.Anthropic())
            )
        self.client = client
        self.settings = settings
        self.model = settings.llm_model

    # -- public tasks --------------------------------------------------------

    def correct(self, transcript: Transcript, meta: LectureMeta) -> tuple[CorrectionResult, LLMUsage]:
        return self._structured(
            lecture_context(transcript, meta),
            task_prompt("correct"),
            CorrectionResult,
            effort=self.settings.llm_effort_correct,
            max_tokens=min(self.settings.llm_max_tokens, 16000),
        )

    def notes(self, transcript: Transcript, meta: LectureMeta, lang: str) -> tuple[LectureNotes, LLMUsage]:
        return self._structured(
            lecture_context(transcript, meta),
            task_prompt("notes", lang),
            LectureNotes,
            effort=self.settings.llm_effort_notes,
            max_tokens=self.settings.llm_max_tokens,
        )

    def study(self, transcript: Transcript, meta: LectureMeta, lang: str) -> tuple[StudyPack, LLMUsage]:
        return self._structured(
            lecture_context(transcript, meta),
            task_prompt("study", lang),
            StudyPack,
            effort=self.settings.llm_effort_study,
            max_tokens=min(self.settings.llm_max_tokens, 24000),
        )

    # -- request ---------------------------------------------------------------

    def build_request(self, context: str, task: str, schema: type[T], *, effort: str, max_tokens: int) -> dict:
        request: dict[str, Any] = {
            "model": self.model,
            "max_tokens": max_tokens,
            "system": [{"type": "text", "text": load_prompt("system")}],
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": context, "cache_control": {"type": "ephemeral"}},
                        {"type": "text", "text": task},
                    ],
                }
            ],
            "output_format": schema,
        }
        if self.model not in NO_EFFORT_MODELS:
            request["output_config"] = {"effort": effort}
        if self.settings.llm_fallbacks and self.model in FALLBACK_MODELS:
            request["betas"] = [FALLBACK_BETA]
            request["fallbacks"] = "default"
        return request

    def _structured(
        self, context: str, task: str, schema: type[T], *, effort: str, max_tokens: int
    ) -> tuple[T, LLMUsage]:
        import anthropic

        request = self.build_request(context, task, schema, effort=effort, max_tokens=max_tokens)
        try:
            with self.client.beta.messages.stream(**request) as stream:
                message = stream.get_final_message()
        except anthropic.BadRequestError as exc:
            raise LLMError(f"Claude rejected the request: {exc.message}", permanent=True) from exc
        except anthropic.AuthenticationError as exc:
            raise LLMError("ANTHROPIC_API_KEY is missing or invalid", permanent=True) from exc
        except anthropic.APIStatusError as exc:
            raise LLMError(f"Claude API error {exc.status_code}: {exc.message}") from exc
        except anthropic.APIConnectionError as exc:
            raise LLMError(f"could not reach the Claude API: {exc}") from exc

        if message.stop_reason == "refusal":
            details = getattr(message, "stop_details", None)
            category = getattr(details, "category", None) if details else None
            raise LLMRefusal(f"Claude declined to process this lecture (category: {category or 'unknown'})")
        if message.stop_reason == "max_tokens":
            raise LLMError(f"Claude's answer was cut off at {max_tokens} tokens")

        parsed = getattr(message, "parsed_output", None)
        if parsed is None:
            text = "".join(b.text for b in message.content if getattr(b, "type", None) == "text")
            try:
                parsed = schema.model_validate_json(text)
            except ValidationError as exc:
                raise LLMError(f"Claude returned output that doesn't match the schema: {exc}") from exc

        u = message.usage
        usage = LLMUsage(
            model=getattr(message, "model", None) or self.model,
            input_tokens=u.input_tokens or 0,
            output_tokens=u.output_tokens or 0,
            cache_creation_input_tokens=getattr(u, "cache_creation_input_tokens", 0) or 0,
            cache_read_input_tokens=getattr(u, "cache_read_input_tokens", 0) or 0,
        )
        return parsed, usage
