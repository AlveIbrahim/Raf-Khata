# API reference

Base URL: wherever the backend runs (e.g. `https://api.example.com`). Interactive docs are served at
`/docs` (OpenAPI), which is generated from the code and always up to date.

- **Auth:** `Authorization: Bearer <access_token>` on every endpoint except `/auth/*`, `/healthz` and
  `/files/*`.
- **Errors:** `{"detail": "..."}` with the HTTP status. A 422 carries validation details.
- **Time:** ISO-8601 UTC. Durations and offsets are in seconds.
- **Notes language** (`lang`): `bn` (বাংলা), `en` (English), or `mixed` (Banglish).

## Sign-in
| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/auth/google` | `{id_token}`, a Google ID token from Credential Manager | `TokenOut` |
| POST | `/auth/refresh` | `{refresh_token}` | `TokenOut` (the old refresh token is revoked) |
| POST | `/auth/logout` | `{refresh_token}` | 204 |
| POST | `/auth/dev-login` | `{email, name}` | `TokenOut`. Only when `DEV_LOGIN_ENABLED=true` |

`TokenOut = {access_token, refresh_token, token_type, expires_in, user}`.

- Access tokens last 1 hour.
- Refresh tokens last 60 days and rotate on every use.
- Presenting an already-rotated refresh token signs the user out everywhere.

## Me
| Method | Path | Notes |
|---|---|---|
| GET | `/me` | Profile |
| PATCH | `/me` | `name, university, department, batch, section, notes_lang, onboarded` |
| GET | `/me/export` | JSON download of all the user's data; audio links valid 24 h |
| DELETE | `/me` | Deletes the account and everything the user recorded. Owned sections and section courses pass to another member |

## Sections
| Method | Path | Notes |
|---|---|---|
| POST | `/spaces` | `{name, university?, section_label?}`. The creator becomes `owner` and gets an 8-character `invite_code` |
| POST | `/spaces/join` | `{invite_code}` (case-insensitive) |
| GET | `/spaces` | The user's sections with their role and member count |
| GET | `/spaces/{id}` | Includes `members[]` |
| POST | `/spaces/{id}/invite-code` | Rotate the code (owner/CR) |
| PATCH | `/spaces/{id}/members/{user_id}` | `{role: "cr" or "member"}` (owner only) |
| DELETE | `/spaces/{id}/members/me` | Leave. Ownership passes to the CR or the oldest member; an empty section is deleted |

## Courses
| Method | Path | Notes |
|---|---|---|
| GET | `/courses?include_archived=` | Own courses plus courses of the user's sections |
| POST | `/courses` | `{title, code?, teacher_name?, semester?, space_id?, glossary[], notes_lang?, routine[]}` |
| GET / PATCH / DELETE | `/courses/{id}` | Edit: owner or section owner/CR. Delete: owner |
| PUT | `/courses/{id}/routine` | `{slots: [{weekday 0=Mon..6=Sun, start_time "HH:MM", end_time, room?}]}` |
| POST | `/courses/{id}/consent` | The current user confirms the teacher allowed recording |

## Lectures: upload flow
The app generates the lecture UUID, so every step can be retried safely.

1. `PUT /lectures/{id}` with `{course_id?, title, started_at?, consent_confirmed, notes_lang?}`. This
   creates the lecture, or updates it while it is still uploading.
2. For each 5-minute segment `n`:
   - `POST /lectures/{id}/segments/{n}/upload-url` with `{size_bytes, content_type: "audio/aac"}`.
     Returns `{url, method: "PUT", headers, expires_at}`.
   - `PUT <url>` with the file bytes and the returned headers. This goes straight to storage.
   - `POST /lectures/{id}/segments/{n}/complete` with `{size_bytes, duration_s}`.
3. Photos:
   - `POST /lectures/{id}/photos` with `{photo_id, t_offset_s, content_type, size_bytes}`, returning a
     presigned URL.
   - `PUT` the image.
   - `POST /lectures/{id}/photos/{photo_id}/complete`.
4. Bookmarks: `PUT /lectures/{id}/bookmarks` with
   `{bookmarks: [{t_offset_s, kind: important|confused|note, text?}]}`. This replaces the user's
   bookmarks on the lecture.
5. `POST /lectures/{id}/finalize` with `{segment_count, duration_s}`.
   - Returns 409 with `detail.missing` if any segment wasn't confirmed.
   - Otherwise the status becomes `queued` and processing starts.

`t_offset_s` and segment durations count **recorded** time, excluding pauses, so they line up with
the joined audio.

**Lecture `status`:**
- `created` → `uploading` → `queued` → `processing` → `ready`, or `failed`.
- While processing, `status_detail` names the current step and `progress` runs from 0 to 100.

| Method | Path | Notes |
|---|---|---|
| GET | `/lectures?course_id=&space_id=&before=&limit=` | Newest first; own lectures plus the user's sections' lectures |
| GET | `/lectures/{id}` | `LectureOut`, including `notes_langs[]` and `has_study` |
| GET | `/lectures/{id}/audio-url` | Presigned URL of the processed `.m4a` |
| POST | `/lectures/{id}/retry` | Re-queue a failed lecture (recorder only) |
| DELETE | `/lectures/{id}` | Recorder only. Storage is cleaned up in the background |

## Lecture content
| Method | Path | Notes |
|---|---|---|
| GET | `/lectures/{id}/transcript` | `{segments: [{id, start, end, speaker T/S, text}], bookmarks, photos (with URLs), can_edit}` |
| PATCH | `/lectures/{id}/transcript/segments/{segment_id}` | `{text}`. Recorder or section owner/CR |
| GET | `/lectures/{id}/notes?lang=` | `{content, html, markdown, ...}`. Returns **202** `{status: "pending"}` while being generated, or 404 if never requested |
| POST | `/lectures/{id}/notes/regenerate` | `{lang}`. Queues notes + study pack in that language → 202 |
| GET | `/lectures/{id}/study?lang=` | `{content: {flashcards, mcqs, questions}}`. 202 while pending |
| POST | `/lectures/{id}/feedback` | `{kind: rating or report, rating 1–5?, comment?, lang?}` |

Notes `content` (JSON), with every item citing transcript `source_segment_ids`:
- Overview: `title`, `summary[]`, `sections[{title, start_segment_id, bullets[{text, source_segment_ids}]}]`.
- Reference: `definitions[]`, `formulas[{latex, meaning}]`, `examples[]`.
- Class items: `exam_alerts[]`, `announcements[{kind, text, due_date (ISO or null), due_text}]`,
  `class_questions[]`, `unclear_parts[]`.

The `html` field is a fragment. The app shows it inside its own shell, which supplies the styles and
KaTeX for `$…$` maths. Timestamp links use the form `rafkhata://seek?t=<seconds>`.

## Other
| Method | Path | Notes |
|---|---|---|
| GET | `/search?q=&limit=` | Transcript and notes hits with snippet, `segment_id`, `t_start`. Digits, punctuation and case are ignored |
| GET | `/deadlines?include_past=` | Announced CTs, assignments and quizzes. Dated ones from today on, undated ones from the last 14 days |
| POST | `/devices` | `{fcm_token, platform, app_version?, locale?}` |
| DELETE | `/devices/{fcm_token}` | Unregister |
| GET | `/healthz` | Liveness and database check |
