# Architecture

```
 Android app (Kotlin)                                Backend (Python 3.13)
 ┌────────────────────────────┐  presigned PUT   ┌──────────────────────────────────┐
 │ RecordingService            │──per segment────▶│ Object storage (R2 / S3 / disk)  │
 │  AudioRecord → AAC → ADTS   │   (WorkManager)  └──────────────────────────────────┘
 │  5-minute files             │                   ┌──────────────────────────────────┐
 │ Room (offline source of     │──REST + JWT─────▶│ API (FastAPI, `rk api`)          │
 │  truth) · Compose UI        │◀─FCM push────────│   ↕ PostgreSQL (data + job queue)│
 │ WebView notes (KaTeX)       │                   │ Worker (`rk worker`)             │
 │ Media3 player               │                   │   audio → ASR → Claude → notes   │
 └────────────────────────────┘                   │   ↔ Sarvam / Soniox, Claude API  │
                                                   └──────────────────────────────────┘
```

## Backend (`backend/src/rafkhata`)

| Module | Role |
|---|---|
| `config.py` | All settings from environment variables. `ENV=prod` refuses insecure values |
| `db.py`, `models.py` | SQLAlchemy 2 models; JSON columns become JSONB on Postgres; UTC datetimes everywhere |
| `migrations/` | Alembic. `rk migrate` applies them |
| `security.py` | Google ID-token verification; JWT access tokens; hashed, rotating refresh tokens |
| `storage.py` | `S3Storage` (R2, MinIO, AWS) and `LocalStorage` (HMAC-signed URLs served by `api/files.py`) |
| `api/` | REST routes. `deps.py` holds the access rules |
| `jobs.py` | Postgres job queue: `FOR UPDATE SKIP LOCKED`, exponential backoff, lease recovery |
| `worker.py` | Claims jobs and runs them. Permanent errors fail immediately; others retry |
| `pipeline/` | Audio, speech recognition, Claude steps, rendering (below) |
| `notify.py` | FCM HTTP v1 push. A no-op when not configured |
| `pricing.py` | Price tables for the per-lecture cost ledger |

### Who can see what
- A lecture is visible to the person who recorded it and to every member of its section.
- The recorder, plus the section's owner and CR, can correct the transcript.
- Courses belong to a user and can be attached to a section. Members can record into section courses.

### Processing a lecture (`pipeline/process.py`)
`POST /lectures/{id}/finalize` queues a `process_lecture` job. The worker runs these steps:

| Step | What happens | Stored | Cost |
|---|---|---|---|
| assemble | Download segments; ffmpeg joins them and converts to 16 kHz mono with loudness normalization; playback `.m4a`; quality score (SNR, clipping, speech ratio) | `audio.m4a`, `lectures.quality` | — |
| vad | Energy VAD with an adaptive noise floor. Silences over 2 s are cut to 0.5 s; a `TimeMap` keeps timestamps true to the original | — | Lowers ASR cost |
| transcribe | Primary provider (`ASR_PRIMARY`), then the fallback. Times are mapped back; text is normalized; the main speaker is labelled `T`, others `S` | `transcripts` v1 (raw), raw JSON in storage | ASR |
| correct | Claude returns **patches only** for misheard terms and English words written in Bangla script. Patches are applied only where the `find` text matches exactly | `transcripts` v2 (corrected, current) | Claude |
| notes | Claude structured output (`LectureNotes`) in the lecture's language. Citations to lines that don't exist are dropped. Rendered to an HTML fragment and to Markdown | `notes` | Claude |
| study | Flashcards, MCQs, short/broad questions. Reuses the cached transcript block | `study_packs` | Claude (cache read) |
| index | Announcements become `deadlines` (ISO date checked, or parsed from Bangla/English phrases); search text is stored | `deadlines` | — |
| glossary | New defined terms go into the course glossary, which biases the next lecture's ASR and correction | `courses.glossary` | — |
| notify | Push to the recorder and section members | — | — |

Every step saves its result before the next one starts. A retried job therefore resumes where it
stopped and does not pay for speech recognition or Claude twice. Raw 5-minute segments are deleted
after `RAW_AUDIO_RETENTION_DAYS` (a `purge_segments` job); the playback `.m4a` stays.

`regenerate_notes` makes notes and a study pack in another language from the current transcript,
including any user edits.

### Claude usage (`pipeline/llm/claude.py`)
- Request shape:
  - `system`: shared rules.
  - First user block: lecture details + transcript, with `cache_control`.
  - Second block: the task.
- Structured outputs via `output_format=<Pydantic model>`.
- Streamed with `client.beta.messages.stream`.
- `fallbacks: "default"` (beta `server-side-fallback-2026-07-01`) re-runs safety-classifier declines on
  Anthropic's recommended model. A final `refusal` fails the lecture with a clear message.
- `LLM_MODEL` (default `claude-opus-5-5`) and per-step effort are configurable. The cost of every call
  is recorded in `cost_ledger`.

### Adding a speech-recognition provider
Implement `transcribe(ASRRequest) -> ASRResult` (see `pipeline/asr/soniox.py`) and register it in
`pipeline/asr/__init__.py`. `rk process-file --asr <name>` and `rk score` compare it on your own
recordings.

## Android app (`android/`)

- **Single-activity Compose app.**
  - MVVM: ViewModels expose `StateFlow`.
  - Repositories combine Room (the offline source of truth) with the Retrofit API.
  - Hilt for dependency injection.
- **`core-logic/`** is a plain-Kotlin build included in the Gradle project. It holds logic that needs
  no Android APIs and is unit-tested on the JVM:
  - ADTS header framing.
  - Segment rotation.
  - Matching the routine to "which course is now".
  - Upload state machine.
  - Time formatting.
- **Recording** (`recording/`):
  - `RecordingService` is a foreground service of type `microphone`, started from the Record screen.
  - `AudioRecord` (16 kHz mono) → `MediaCodec` AAC-LC → ADTS frames in 5-minute files.
  - Bookmark and photo times count recorded time (pauses excluded), so they line up with the joined
    audio.
  - After a crash, the saved segments are offered for upload on next launch.
- **Upload** (`upload/`):
  - A unique WorkManager job per lecture: create → upload segments through presigned URLs → photos and
    bookmarks → finalize.
  - Respects the Wi-Fi-only setting and retries with backoff.
- **Notes view:**
  - The server HTML fragment goes inside a local shell (`assets/notes/`) with bundled KaTeX and app
    styles.
  - `rafkhata://seek?t=` links jump the audio player to that moment.
  - PDF export goes through Android printing.
- **Sign-in:**
  - Credential Manager returns a Google ID token for the **Web** client ID, which the backend verifies.
  - Tokens are stored encrypted with an Android Keystore key.

## Testing
- **Backend:** `uv run pytest` (unit, API, end-to-end worker runs with real ffmpeg, the mock ASR and
  the fake LLM). Set `RAFKHATA_TEST_DATABASE_URL` to run against PostgreSQL through the migrations;
  CI does this.
- **Android:** `./gradlew :core-logic:test` (JVM), and `:app:testDebugUnitTest :app:lintDebug
  :app:assembleDebug` on CI.
