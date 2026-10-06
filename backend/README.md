# Raf-Khata backend

FastAPI API, background worker and the lecture-processing pipeline (audio → Bangla/Banglish
transcript → Claude notes and study pack).

```bash
cp .env.example .env              # mock speech recognition + fake LLM by default
docker compose up -d postgres
uv sync
uv run rk migrate
uv run rk api                     # http://localhost:8000/docs
uv run rk worker
```

| Command | What it does |
|---|---|
| `rk api [--port 8000] [--reload]` | Run the HTTP API |
| `rk worker [--once]` | Process queued jobs |
| `rk migrate` | Apply database migrations |
| `rk process-file AUDIO --asr sarvam --llm gemini --lang bn --glossary terms.txt --out out/` (or `--llm claude`) | Run the whole pipeline on one file, no database needed |
| `rk score HYP REF [--glossary terms.txt]` | CER, WER, term recall and English-in-Latin recall of a transcript against a reference |

Tests: `uv run pytest` (SQLite). To run them on PostgreSQL through the migrations:
`RAFKHATA_TEST_DATABASE_URL=postgresql+psycopg://... uv run pytest`.
Lint: `uv run ruff check src tests && uv run ruff format --check src tests`.

Smoke-test a running server: `uv run python scripts/smoke.py http://localhost:8000`.

See [`../docs/architecture.md`](../docs/architecture.md), [`../docs/api.md`](../docs/api.md) and
[`../docs/deployment.md`](../docs/deployment.md).
