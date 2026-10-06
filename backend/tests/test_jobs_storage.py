from __future__ import annotations

import time
from datetime import timedelta
from pathlib import Path

import pytest
from sqlalchemy.orm import Session

from rafkhata import jobs
from rafkhata.db import utcnow
from rafkhata.models import JOB_FAILED, JOB_QUEUED, JOB_RUNNING, JOB_SUCCEEDED, Job
from rafkhata.storage import LocalStorage


def test_enqueue_dedupes_same_payload(db: Session) -> None:
    a = jobs.enqueue(db, jobs.DELETE_PREFIX, payload={"prefix": "lectures/x/"})
    b = jobs.enqueue(db, jobs.DELETE_PREFIX, payload={"prefix": "lectures/x/"})
    c = jobs.enqueue(db, jobs.DELETE_PREFIX, payload={"prefix": "lectures/y/"})
    db.commit()
    assert a.id == b.id
    assert c.id != a.id


def test_claim_retry_backoff_and_failure(db: Session) -> None:
    job = jobs.enqueue(db, jobs.DELETE_PREFIX, payload={"prefix": "p/"}, max_attempts=2)
    db.commit()

    claimed = jobs.claim_next(db, "w1", lease_s=60)
    assert claimed is not None and claimed.id == job.id
    assert claimed.status == JOB_RUNNING and claimed.attempts == 1
    assert jobs.claim_next(db, "w2", lease_s=60) is None

    assert jobs.mark_failed(db, claimed, "boom") is True
    assert claimed.status == JOB_QUEUED
    assert claimed.run_after > utcnow() + timedelta(seconds=20)
    assert jobs.claim_next(db, "w1", lease_s=60) is None  # not due yet

    claimed.run_after = utcnow() - timedelta(seconds=1)
    db.commit()
    again = jobs.claim_next(db, "w1", lease_s=60)
    assert again is not None and again.attempts == 2
    assert jobs.mark_failed(db, again, "boom again") is False
    assert again.status == JOB_FAILED


def test_expired_lease_is_requeued(db: Session) -> None:
    job = jobs.enqueue(db, jobs.DELETE_PREFIX, payload={"prefix": "q/"})
    db.commit()
    claimed = jobs.claim_next(db, "dead-worker", lease_s=60)
    claimed.locked_at = utcnow() - timedelta(seconds=120)
    db.commit()
    reclaimed = jobs.claim_next(db, "w2", lease_s=60)
    assert reclaimed is not None and reclaimed.id == job.id and reclaimed.locked_by == "w2"
    jobs.mark_succeeded(db, reclaimed)
    assert db.get(Job, job.id).status == JOB_SUCCEEDED


def test_backoff_caps() -> None:
    assert jobs.backoff_seconds(1) == 30
    assert jobs.backoff_seconds(3) == 120
    assert jobs.backoff_seconds(20) == 1800


def test_local_storage_signatures_and_paths(tmp_path: Path) -> None:
    storage = LocalStorage(tmp_path / "s", "http://x", "secret")
    url = storage.presign_get("lectures/a/audio.m4a", 60)
    exp = int(url.split("exp=")[1].split("&")[0])
    sig = url.split("sig=")[1]
    assert storage.verify("GET", "lectures/a/audio.m4a", exp, sig)
    assert not storage.verify("PUT", "lectures/a/audio.m4a", exp, sig)
    assert not storage.verify("GET", "lectures/b/audio.m4a", exp, sig)
    assert not storage.verify("GET", "lectures/a/audio.m4a", int(time.time()) - 1, sig)

    for bad in ("../etc/passwd", "/abs", "a/../../b", ""):
        with pytest.raises(ValueError):
            storage.path_for(bad)

    storage.put_bytes("lectures/a/segments/0000.aac", b"123", "audio/aac")
    storage.put_bytes("lectures/a/segments/0001.aac", b"4567", "audio/aac")
    assert storage.size("lectures/a/segments/0001.aac") == 4
    assert storage.size("lectures/a/segments/0009.aac") is None
    assert storage.delete_prefix("lectures/a/") == 2
    assert storage.size("lectures/a/segments/0000.aac") is None
