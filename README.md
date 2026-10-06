# Raf-Khata (রাফ খাতা)

**Record the class. Get the notes.** Raf-Khata records university lectures in Bangla and Banglish
(Bangla-English code-mixed speech), transcribes them, and turns them into exam-ready study notes,
flashcards and practice questions. It is built for students in Bangladesh.

> Status: Phase 1 (MVP) in development. See [`docs/PLAN.md`](docs/PLAN.md) for the product plan.

## What it does
- **Records the class.** It keeps recording with the screen off and saves 5-minute files, so a crash
  loses almost nothing. You can mark ⭐ important / ❓ confusing moments and photograph the board.
- **Transcribes Bangla + English as spoken.** `এই algorithm টা O(n log n) time এ run করে`, with English
  terms kept in English.
- **Writes notes** in বাংলা, English or Banglish: summary, topic outline with timestamps, definitions,
  formulas, worked examples, exam alerts ("এটা পরীক্ষায় আসবে"), and announced deadlines.
- **Builds a study pack:** flashcards, MCQs, and short and broad questions.
- **Shares with your section.** Your CR records once, and everyone in the section gets the notes.

## Repository
| Path | What |
|---|---|
| [`android/`](android/) | Native Android app (Kotlin, Jetpack Compose) |
| [`backend/`](backend/) | Python 3.13 API (FastAPI), worker and processing pipeline (speech recognition + Claude) |
| [`docs/`](docs/) | Plan, research, architecture, API reference, deployment, style guide, consent and privacy templates |
| `.github/workflows/` | CI: backend tests; Android build, tests and lint, with a debug APK artifact |

## Quick start (development)

### Backend
Requirements: Python 3.13, [uv](https://docs.astral.sh/uv/), ffmpeg, PostgreSQL 16 (or Docker).

```bash
cd backend
cp .env.example .env            # dev defaults: mock speech recognition, fake LLM, local storage
docker compose up -d postgres   # or point DATABASE_URL at your own PostgreSQL
uv sync
uv run rk migrate
uv run rk api                   # http://localhost:8000/docs
uv run rk worker                # in a second terminal
```

To try the pipeline on a recording without the app:

```bash
uv run rk process-file lecture.m4a --asr mock --llm fake --out out/
# with real providers (set SARVAM_API_KEY / SONIOX_API_KEY / ANTHROPIC_API_KEY in .env):
uv run rk process-file lecture.m4a --asr sarvam --llm claude --out out/
```

### Android
Open `android/` in Android Studio (a recent release that supports AGP 9.4), then:
1. Put the API URL and your Google **Web** OAuth client ID in `android/local.properties`:
   ```properties
   rafkhata.apiBaseUrl=http://10.0.2.2:8000/
   rafkhata.googleWebClientId=1234567890-abc.apps.googleusercontent.com
   ```
2. Optional, for push notifications: copy four values from your Firebase app's `google-services.json`
   into the same file (see [deployment §4](docs/deployment.md#4-push-notifications-optional)):
   ```properties
   rafkhata.firebaseProjectId=your-project-id
   rafkhata.firebaseAppId=1:1234567890:android:abc123
   rafkhata.firebaseApiKey=AIza...
   rafkhata.firebaseSenderId=1234567890
   ```
3. Run the `app` configuration. Debug builds also offer **Dev login** when the backend has
   `DEV_LOGIN_ENABLED=true`.

Each CI run also uploads an installable `app-debug.apk` as an artifact.

## Documentation
- [Product and research plan](docs/PLAN.md) · [Research notes](docs/research.md)
- [Architecture](docs/architecture.md) · [API reference](docs/api.md) · [Deployment](docs/deployment.md)
- [Transcription style guide](docs/transcription-style-guide.md) · [Teacher consent form](docs/consent-form.md) ·
  [Privacy policy template](docs/privacy-policy-template.md)
