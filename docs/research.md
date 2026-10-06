# Research notes (October 2026)

Background research behind [`PLAN.md`](PLAN.md). Vendor numbers change often, so re-check them before
making purchase decisions. Figures marked *vendor claim* come from the vendor's own marketing.

## 1. Bangla speech recognition benchmarks

### 1.1 Riverbornai Bangla Speech-to-Text Benchmark (September 2026)
1,001 real Bangla clips from 13 domains (audiobooks, biography, celebrity interviews, class lectures,
documentaries, drama, kids' content, medicine, parliament, political talk shows, sports, TV news, ...).
That is 50 minutes in total, about 3 seconds per clip, 7,996 reference words, 16 kHz WAV.

Normalization, applied to both reference and output: Unicode NFC; zero-width joiners/non-joiners removed;
danda and punctuation removed; Bangla digits mapped to ASCII; Latin text lowercased; whitespace collapsed.

**Batch:**

| Provider | Model | CER | WER | Class-lecture CER |
|---|---|---|---|---|
| Sarvam AI | saarika:v2.5 | **6.9%** | **18.2%** | **6.0%** |
| Soniox | stt-async-v5 | 8.1% | 21.5% | 12.2% |
| Deepgram | nova-3 | 10.1% | 23.9% | 12.4% |
| Google Gemini | gemini-2.5-flash | 13.3% | 27.0% | 11.3% |
| ElevenLabs | scribe_v1 | 14.7% | 26.0% | 20.8% |
| Google Cloud | chirp_2 | 21.9% | 41.4% | 17.4% |
| OpenAI | gpt-4o-transcribe | 28.7% | 45.1% | 33.9% |
| Groq | whisper-large-v3 | 30.7% | 71.7% | 34.1% |

**Streaming:**

| Provider | Model | CER | WER | Class-lecture CER | p50 time to final |
|---|---|---|---|---|---|
| Soniox | stt-rt-v5 | **7.4%** | **19.7%** | 13.2% | 2.46 s |
| Sarvam AI | saaras:v3-realtime | 9.0% | 20.3% | **6.2%** | **0.88 s** |
| Deepgram | nova-3 | 17.5% | 33.3% | 25.0% | 1.55 s |
| Google Cloud | chirp_2 | 23.6% | 43.8% | 18.8% | 2.17 s |
| OpenAI | gpt-4o-transcribe | 24.6% | 42.4% | 37.1% | 4.22 s |
| ElevenLabs | scribe_v2_realtime | 38.6% | 55.4% | 43.4% | 1.36 s |
| Google Gemini | 2.5-flash native audio | 69.6% | 79.2% | 76.4% | 10.44 s |

**Price per audio hour (as published with the benchmark):**
- Soniox $0.10
- Groq $0.11 (about 3.4× in practice because of a 10-second minimum per request)
- Sarvam about $0.31 (₹30)
- Deepgram $0.26–0.46
- Google Chirp 2 $0.96

**Caveats:**
- The clips are short.
- Phone-call audio, heavy background noise and code-switched conversational speech are excluded.
- Long-form behaviour (skipped or repeated passages over 60–90 minutes) is not tested.

### 1.2 Voice of India (IIT Madras, 2026)
536 hours of unscripted telephone speech from 36,691 speakers, covering 15 Indian languages.
- Bengali WER: Gemini 3 Pro about 8.5%, ElevenLabs Scribe v2 about 8–10%, Gemini 3 Flash about 12.6%.
- Sarvam (Saaras v3 / Sarvam Audio) is #1 or #2 on most languages, Bengali included.
- OpenAI's transcription models trail badly.
- Error rises steadily as audio quality drops. For example, Scribe goes from 15.3% to 25.2% between the
  best and worst quality quartiles.

### 1.3 Vendor claims
- ElevenLabs Scribe on Bengali FLEURS: 3.1% (v2) and 8.1% (v1) WER. *Vendor claim.*
- Soniox async Bengali WER: 6.3%. *Vendor claim.*
- Sarvam Saaras v3: about 19.3% WER averaged over the IndicVoices top-10 languages, ahead of GPT-4o
  Transcribe, Scribe v2, Gemini 3 Pro and Nova-3. Trained on 1M+ hours of Indian audio, with attention
  to noisy and code-mixed speech. *Vendor claim.*

### 1.4 Open models
| Model | What it is | Bangla result |
|---|---|---|
| BuzzASR/bengali (EMNLP 2026) | Whisper-large-v3, fully fine-tuned with a native Bengali tokenizer; open weights | CER/WER 5.5/19.4 on Common Voice 25; 10.2/32.0 on FLEURS |
| AI4Bharat IndicConformer | 120M Bengali model or 600M multilingual; hybrid CTC-RNNT; fast; no hallucination | Indian Bengali; outputs Bengali script only, so English words are transliterated |
| Meta Omnilingual ASR | wav2vec2-based, up to 7B, 1,600+ languages, Apache-2.0 | 78% of languages have CER < 10 (no Bengali-specific figure published) |
| Fine-tuned Whisper-medium (DL Sprint 4.0, BUET CSE Fest 2026) | Long-form Bangla with diarization, 22-hour hidden test | WER 0.24–0.38; DER about 0.24–0.27 |

Stock Whisper splits Bangla into far too many tokens, which makes it slow and error-prone. Fixing the
tokenizer is a large part of why BuzzASR improves on it.

## 2. Provider notes for integration
- **Sarvam:**
  - Models `saarika:v2.5` and `saaras:v3`.
  - Saaras v3 modes: `transcribe`, `translate`, `verbatim`, `translit` (romanized) and `codemix`
    (English words in Latin script, Indic words in native script).
  - The REST endpoint takes up to 30 seconds of audio. The Batch (job) API takes files up to 2 hours,
    with diarization.
  - Pricing is in INR only: about ₹30/hour for transcription, ₹45/hour with translation and diarization.
- **Soniox:**
  - Async and real-time models in one multilingual model, with no language switch needed for
    code-switching.
  - A `context` object holds `general` key/value pairs, free `text`, `terms` and `translation_terms`,
    up to about 8,000 tokens.
  - Speaker diarization supported.
  - $0.10/hour async, $0.12/hour real-time.
- **ElevenLabs Scribe v2** (March 2026):
  - $0.22/hour batch, $0.39/hour real-time.
  - Keyterm prompting (up to 1,000 terms, +$0.05/hour), diarization of up to 32 speakers, entity
    detection.
- **Gemini:**
  - Audio is billed per token; each audio second is about 25–32 tokens, depending on model generation.
  - Long-file transcription shows repetition loops, early stops and, in an August 2026 report, calls
    returning zero output tokens.
  - Recommended workaround: 5–10-minute chunks with overlap, then a second correction pass.
  - Timestamps are imprecise.
- **Deepgram nova-3:** supports Bengali; keyterm prompting; $0.26–0.46/hour.

## 3. Datasets
| Dataset | Size | Content | Notes |
|---|---|---|---|
| OOD-Speech (Bengali.AI) | 1,177.9 h train / 23 h test | 22,645 speakers; test set from 17 sources incl. online classes, TV drama, sermons | Out-of-distribution benchmark |
| Bengali Common Voice | about 399 h (CV 9.0), growing | Read sentences, 19,817 contributors | CC0 |
| MUCS 2021 Bengali-English | 46.11 h train / 7.02 h test | Code-switched technical "spoken tutorials" | OpenSLR 104. Closest public match to Banglish lectures |
| Lipi-Ghor-882 | 882 h | Multi-speaker long-form Bengali (DL Sprint 4.0) | Check license |
| OpenSLR 53 | Large | Read Bengali (Google) | |
| IndicVoices / Kathbath / Shrutilipi | Varies | Indian Bengali | AI4Bharat |
| BRADS / BRWDS, Bangla Regional Dialects (UAP) | Small | Regional words and dialects | Useful for dialect checks |

No public dataset of Bangladeshi university lectures exists.

## 4. LLM (Claude) for correction and notes
- Claude does not take audio input, so speech recognition is a separate stage.
- **Prices per 1M tokens** (input/output):
  - Claude Opus 5.5 (`claude-opus-5-5`): $4/$20; cache reads $0.20; 1M context.
  - Claude Sonnet 5.5 (`claude-sonnet-5-5`): $2/$10; cache reads $0.20; 1M context.
  - Claude Haiku 4.5 (`claude-haiku-4-5`): $1/$5; 200K context.
  - Batch API: 50% off. Cache writes cost about 1.25× input.
- **API behaviour to plan for:**
  - Structured outputs via `output_config.format`; the Python helper is `client.messages.parse()` with
    Pydantic models.
  - Claude Opus 5.5 always thinks. Effort defaults to `medium` and is set with
    `output_config.effort`.
  - Safety classifiers can decline a request (`stop_reason: "refusal"`). Server-side
    `fallbacks: "default"` (beta `server-side-fallback-2026-07-01`) re-runs a declined request on a
    recommended model. This matters for medicine and microbiology lectures.

## 5. Bangladesh context
- **Students:** about 4.82M in tertiary education (UGC, 2023 data), 3.4M of them at National University
  affiliated colleges (70%). 53 public universities in operation and 114 private.
- **Competitors:**
  - Soniox App: Bengali lecture transcription, weekly free credits.
  - Hearlog: 200 free minutes a month; claims Bangladeshi Bangla and Banglish support.
  - HappyScribe, NoteGPT, TurboScribe.
  - Global study apps: Turbo AI (5M+ users), Coconote.
  - None is built around Bangladeshi university workflows.
- **Payments:** bKash has 70M+ users and handles more than 60% of online payments. Tokenized checkout
  supports recurring "agreements". Aggregators such as SSLCommerz cover bKash, Nagad and cards.
- **Law:** Personal Data Protection Ordinance 2025 (approved Oct 2025, enacted Nov 2025, revised Mar
  2026):
  - Explicit consent; notice of purpose, retention and transfer; rights of access and withdrawal.
  - The general localization mandate was removed in March 2026. "Confidential"/"restricted" data must
    stay in Bangladesh.
  - Cross-border transfers require an "adequate" destination, and the list is not yet published.
- **Android:**
  - Android 14+ requires the `microphone` foreground-service type, and the service must start while the
    app is visible.
  - Budget phone makers (Xiaomi, Realme, Oppo, Vivo, Infinix, Tecno) aggressively kill background work.
- **PDF:** many PDF libraries break Bangla conjuncts. HarfBuzz-based rendering is needed: Chromium,
  WeasyPrint, or Android's WebView printing.

## 6. Bangla text handling (used by the normalizer)
- **Nukta letters** ড় (U+09DC), ঢ় (U+09DD) and য় (U+09DF) are Unicode composition exclusions. NFC
  therefore turns them *into* base + nukta (U+09BC). Always compare text after the same normalization.
- **Two-part vowel signs:** ো (U+09CB) and ৌ (U+09CC) have canonical decompositions, and NFC
  recomposes them.
- **Khanda ta** ৎ (U+09CE) is sometimes typed as ত + hasanta + ZWJ.
- **Joiners:** ZWJ/ZWNJ change rendering (e.g. র‍্য vs র্য). Keep them for display, drop them for scoring
  and search.
- **Typing slips:** "অ + া" is a common slip for "আ", and a doubled hasanta (্্) is invalid.
- **Danda:** "।" (U+0964) is often typed as "|".
- **Digits:** Bangla digits ০-৯ (U+09E6–U+09EF) are mapped to ASCII for scoring and search.

## Sources
- Riverbornai benchmark: https://github.com/riverbornai/bangla-speech-to-text-benchmark
- Voice of India: https://arxiv.org/abs/2604.19151 · https://cxotoday.com/media-coverage/global-speech-ai-struggles-to-understand-india-new-national-benchmark-voice-of-india-reveals/
- ElevenLabs Bengali: https://elevenlabs.io/speech-to-text/bengali · Scribe v2 pricing: https://andrew.ooo/answers/best-speech-to-text-apis-2026-ranked/
- Sarvam: https://www.sarvam.ai/apis/speech-to-text/bengali · https://docs.sarvam.ai/api/getting-started/models/saaras · https://www.callmissed.com/blog/sarvam-saaras-v3-review-why-india-s-stt-outshines-global-speech-to-text-models
- Soniox: https://soniox.com/speech-to-text/bengali · https://soniox.com/docs/stt/concepts/context
- Gemini: https://www.morphllm.com/gemini-api-pricing · https://discuss.ai.google.dev/t/how-to-get-consistent-multi-speaker-transcription-output-from-gemini-2-5-pro/87391
- BuzzASR: https://arxiv.org/abs/2609.09554 · https://huggingface.co/BuzzASR/bengali
- IndicConformer: https://github.com/AI4Bharat/IndicConformerASR · Omnilingual ASR: https://ai.meta.com/blog/omnilingual-asr-advancing-automatic-speech-recognition/
- Long-form Bangla ASR / DL Sprint 4.0: https://arxiv.org/abs/2605.08214 · https://arxiv.org/abs/2602.23070 · https://arxiv.org/abs/2603.03158
- OOD-Speech: https://arxiv.org/abs/2305.09688 · MUCS 2021: https://openslr.org/104/ · Bengali Common Voice: https://arxiv.org/abs/2206.14053
- Whisper tokenization for Indic languages: https://arxiv.org/abs/2412.19785
- Competitors: https://soniox.com/soniox-app/for/university-students-lecture-transcription-in-bengali · https://hearlog.ai/transcribe/bengali · https://apps.apple.com/us/app/-/id6502794561
- UGC figures: https://en.prothomalo.com/amp/story/youth/education/6pij7mhk3g
- PDPO 2025: https://www.thedailystar.net/tech-startup/news/bangladeshs-personal-data-protection-ordinance-2025-key-takeaways-4015401 · https://ccianet.org/wp-content/uploads/2026/04/CCIA-Views-on-Bangladeshs-Personal-Data-Protection-Ordinance.pdf
- bKash: https://www.photonpay.com/hk/blog/article/payment-methods-in-Bangladesh
- Android 14 foreground services: https://omr.it.com/blog/foreground-service-lifecycle-android-14-constraints/
- Bangla PDF shaping: https://pub.dev/packages/pdf_text_shaper
