"""Smoke-test a running Raf-Khata backend the way the app uses it.

    uv run python scripts/smoke.py http://localhost:8000 --email you@example.com

Needs DEV_LOGIN_ENABLED=true on the server (or pass --token with an access token) and ffmpeg locally.
It records nothing: it generates a 1.5-minute test tone, uploads it as two segments, finalizes, and
waits for a worker to produce the transcript, notes and study pack.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

import httpx


def make_segment(path: Path, seconds: int) -> bytes:
    expr = "0.4*sin(2*PI*220*t)*lt(mod(t\\,6)\\,2.5)"
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-f", "lavfi", "-i", f"aevalsrc={expr}:s=16000:d={seconds}",
         "-c:a", "aac", "-b:a", "32k", "-ac", "1", "-f", "adts", str(path)],
        check=True,
    )  # fmt: skip
    return path.read_bytes()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("base_url")
    parser.add_argument("--email", default="smoke-test@example.com")
    parser.add_argument("--token", help="access token (instead of dev login)")
    parser.add_argument("--timeout", type=int, default=600, help="seconds to wait for processing")
    args = parser.parse_args()

    api = httpx.Client(base_url=args.base_url.rstrip("/"), timeout=60)
    print("health:", api.get("/healthz").json())
    token = (
        args.token
        or api.post("/auth/dev-login", json={"email": args.email, "name": "Smoke Test"}).json()["access_token"]
    )
    api.headers["Authorization"] = f"Bearer {token}"

    course = api.post("/courses", json={"title": "Smoke Test Course", "glossary": ["deadlock"]}).json()
    lecture_id = str(uuid.uuid4())
    lecture = api.put(
        f"/lectures/{lecture_id}", json={"course_id": course["id"], "title": "Smoke test", "consent_confirmed": True}
    ).json()
    print("lecture:", lecture["id"], lecture["status"])

    with tempfile.TemporaryDirectory() as tmp:
        for idx in range(2):
            data = make_segment(Path(tmp) / f"{idx}.aac", 45)
            url = api.post(f"/lectures/{lecture_id}/segments/{idx}/upload-url", json={"size_bytes": len(data)}).json()
            put = httpx.put(url["url"], content=data, headers=url["headers"], timeout=120)
            put.raise_for_status()
            api.post(
                f"/lectures/{lecture_id}/segments/{idx}/complete", json={"size_bytes": len(data), "duration_s": 45}
            ).raise_for_status()
            print(f"uploaded segment {idx} ({len(data)} bytes)")

    final = api.post(f"/lectures/{lecture_id}/finalize", json={"segment_count": 2, "duration_s": 90}).json()
    print("finalized:", final["status"])

    deadline = time.time() + args.timeout
    while time.time() < deadline:
        lecture = api.get(f"/lectures/{lecture_id}").json()
        print(f"  status={lecture['status']} step={lecture['status_detail']} progress={lecture['progress']}")
        if lecture["status"] in ("ready", "failed"):
            break
        time.sleep(3)
    if lecture["status"] != "ready":
        print("FAILED:", lecture.get("error"))
        return 1

    transcript = api.get(f"/lectures/{lecture_id}/transcript").json()
    notes = api.get(f"/lectures/{lecture_id}/notes").json()
    study = api.get(f"/lectures/{lecture_id}/study").json()
    audio = api.get(f"/lectures/{lecture_id}/audio-url").json()
    print(f"transcript: {len(transcript['segments'])} lines; first: {transcript['segments'][0]['text']}")
    print(f"notes: {notes['content']['title']!r}, {len(notes['html'])} chars of HTML")
    print(f"study: {len(study['content']['flashcards'])} flashcards, {len(study['content']['mcqs'])} MCQs")
    print(f"audio: {httpx.get(audio['url'], timeout=60).status_code}")
    api.delete(f"/lectures/{lecture_id}")
    api.delete(f"/courses/{course['id']}")
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
