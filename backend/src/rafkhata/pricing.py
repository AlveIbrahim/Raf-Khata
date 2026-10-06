"""Price tables for the cost ledger. Update when vendors change prices (see docs/research.md)."""

from __future__ import annotations

from rafkhata.pipeline.llm.base import LLMUsage

# USD per audio hour (batch/async).
ASR_USD_PER_HOUR = {"sarvam": 0.35, "soniox": 0.10, "mock": 0.0}

# USD per 1M tokens: (input, output, cache read). Cache writes cost 1.25x input (5-minute TTL).
LLM_USD_PER_MTOK = {
    "claude-opus-5-5": (4.0, 20.0, 0.20),
    "claude-opus-5": (5.0, 25.0, 0.50),
    "claude-opus-4-8": (5.0, 25.0, 0.50),
    "claude-sonnet-5-5": (2.0, 10.0, 0.20),
    "claude-sonnet-5": (2.0, 10.0, 0.20),
    "claude-haiku-4-5": (1.0, 5.0, 0.10),
    "fake": (0.0, 0.0, 0.0),
}
CACHE_WRITE_MULTIPLIER = 1.25


def asr_cost(provider: str, seconds: float) -> float:
    return round(ASR_USD_PER_HOUR.get(provider, 0.0) * seconds / 3600.0, 6)


def llm_cost(usage: LLMUsage, fallback_model: str | None = None) -> float:
    prices = LLM_USD_PER_MTOK.get(usage.model) or LLM_USD_PER_MTOK.get(fallback_model or "", (0.0, 0.0, 0.0))
    inp, out, cache_read = prices
    usd = (
        usage.input_tokens * inp
        + usage.cache_creation_input_tokens * inp * CACHE_WRITE_MULTIPLIER
        + usage.cache_read_input_tokens * cache_read
        + usage.output_tokens * out
    ) / 1_000_000
    return round(usd, 6)
