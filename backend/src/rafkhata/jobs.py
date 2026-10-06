"""A small Postgres-backed job queue (no Redis).

Workers claim one job at a time with `SELECT ... FOR UPDATE SKIP LOCKED`, so several workers can run
safely. Failed jobs are retried with exponential backoff. Jobs whose worker died are re-queued once
their lease expires.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from rafkhata.db import utcnow
from rafkhata.models import JOB_FAILED, JOB_QUEUED, JOB_RUNNING, JOB_SUCCEEDED, Job

PROCESS_LECTURE = "process_lecture"
REGENERATE_NOTES = "regenerate_notes"
DELETE_PREFIX = "delete_prefix"
PURGE_SEGMENTS = "purge_segments"


def backoff_seconds(attempt: int) -> int:
    """30 s, 60 s, 120 s ... capped at 30 minutes."""
    return min(30 * 2 ** max(attempt - 1, 0), 1800)


def enqueue(
    db: Session,
    kind: str,
    *,
    lecture_id: uuid.UUID | None = None,
    payload: dict[str, Any] | None = None,
    run_after: datetime | None = None,
    max_attempts: int = 4,
    dedupe: bool = True,
) -> Job:
    """Add a job unless an identical one is already queued or running."""
    payload = payload or {}
    if dedupe:
        existing = db.scalars(
            select(Job).where(
                Job.kind == kind,
                Job.lecture_id == lecture_id if lecture_id is not None else Job.lecture_id.is_(None),
                Job.status.in_((JOB_QUEUED, JOB_RUNNING)),
            )
        ).all()
        for job in existing:
            if (job.payload or {}) == payload:
                return job
    job = Job(
        kind=kind,
        lecture_id=lecture_id,
        payload=payload,
        run_after=run_after or utcnow(),
        max_attempts=max_attempts,
        status=JOB_QUEUED,
    )
    db.add(job)
    db.flush()
    return job


def pending_job(db: Session, kind: str, lecture_id: uuid.UUID, **payload_match: Any) -> Job | None:
    jobs = db.scalars(
        select(Job).where(Job.kind == kind, Job.lecture_id == lecture_id, Job.status.in_((JOB_QUEUED, JOB_RUNNING)))
    ).all()
    for job in jobs:
        if all((job.payload or {}).get(k) == v for k, v in payload_match.items()):
            return job
    return None


def claim_next(db: Session, worker_id: str, lease_s: int) -> Job | None:
    """Claim the oldest runnable job and mark it running. Commits."""
    now = utcnow()
    db.execute(
        update(Job)
        .where(Job.status == JOB_RUNNING, Job.locked_at < now - timedelta(seconds=lease_s))
        .values(status=JOB_QUEUED, locked_by=None, locked_at=None)
    )
    stmt = select(Job).where(Job.status == JOB_QUEUED, Job.run_after <= now).order_by(Job.run_after, Job.created_at)
    if db.get_bind().dialect.name == "postgresql":
        stmt = stmt.with_for_update(skip_locked=True)
    job = db.scalars(stmt.limit(1)).first()
    if job is None:
        db.commit()
        return None
    job.status = JOB_RUNNING
    job.locked_by = worker_id
    job.locked_at = now
    job.attempts += 1
    db.commit()
    return job


def mark_succeeded(db: Session, job: Job) -> None:
    job.status = JOB_SUCCEEDED
    job.finished_at = utcnow()
    job.locked_by = None
    job.last_error = None
    db.commit()


def mark_failed(db: Session, job: Job, error: str, *, retry: bool = True) -> bool:
    """Record a failure. Returns True if the job will be retried."""
    job.last_error = error[-4000:]
    job.locked_by = None
    job.locked_at = None
    if retry and job.attempts < job.max_attempts:
        job.status = JOB_QUEUED
        job.run_after = utcnow() + timedelta(seconds=backoff_seconds(job.attempts))
        db.commit()
        return True
    job.status = JOB_FAILED
    job.finished_at = utcnow()
    db.commit()
    return False
