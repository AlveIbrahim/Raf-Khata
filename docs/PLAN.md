# Raf-Khata (রাফ খাতা): product and research plan

This is the product plan the app is built from. Detailed research tables are in
[`research.md`](research.md), and how the code is put together is in [`architecture.md`](architecture.md).

## Context

**Goal.** A product for Bangladeshi university students: record a class, get an accurate transcript of
Bangla and Bangla-English code-mixed ("Banglish") speech, and get exam-ready study notes from it.

**Why it is worth building.**
- Most global lecture-note apps (Turbo AI, 5M+ users; Coconote; Otter) are built for English. General
  speech models do badly on Bangla: in a Sept-2026 benchmark of 8 speech-to-text APIs on real Bangla
  audio, gpt-4o-transcribe and Whisper-large-v3 had 4 to 5 times the error rate of the best systems.
- Some Bengali apps already exist (Soniox App, Hearlog), so plain "Bengali transcription" is not a moat.
  No app is built around how Bangladeshi universities actually work: Banglish lectures, ceiling-fan
  classrooms, CR/section culture, CT/midterm/final exams, bKash payments and BDT pricing.
- Market: about 4.82M tertiary students (UGC, 2023), including 3.4M at National University colleges, plus
  53 public and 114 private universities. Android dominates.

**Decisions:**
- Native Android app in **Kotlin**.
- **Python** backend and AI pipeline, **self-hosted** (FastAPI + PostgreSQL + S3/R2 storage).
- Speech recognition: **paid APIs first, our own model later**.
- The first build covers **Phase 1 (MVP) only** (§2). Phase 0 measurement runs alongside it, using the
  app itself and the backend's `rk process-file` and `rk score` commands instead of a separate prototype.

---

## 1. Research findings that drive the design

### 1.1 Bangla speech recognition (ASR) as of October 2026

| Service / model | Evidence | Price per audio hour | Verdict |
|---|---|---|---|
| **Sarvam** saarika v2.5 / **saaras v3** | Riverbornai benchmark (Sep 2026, 1,001 real Bangla clips, 13 domains): best batch result overall, **6.9% CER / 18.2% WER**. **Class-lecture domain: 6.0% CER (best)**; 6.2% in streaming. Ranked #1 or #2 for Bengali on Voice of India (IIT-M, 2026). `codemix` mode keeps English words in Latin script and Bangla in Bengali script. Batch API takes files up to 2 h; supports diarization | ₹30 (about $0.31–0.35) | **Primary candidate.** Indian vendor that bills in INR, so check sign-up and billing from Bangladesh. Audio would be processed in India |
| **Soniox** stt-async-v5 | 8.1% CER / 21.5% WER overall; 12.2% CER on lectures; best overall in streaming (7.4% CER). Handles code-switching natively. A `context` field (key terms plus up to about 8k tokens of text) adds course vocabulary | **$0.10** async, $0.12 realtime | **Secondary / fallback**, and the choice for live captions |
| ElevenLabs Scribe v2 | v1 scored 14.7% CER (20.8% on lectures); v2 scored about 8–10% WER on Bengali in Voice of India (telephone speech). Keyterm prompting up to 1,000 terms | $0.22 (+$0.05 for keyterms) | Include in the benchmark |
| Gemini (2.5 Flash / 3 Pro / newer Flash) | 2.5 Flash: 13.3% CER (11.3% on lectures). Voice of India Bengali: 3 Pro 8.5% WER, 3 Flash 12.6%. On long audio it is unreliable: repetition loops, skipped segments, and calls that return empty output. Timestamps are weak | per token | Benchmark only |
| Deepgram nova-3 | 10.1% CER (12.4% on lectures) | $0.26–0.46 | Benchmark only |
| Google Chirp 2 / gpt-4o-transcribe / Whisper-large-v3 | 21.9% / 28.7% / 30.7% CER (Whisper: 71.7% WER) | $0.96 / — / — | Not usable |

Caveat: the riverbornai clips average about 3 seconds and leave out noisy and code-switched
conversational speech. **We must benchmark on our own full-length Bangladeshi lectures (Phase 0).**

### 1.2 Open models and data (for owning the model later)
- **BuzzASR/bengali** (EMNLP 2026): Whisper-large-v3, fully fine-tuned with a native Bengali tokenizer.
  CER 5.5 on Common Voice 25, 10.2 on FLEURS. Open weights. Fixing the tokenizer matters because stock
  Whisper splits Bangla into far too many tokens.
- **AI4Bharat IndicConformer** (120M Bengali model, 600M multilingual; hybrid CTC-RNNT): fast and does not
  hallucinate, but it outputs Bengali script only. **Meta Omnilingual ASR**: Apache-2.0, up to 7B
  parameters, 1,600+ languages.
- Long-form Bangla is still hard for open models. In DL Sprint 4.0 (BUET CSE Fest 2026, 22-hour hidden
  long-form test), the best fine-tuned Whisper-medium systems scored about **0.24–0.38 WER**.
- Training data: **OOD-Speech** (1,178 h train / 23 h test, including online classes);
  **Common Voice bn** (about 400 h); **MUCS-2021 Bengali-English code-switched** (46 h + 7 h of technical
  tutorials, the closest public match to Banglish lectures); **Lipi-Ghor-882** (882 h, multi-speaker);
  OpenSLR-53; IndicVoices. Check each license before training.
- **There is no public dataset of Bangladeshi university lectures.** Building one, with consent, is the
  long-term moat.

### 1.3 The notes model (Claude)
- Claude does not accept audio, so speech recognition is a separate stage. Claude handles transcript
  correction, notes, study tools and chat.
- Price per 1M tokens (input/output): **Opus 5.5** `claude-opus-5-5` $4/$20 (1M context, cache reads
  $0.20); **Sonnet 5.5** `claude-sonnet-5-5` $2/$10 (cache reads $0.20); **Haiku 4.5** `claude-haiku-4-5`
  $1/$5. The Batch API is 50% cheaper.
- A 1-hour lecture is about 8–10k spoken words, roughly 25–40k tokens of Bangla/Banglish text (to confirm
  with `count_tokens`). That fits in one request, so the notes step needs no chunking.

### 1.4 Constraints specific to Bangladesh
- Budget Xiaomi, Realme, Oppo, Vivo, Infinix and Tecno phones aggressively kill background apps. On
  Android 14+, a microphone foreground service must be started while the app is on screen.
- Payments: bKash (70M+ users, more than 60% of online payments) offers tokenized-checkout "agreements"
  for recurring billing. SSLCommerz-type aggregators cover bKash, Nagad and cards.
- Law: the Personal Data Protection Ordinance 2025 (enacted Nov 2025, revised Mar 2026) requires
  explicit consent, notice of purpose and retention, and data-subject rights. The general localization
  mandate was dropped, but cross-border transfers are limited to "adequate" countries and that list is
  still pending. Keep data minimal, deletable and region-pinned, and get a legal review before launch.
- Many PDF libraries break Bangla conjuncts. Exports need HarfBuzz text shaping: Android's WebView
  print-to-PDF, or Chromium/WeasyPrint on the server.

---

## 2. Product

**Core loop:** Record → upload (automatic) → transcript → notes → study → share with the section.

**MVP (Phase 1)**
1. **One-tap recording** that keeps running with the screen off and survives calls and crashes. During
   class, students can tap **⭐ Important** / **❓ Didn't get it**, and take **board/slide photos** that
   are pinned to that moment in the lecture.
2. **Courses + class routine.** The student enters their weekly routine once, and recordings are filed
   to the right course automatically by time slot.
3. **Transcript synced with audio.** Tapping a line plays that moment. Lines are labeled Teacher or
   Student, and any line can be corrected.
4. **Notes** in বাংলা, English or Banglish (student's choice): a short summary, a topic outline with
   timestamps, detailed notes, a glossary (English term plus Bangla explanation), formulas in LaTeX,
   worked examples, and Q&A asked in class. Two sections no global app has:
   - **Exam alerts**: anything the teacher flagged ("এটা পরীক্ষায় আসবে", "important", "CT তে দিব").
   - **Announcements & deadlines**: CT, assignment and quiz dates turned into reminders.
5. **Study pack:** flashcards, MCQs, and short and broad questions in Bangladeshi exam styles
   (সংজ্ঞা দাও / পার্থক্য লেখ / ব্যাখ্যা কর).
6. **Section spaces.** The CR or any member records, classmates join with a code, and notes are shared
   inside the section. One recording serves 30–60 students, which splits the cost and spreads the app.
7. Search across lectures, and PDF export.

**v1 (Phase 2):** chat with a course ("স্যার deadlock নিয়ে কী বলেছিলেন?") with answers that cite
timestamps; an exam pack that merges chosen lectures into one revision guide; bKash subscription; an
optional teacher view.
**Later:** live captions (also an accessibility feature for hard-of-hearing students), iOS, a web
reader, and plans sold to institutions.

**Consent and trust are part of the product:** a "teacher's permission" confirmation per course, a
visible recording indicator, private by default, teacher takedown requests honored, audio retention
limits, and user-controlled delete and export. There is never a covert recording mode.

---

## 3. The pipeline: getting accurate notes from Bangla and Banglish speech

**Transcription style.** This is both the output format and the rule set for gold (reference) data:
- Bangla words are written in Bengali script with Bangla Academy spelling.
- Words the teacher said in English stay in Latin script: `এই algorithm টা O(n log n) time এ run করে`.
- Numbers are written as digits, inaudible parts as `[অস্পষ্ট]`, and speakers as T (teacher) / S (student).

**Stages.** One Python worker runs them. Every stage is idempotent, retried on failure, and logs its cost.
1. **Capture (native Android).**
   - `AudioRecord` captures 16 kHz mono PCM. That feeds a live level meter and a `MediaCodec` AAC-LC
     encoder, which writes ADTS frames at 32–48 kbps (about 14–21 MB per hour; bitrate set in Phase 0).
   - The recorder starts a new file every 5 minutes. ADTS is frame-based, so a crash loses at most the
     last few frames. MP4 is avoided because the whole file is lost if the app is killed before it
     finalizes the file.
   - Microphone source (MIC vs VOICE_RECOGNITION vs CAMCORDER vs UNPROCESSED) is picked by a Phase 0 test.
   - `AudioRecordingCallback` detects when a phone call silences the mic, and the app marks that gap.
   - A 10-second check before recording measures noise and volume and gives placement tips: near the
     teacher, away from ceiling fans, never face-down. CRs can optionally use a ৳500–1,500 wireless
     lavalier mic.
2. **Upload:** each 5-minute segment is uploaded as its own object through a presigned URL by
   WorkManager, retried independently, with a Wi-Fi-only option and an offline queue. The server joins
   the segments.
3. **Preprocess:**
   - Convert with ffmpeg to 16 kHz mono and normalize loudness (EBU R128).
   - Run Silero VAD to cut long silences (board-writing pauses) while keeping a time map, so billed
     audio drops and timestamps stay correct.
   - Compute an audio-quality score. Denoising (DeepFilterNet/Demucs) runs only if Phase 0 shows it helps.
4. **ASR** behind a provider-agnostic `ASRProvider` interface:
   - Primary: Sarvam saaras v3 batch in `codemix` mode with diarization.
   - Secondary: Soniox async v5 with the course glossary passed as `context.terms`. It takes over on
     errors or timeouts and powers live mode.
   - Phase 0 confirms which one is primary.
5. **Normalize:** Unicode NFC plus Bangla-specific normalization (the nukta forms of য়/ড়/ঢ়, ৎ, ZWJ/ZWNJ),
   digits, danda and spacing.
6. **Correction using the course glossary (Claude).**
   - Claude receives the segments with their IDs plus the course glossary.
   - It returns **JSON patches only**: segment_id, from, to and reason. These fix mangled technical terms
     and English words written in Bangla script (অ্যালগরিদম → algorithm).
   - Claude never paraphrases. The raw transcript is kept for audit and training.
   - Returning patches instead of the full text keeps output tokens, and so cost, low.
7. **Notes (Claude, structured output).**
   - Every bullet carries `source_segment_ids`, so tapping it plays the source audio.
   - Notes contain only what was said in class. Anything extra, from an "explain more" request, is
     shown separately and labeled as AI explanation.
   - Bookmarks and board-photo OCR (Claude vision) are extra inputs.
   - Claude calls use structured outputs and prompt caching, and handle `stop_reason: "refusal"` with
     server-side `fallbacks: "default"`. Medicine and microbiology lectures could trigger false-positive
     refusals.
8. **Study pack and exam pack** reuse the cached transcript prefix (cache reads cost about 5–10% of
   normal input). Jobs that aren't urgent go through the Batch API at half price.
9. **Course memory.** After each lecture the course glossary (terms, names, symbols) is updated and fed
   into the next lecture's ASR and correction, so accuracy for a course improves over the semester.
10. **Index and notify.**
    - MVP search is normalized substring search.
    - "Chat with course" puts the course's notes into Claude's 1M-token context, so no retrieval stack is
      needed at first. Hybrid retrieval (BGE-M3 + pgvector) comes in Phase 2.
    - A push notification announces "নোট তৈরি!" when notes are ready.

**Target:** notes ready within 15 minutes of upload for a 1-hour lecture.

---

## 4. Architecture and stack

```
 Native Android app (Kotlin)                       Python backend
 ┌──────────────────────────────┐  presigned PUT  ┌────────────────────────────────┐
 │ RecordingService (fg svc,     │──per segment──▶│ Object storage (Cloudflare R2) │
 │  type=microphone) AudioRecord │  (WorkManager)  └────────────────────────────────┘
 │  → MediaCodec AAC → 5-min ADTS│                  ┌────────────────────────────────┐
 │ Room (offline state)          │──REST/JWT──────▶│ FastAPI API                    │
 │ Compose UI + WebView notes    │◀──FCM push──────│  ↕ PostgreSQL (self-hosted)    │
 │ (HTML + KaTeX + Bangla font)  │                  │  ↕ jobs table (SKIP LOCKED)    │
 │ Media3 ExoPlayer (audio sync) │                  │ Pipeline worker(s):            │
 └──────────────────────────────┘                  │  ingest→VAD→ASR→normalize→     │
                                                    │  correct→notes→study→index     │
                                                    │  ↔ Sarvam / Soniox (ASR)       │
                                                    │  ↔ Claude API (LLM)            │
                                                    └────────────────────────────────┘
```
- **App:** native Android in **Kotlin**. Google's Android APIs and Jetpack libraries are Kotlin-first;
  Java would work but is not recommended for new apps. iOS comes later as a separate Swift app, with
  shared logic moved to Kotlin Multiplatform if that pays off.
  - **Stack:** Jetpack Compose (Material 3); MVVM with coroutines/Flow; Hilt for dependency injection;
    Room for offline state (lectures, segments, upload status, cached notes); Retrofit/OkHttp for the
    API; Media3 ExoPlayer to play audio in sync with the transcript.
  - **Recording:** `RecordingService` is a foreground service with
    `foregroundServiceType="microphone"`. It needs RECORD_AUDIO, FOREGROUND_SERVICE,
    FOREGROUND_SERVICE_MICROPHONE and POST_NOTIFICATIONS, and must be started while the app is on
    screen. After a crash or process kill, the recorded segments are kept and offered for upload on
    next launch (Android 14+ doesn't allow restarting the microphone from the background).
  - **Background work:** WorkManager for uploads.
  - **Sign-in:** Google sign-in through Credential Manager. The backend verifies the Google ID token and
    issues its own session tokens.
  - **Push:** FCM. **Crash reporting:** Crashlytics or Sentry.
  - **Notes display:** the server renders notes to HTML (KaTeX, Noto Sans Bengali / Hind Siliguri) and
    the app shows them in a WebView. PDF export prints that same HTML through Android's print framework,
    whose Chromium/HarfBuzz rendering keeps Bangla conjuncts intact.
  - **Targets:** minSdk 26, targetSdk 36 (Google Play's current requirement), compileSdk 37.
  - **Testing:** a device matrix of budget Xiaomi, Realme, Oppo, Vivo, Samsung and Infinix phones.
- **Backend:** Python 3.13, FastAPI, and a pipeline worker, deployed in Docker.
  - Self-hosted PostgreSQL. Sign-in is handled by the backend, which verifies Google ID tokens and
    issues its own JWTs.
  - Jobs live in a Postgres-backed queue (`FOR UPDATE SKIP LOCKED`), so there is no Redis.
  - Audio goes to Cloudflare R2, which has no egress fees.
  - Region: Mumbai or Singapore.
  - Sentry for errors; a cost record per lecture; Metabase for the admin dashboard.
- **Key tables:**
  - `users`
  - `spaces` and `space_members` (roles: cr, member, teacher)
  - `courses` (glossary as jsonb, routine)
  - `lectures` (status, audio keys, quality score, consent flag)
  - `assets` (photos)
  - `bookmarks`
  - `transcripts` (versioned segments as jsonb, provider)
  - `notes` (language, version, content as jsonb, model)
  - `study_items`
  - `jobs` (step, status, attempts, cost_usd)
  - `feedback` and `edits`
- **Monorepo:**
  ```
  docs/      PLAN.md, research.md (sources), architecture, API, style guide, consent form, deployment
  backend/   FastAPI app + worker + processing pipeline (package `rafkhata`)
  android/   Native Android app, Kotlin + Compose, with a plain-Kotlin core-logic build
  (architecture.md has the full layout.)
  ```

---

## 5. Roadmap

**Phase 0: prove quality and demand (weeks 1–3)**
- Collect 20–30 hours of real lectures, with consent:
  - from at least 4 universities (public, private and National University colleges), 8 subjects and
    15 teachers;
  - across different phones and phone positions, with and without fan noise.
- Build a **gold set**: 6–8 hours, sampled as 5–10-minute windows, transcribed by 2–3 paid students
  following the style guide and checked twice.
- **Recorder test (Kotlin, week 2):** a bare-bones app that records the same lecture with each mic
  source and bitrate on 4–6 budget phones. Run the files through the benchmark to pick the capture
  settings, and check how each phone's battery saver treats a 90-minute session with the screen off.
- Run every candidate through the backend's `rk process-file` and score it with `rk score`:
  - APIs: Sarvam (codemix), Soniox (with context), ElevenLabs v2 (with keyterms), Gemini (current Flash
    and Pro), Deepgram nova-3.
  - Open models: BuzzASR-bn, IndicConformer, Omnilingual.
  - Each with and without preprocessing, and with and without the Claude correction step.
- **Metrics:**
  - CER, plus WER after normalization.
  - Technical-term recall, and how often English words come out in Latin script.
  - Deletions and insertions on full 60–90-minute files (to catch skipped or invented text).
  - Timestamp drift, cost per hour and latency.
- **Notes eval:**
  - Students rate notes for 30 lectures on faithfulness, completeness and usefulness (1–5).
  - Claude also grades them against a rubric, calibrated to the student ratings.
  - Compare Opus 5.5, Sonnet 5.5 and Haiku 4.5 using the §6 cost table; **you then pick the production
    model.**
- **Demand:** 25+ student interviews, 5 CRs, and a price test at ৳99/149/199.
- **Exit gate:**
  - CER ≤ 10% and term recall ≥ 90% on the gold set.
  - At least 70% of raters score the notes ≥ 4/5.
  - Pipeline cost per hour is known.

**Phase 1: MVP closed beta (weeks 4–10)**
- Native Android app in Kotlin (record, upload, courses and routine, transcript, notes, study pack) plus
  FastAPI, the worker, PostgreSQL, R2 and FCM.
- Beta with 5–10 sections (about 200–400 students) at 2–3 universities, recruited through CRs.
- KPIs:
  - Notes opened for at least 60% of recorded lectures.
  - 30-day retention of at least 35%.
  - Average note rating of at least 4/5.
  - At least 99% of recordings complete without a crash.

**Phase 2: growth and payments (weeks 11–18)**
- Section invites, chat with a course, exam pack, PDF export, board-photo OCR merged into notes, bKash
  subscription through an aggregator, and Play Store launch.
- Growth: a CR ambassador program and sharing in section Facebook groups.
- Pricing hypothesis to test:
  - Free: shared section notes plus 3 of your own lectures per month.
  - Premium: ৳149/month or ৳399/semester.
  - A section pass.

**Phase 3: own the speech model (months 4–8, overlapping Phase 2)**
- Opt-in data collection: audio plus user-corrected transcripts, with the teacher's consent.
- Fine-tune BuzzASR-bn or Whisper-large-v3-turbo with a tokenizer that covers both Bengali and Latin
  script. Training data:
  - public sets (OOD-Speech, Common Voice, MUCS bn-en and others);
  - lectures labeled automatically by the API ASR plus Claude correction, keeping only segments where
    two systems agree;
  - the gold set.
- Serve with CTranslate2/faster-whisper on L4-class GPUs in batches.
- Switch only when it beats the API on the gold set. Target: **≤ $0.03 per audio hour**.

**Phase 4: expand.** Live captions (Soniox or Sarvam realtime), iOS, a web reader, and teacher and
institution plans.

---

## 6. Unit economics per lecture-hour (estimates to confirm in Phase 0)

| | Premium | Balanced | Lean |
|---|---|---|---|
| ASR | Sarvam ≈ $0.33 | Sarvam ≈ $0.33 | Soniox $0.10 |
| Claude (correction + notes + study pack) | Opus 5.5 ≈ $0.70–0.80 | Sonnet 5.5 ≈ $0.35–0.40 | Haiku 4.5 ≈ $0.15–0.20 |
| Infra and storage | ≈ $0.02 | ≈ $0.02 | ≈ $0.02 |
| **Total per lecture-hour** | **≈ $1.10** | **≈ $0.73** | **≈ $0.29** |
| Per student, 40-student section sharing one recording | ≈ $0.028 | ≈ $0.018 | ≈ $0.007 |

Assumptions: 35k transcript tokens; about 3k patch, 8k notes and 6k study-pack output tokens; thinking
overhead included; the study pack uses the cached transcript.

A student attends about 60–80 lecture-hours a month. **If every student records alone, the product cannot
pay for itself at Bangladeshi prices.** Section sharing, VAD trimming, the Batch API and our own model
(Phase 3) are what make the numbers work. Track the real cost of every lecture from day one.

---

## 7. Risks and mitigations
- **Noisy far-field audio** (fans, echo, back benches): placement tips, the pre-recording check, a lav mic
  for CRs, the glossary, a second ASR as fallback, and transcript edits by users.
- **Notes inventing content:** every bullet cites its transcript segment, the prompt allows only what was
  said, faithfulness is part of the eval, and users get a "report" button.
- **Vendor risk** (Sarvam's INR billing, data going to India, India–Bangladesh politics): the ASR interface
  is provider-agnostic, Soniox is integrated from day one, and our own model arrives in Phase 3.
- **Teachers or universities objecting:** the consent flow, a teacher view, framing as an accessibility
  tool, and partnerships with institutions.
- **Phone makers killing the recorder:** a foreground service, onboarding that asks users to switch off
  battery optimization, 5-minute file rotation, and recovery on restart.
- **Cost blow-up:** quotas per user, VAD trimming, Batch API, prompt caching, and choosing the model tier.
- **Regional dialects:** include them in the gold set, and add regional training data in Phase 3.
- **PDPO 2025:** consent, retention limits, delete and export, data-processing agreements with vendors,
  and a legal review.

---

## 8. Implementation
The Phase 1 build (Android app, backend, processing pipeline) is described in
[`architecture.md`](architecture.md); setup steps are in the [README](../README.md) and
[`deployment.md`](deployment.md).

---

## Sources
- Riverbornai Bangla STT benchmark (Sep 2026): https://github.com/riverbornai/bangla-speech-to-text-benchmark
- Voice of India benchmark (IIT-M, 2026): https://arxiv.org/abs/2604.19151 · https://cxotoday.com/media-coverage/global-speech-ai-struggles-to-understand-india-new-national-benchmark-voice-of-india-reveals/
- ElevenLabs Bengali STT: https://elevenlabs.io/speech-to-text/bengali · Scribe v2 pricing: https://andrew.ooo/answers/best-speech-to-text-apis-2026-ranked/
- Sarvam STT (Bengali, modes, pricing): https://www.sarvam.ai/apis/speech-to-text/bengali · https://docs.sarvam.ai/api/getting-started/models/saaras · https://www.callmissed.com/blog/sarvam-saaras-v3-review-why-india-s-stt-outshines-global-speech-to-text-models
- Soniox Bengali + context: https://soniox.com/speech-to-text/bengali · https://soniox.com/docs/stt/concepts/context
- Gemini pricing and long-audio issues: https://www.morphllm.com/gemini-api-pricing · https://discuss.ai.google.dev/t/how-to-get-consistent-multi-speaker-transcription-output-from-gemini-2-5-pro/87391
- BuzzASR: https://arxiv.org/abs/2609.09554 · https://huggingface.co/BuzzASR/bengali
- IndicConformer: https://github.com/AI4Bharat/IndicConformerASR · Omnilingual ASR: https://ai.meta.com/blog/omnilingual-asr-advancing-automatic-speech-recognition/
- Bangla long-form ASR / DL Sprint 4.0: https://arxiv.org/abs/2605.08214 · https://arxiv.org/abs/2602.23070 · https://arxiv.org/abs/2603.03158
- OOD-Speech: https://arxiv.org/abs/2305.09688 · MUCS 2021: https://openslr.org/104/ · Bengali Common Voice: https://arxiv.org/abs/2206.14053
- Whisper tokenizer issues for Indic languages: https://arxiv.org/abs/2412.19785
- Competitors: https://soniox.com/soniox-app/for/university-students-lecture-transcription-in-bengali · https://hearlog.ai/transcribe/bengali · https://apps.apple.com/us/app/-/id6502794561
- Bangladesh students (UGC): https://en.prothomalo.com/amp/story/youth/education/6pij7mhk3g
- PDPO 2025: https://www.thedailystar.net/tech-startup/news/bangladeshs-personal-data-protection-ordinance-2025-key-takeaways-4015401 · https://ccianet.org/wp-content/uploads/2026/04/CCIA-Views-on-Bangladeshs-Personal-Data-Protection-Ordinance.pdf
- bKash tokenized/recurring: https://www.photonpay.com/hk/blog/article/payment-methods-in-Bangladesh
- Android 14 microphone foreground service: https://omr.it.com/blog/foreground-service-lifecycle-android-14-constraints/
- Bangla PDF shaping: https://pub.dev/packages/pdf_text_shaper
