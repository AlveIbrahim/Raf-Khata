# Deployment

A small single-server setup is enough for a closed beta: one VPS running the API, a worker and
PostgreSQL, with audio in Cloudflare R2. Pick a Singapore or Mumbai region for low latency from
Bangladesh.

## 1. Accounts and keys
| What | Where | Used for |
|---|---|---|
| Google OAuth clients | Google Cloud Console → APIs & Services → Credentials | Sign-in. See §3 |
| Anthropic API key | console.anthropic.com | Correction, notes, study packs (`ANTHROPIC_API_KEY`) |
| Sarvam API key | dashboard.sarvam.ai | Primary speech recognition (`SARVAM_API_KEY`). Billed in INR; confirm you can pay from Bangladesh |
| Soniox API key | console.soniox.com | Fallback speech recognition (`SONIOX_API_KEY`) |
| Cloudflare R2 bucket + API token | Cloudflare dashboard → R2 | Audio and photos (`S3_*`) |
| Firebase project (optional) | console.firebase.google.com | Push notifications |

## 2. Server
1. Install Docker and Docker Compose on the VPS.
2. Copy the `backend/` folder to the server.
3. Create `backend/.env` from `.env.example` with production values:
   ```env
   ENV=prod
   PUBLIC_BASE_URL=https://api.your-domain.com
   JWT_SECRET=<48+ random characters>
   GOOGLE_CLIENT_IDS=<web-client-id>.apps.googleusercontent.com
   DEV_LOGIN_ENABLED=false

   STORAGE_BACKEND=s3
   S3_BUCKET=raf-khata
   S3_ENDPOINT_URL=https://<account-id>.r2.cloudflarestorage.com
   S3_REGION=auto
   S3_ACCESS_KEY_ID=...
   S3_SECRET_ACCESS_KEY=...

   ASR_PRIMARY=sarvam
   ASR_FALLBACK=soniox
   SARVAM_API_KEY=...
   SONIOX_API_KEY=...

   LLM_PROVIDER=claude
   ANTHROPIC_API_KEY=...
   LLM_MODEL=claude-opus-5-5
   ```
4. Change the Postgres password in `docker-compose.yml`, or point `DATABASE_URL` at a managed
   Postgres with backups.
5. Run `docker compose up -d --build`. The API applies database migrations on start.
6. Put HTTPS in front, e.g. Caddy with `reverse_proxy localhost:8000`. Android blocks plain HTTP in
   release builds.
7. Scale processing by running more workers: `docker compose up -d --scale worker=3`. Jobs are claimed
   safely by multiple workers.

## 3. Google sign-in
1. Configure the OAuth consent screen (External; app name "Raf-Khata").
2. Create an OAuth client of type **Web application**. Its client ID goes into:
   - the backend's `GOOGLE_CLIENT_IDS`;
   - the app's `rafkhata.googleWebClientId`.
3. Create an OAuth client of type **Android** with:
   - package `com.rafkhata.app`;
   - the SHA-1 of your signing key (`./gradlew :app:signingReport` for debug, plus the Play App
     Signing key for release).

   No ID from it goes in the app, but Google requires it to exist.

## 4. Push notifications (optional)
1. Create a Firebase project and add an Android app with package `com.rafkhata.app`.
2. Download its `google-services.json` and copy four values into `android/local.properties`. The app
   initializes Firebase from these, so the Google Services Gradle plugin isn't used:

   | `local.properties` key | Where it is in `google-services.json` |
   |---|---|
   | `rafkhata.firebaseProjectId` | `project_info.project_id` |
   | `rafkhata.firebaseSenderId` | `project_info.project_number` |
   | `rafkhata.firebaseAppId` | `client[0].client_info.mobilesdk_app_id` |
   | `rafkhata.firebaseApiKey` | `client[0].api_key[0].current_key` |

   Without them the app works normally, just without push notifications.
3. Under Project settings → Service accounts, generate a key. Mount it on the server and set:
   ```env
   FCM_PROJECT_ID=<firebase-project-id>
   FCM_SERVICE_ACCOUNT_FILE=/secrets/firebase.json
   ```

## 5. Check it works
- `curl https://api.your-domain.com/healthz`
- On a staging copy with `DEV_LOGIN_ENABLED=true`, run
  `uv run python scripts/smoke.py https://staging.your-domain.com`. It uploads a test tone, waits for
  processing, and checks the transcript, notes, study pack and audio.
- Try a real recording without the app:
  `uv run rk process-file lecture.m4a --asr sarvam --llm claude --out out/`.

## 6. Operations
- **Cost per lecture:** `SELECT lecture_id, sum(usd) FROM cost_ledger GROUP BY 1 ORDER BY 2 DESC;`
  The total is also stored in `lectures.cost_usd`.
- **Failed lectures:** `SELECT id, error FROM lectures WHERE status = 'failed';`. Users can retry
  from the app.
- **Retention:** raw segments are deleted after `RAW_AUDIO_RETENTION_DAYS` (default 30). Deleting a
  lecture or an account removes its files.
- **Backups:** back up Postgres daily, and enable R2 object versioning if you need to undo deletions.
- **Data protection:** see `privacy-policy-template.md` and the PDPO 2025 notes in `research.md`.
  Record which processors receive data (Sarvam, Soniox, Anthropic, Cloudflare, Google).
