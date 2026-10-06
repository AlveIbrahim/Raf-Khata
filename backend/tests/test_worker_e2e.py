"""End to end: the app's API calls -> worker -> transcript, notes, study pack, deadlines, push.

Uses real ffmpeg on generated audio, the mock speech recognizer and the fake LLM.
"""

from __future__ import annotations

import uuid
from datetime import timedelta
from pathlib import Path

import pytest
from audio_fixtures import make_adts
from conftest import new_id
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.orm import Session

from rafkhata.db import utcnow
from rafkhata.models import CostEntry, Course, Job, Lecture
from rafkhata.notify import NullNotifier
from rafkhata.pipeline.asr import ASRError
from rafkhata.pipeline.asr.mock import MockASR
from rafkhata.pipeline.llm.fake import FakeLLM
from rafkhata.pipeline.process import PipelineContext
from rafkhata.worker import work_once


@pytest.fixture
def ctx(app: FastAPI) -> PipelineContext:
    return PipelineContext(
        settings=app.state.settings,
        sessionmaker=app.state.sessionmaker,
        storage=app.state.storage,
        llm=FakeLLM(),
        notifier=NullNotifier(),
    )


def drain(ctx: PipelineContext) -> int:
    count = 0
    while work_once(ctx, "test-worker"):
        count += 1
    return count


def upload_lecture(client: TestClient, headers: dict, tmp_path: Path, course_id: str | None) -> str:
    lecture_id = new_id()
    client.put(
        f"/lectures/{lecture_id}",
        headers=headers,
        json={
            "course_id": course_id,
            "title": "Deadlock",
            "consent_confirmed": True,
            "started_at": "2026-10-06T04:00:00Z",
        },
    )
    for idx in range(2):
        path = make_adts(tmp_path / f"{lecture_id}-{idx}.aac", 40)
        data = path.read_bytes()
        url = client.post(
            f"/lectures/{lecture_id}/segments/{idx}/upload-url", headers=headers, json={"size_bytes": len(data)}
        ).json()
        assert client.put(url["url"], content=data, headers=url["headers"]).status_code == 200
        client.post(
            f"/lectures/{lecture_id}/segments/{idx}/complete",
            headers=headers,
            json={"size_bytes": len(data), "duration_s": 40},
        )
    client.put(
        f"/lectures/{lecture_id}/bookmarks",
        headers=headers,
        json={"bookmarks": [{"t_offset_s": 12, "kind": "important"}]},
    )
    resp = client.post(f"/lectures/{lecture_id}/finalize", headers=headers, json={"segment_count": 2, "duration_s": 80})
    assert resp.status_code == 200, resp.text
    return lecture_id


def test_full_pipeline(client: TestClient, alice, bob, ctx: PipelineContext, db: Session, tmp_path: Path) -> None:
    space = client.post("/spaces", headers=alice, json={"name": "CSE-21 A"}).json()
    client.post("/spaces/join", headers=bob, json={"invite_code": space["invite_code"]})
    course = client.post(
        "/courses",
        headers=alice,
        json={"title": "Operating Systems", "space_id": space["id"], "glossary": ["deadlock"]},
    ).json()
    client.post("/devices", headers=bob, json={"fcm_token": "bob-phone-token-123", "platform": "android"})

    lecture_id = upload_lecture(client, alice, tmp_path, course["id"])
    assert drain(ctx) == 1

    lecture = client.get(f"/lectures/{lecture_id}", headers=bob).json()
    assert lecture["status"] == "ready", lecture
    assert lecture["progress"] == 100
    assert lecture["notes_langs"] == ["bn"]
    assert lecture["has_study"] is True
    assert lecture["quality_score"] is not None

    transcript = client.get(f"/lectures/{lecture_id}/transcript", headers=bob).json()
    texts = [s["text"] for s in transcript["segments"]]
    assert transcript["kind"] == "corrected"
    assert any("banker's" in t or "algorithm" in t for t in texts)  # fake correction applied
    assert {s["speaker"] for s in transcript["segments"]} == {"T", "S"}
    assert all(0 <= s["start"] <= 80.5 for s in transcript["segments"])

    notes = client.get(f"/lectures/{lecture_id}/notes", headers=bob).json()
    assert notes["content"]["exam_alerts"]
    assert "rk-notes" in notes["html"]
    study = client.get(f"/lectures/{lecture_id}/study", headers=bob).json()
    assert study["content"]["flashcards"]

    deadlines = client.get("/deadlines", headers=bob).json()
    ct = next(d for d in deadlines if d["kind"] == "ct")
    assert ct["due_date"] == "2026-10-11"  # "আগামী রবিবার" after a Tuesday lecture

    audio = client.get(f"/lectures/{lecture_id}/audio-url", headers=bob).json()
    assert client.get(audio["url"]).status_code == 200

    hits = client.get("/search", headers=bob, params={"q": "circular wait"}).json()
    assert hits and hits[0]["lecture_id"] == lecture_id

    glossary = db.get(Course, uuid.UUID(course["id"])).glossary
    assert "deadlock" in [g.lower() for g in glossary]
    assert len(glossary) > 1

    costs = db.scalars(select(CostEntry).where(CostEntry.lecture_id == uuid.UUID(lecture_id))).all()
    assert {c.step for c in costs} == {"asr", "correct", "notes_bn", "study_bn"}

    sent = ctx.notifier.sent
    assert sent and sent[0][0] == ["bob-phone-token-123"]
    assert sent[0][3]["lecture_id"] == lecture_id

    # Raw segments are purged later, not now.
    purge = db.scalars(select(Job).where(Job.kind == "purge_segments")).one()
    assert purge.run_after > utcnow() + timedelta(days=29)

    # Another language on request.
    assert client.post(f"/lectures/{lecture_id}/notes/regenerate", headers=bob, json={"lang": "en"}).status_code == 202
    assert client.get(f"/lectures/{lecture_id}/notes?lang=en", headers=bob).status_code == 202
    drain(ctx)
    en = client.get(f"/lectures/{lecture_id}/notes?lang=en", headers=bob)
    assert en.status_code == 200
    assert "Important for exams" in en.json()["html"]
    assert client.get(f"/lectures/{lecture_id}/study?lang=en", headers=bob).status_code == 200


class FlakyASR:
    """Fails the first time with a transient error, then behaves like the mock."""

    name = "flaky"

    def __init__(self) -> None:
        self.calls = 0

    def transcribe(self, request):
        self.calls += 1
        if self.calls == 1:
            raise ASRError("provider timeout")
        return MockASR().transcribe(request)


def test_transient_failure_is_retried(client: TestClient, alice, ctx: PipelineContext, db: Session, tmp_path) -> None:
    flaky = FlakyASR()
    ctx.asr_factory = lambda name, settings: flaky
    lecture_id = upload_lecture(client, alice, tmp_path, None)

    assert drain(ctx) == 1
    lecture = client.get(f"/lectures/{lecture_id}", headers=alice).json()
    assert lecture["status"] == "queued"
    assert "provider timeout" in lecture["error"]

    job = db.scalars(select(Job).where(Job.kind == "process_lecture")).one()
    job.run_after = utcnow() - timedelta(seconds=1)  # skip the backoff wait
    db.commit()
    drain(ctx)
    assert client.get(f"/lectures/{lecture_id}", headers=alice).json()["status"] == "ready"
    assert flaky.calls == 2


def test_permanent_failure_and_manual_retry(client: TestClient, alice, ctx: PipelineContext, tmp_path) -> None:
    def broken(name, settings):
        raise ASRError("SARVAM_API_KEY is not set", permanent=True)

    ctx.asr_factory = broken
    lecture_id = upload_lecture(client, alice, tmp_path, None)
    drain(ctx)
    lecture = client.get(f"/lectures/{lecture_id}", headers=alice).json()
    assert lecture["status"] == "failed"
    assert "SARVAM_API_KEY" in lecture["error"]

    ctx.asr_factory = lambda name, settings: MockASR()
    assert client.post(f"/lectures/{lecture_id}/retry", headers=alice).json()["status"] == "queued"
    drain(ctx)
    assert client.get(f"/lectures/{lecture_id}", headers=alice).json()["status"] == "ready"


def test_deleting_a_lecture_cleans_storage(client: TestClient, alice, ctx: PipelineContext, tmp_path) -> None:
    lecture_id = upload_lecture(client, alice, tmp_path, None)
    drain(ctx)
    storage_dir = Path(ctx.settings.storage_local_dir) / "lectures" / lecture_id
    assert (storage_dir / "audio.m4a").exists()
    client.delete(f"/lectures/{lecture_id}", headers=alice)
    drain(ctx)
    assert not storage_dir.exists()
    with ctx.sessionmaker() as s:
        assert s.get(Lecture, uuid.UUID(lecture_id)) is None
