"""Background worker: claims jobs from the Postgres queue and runs them."""

from __future__ import annotations

import logging
import os
import signal
import socket
import time
import traceback

from rafkhata import jobs
from rafkhata.config import Settings, get_settings
from rafkhata.db import make_engine, make_sessionmaker
from rafkhata.models import LECTURE_FAILED, LECTURE_QUEUED, Job, Lecture
from rafkhata.pipeline.process import (
    PipelineContext,
    make_context,
    process_lecture,
    purge_segments,
    regenerate_notes,
)
from rafkhata.storage import make_storage

log = logging.getLogger(__name__)


def run_job(ctx: PipelineContext, job: Job) -> None:
    if job.kind == jobs.PROCESS_LECTURE:
        process_lecture(ctx, job.lecture_id)
    elif job.kind == jobs.REGENERATE_NOTES:
        regenerate_notes(ctx, job.lecture_id, job.payload["lang"])
    elif job.kind == jobs.DELETE_PREFIX:
        ctx.storage.delete_prefix(job.payload["prefix"])
    elif job.kind == jobs.PURGE_SEGMENTS:
        purge_segments(ctx, job.lecture_id)
    else:
        raise ValueError(f"unknown job kind {job.kind}")


def _friendly(exc: BaseException) -> str:
    return str(exc).strip()[:500] or exc.__class__.__name__


def work_once(ctx: PipelineContext, worker_id: str) -> bool:
    """Claim and run one job. Returns False when the queue had nothing runnable."""
    with ctx.sessionmaker() as db:
        job = jobs.claim_next(db, worker_id, ctx.settings.job_lease_s)
        if job is None:
            return False
        job_id, kind, lecture_id = job.id, job.kind, job.lecture_id

    log.info("running job %s (%s) for lecture %s", job_id, kind, lecture_id)
    try:
        with ctx.sessionmaker() as db:
            run_job(ctx, db.get(Job, job_id))
    except Exception as exc:
        permanent = bool(getattr(exc, "permanent", False))
        log.error("job %s failed%s: %s", job_id, " permanently" if permanent else "", exc)
        log.debug("%s", traceback.format_exc())
        with ctx.sessionmaker() as db:
            job = db.get(Job, job_id)
            retrying = jobs.mark_failed(db, job, f"{exc.__class__.__name__}: {exc}", retry=not permanent)
            if kind == jobs.PROCESS_LECTURE and lecture_id is not None:
                lecture = db.get(Lecture, lecture_id)
                if lecture is not None:
                    lecture.error = _friendly(exc)
                    if retrying:
                        lecture.status = LECTURE_QUEUED
                        lecture.status_detail = "retrying"
                    else:
                        lecture.status = LECTURE_FAILED
                        lecture.status_detail = None
                    db.commit()
        return True

    with ctx.sessionmaker() as db:
        jobs.mark_succeeded(db, db.get(Job, job_id))
    log.info("job %s done", job_id)
    return True


def run_worker(once: bool = False, settings: Settings | None = None) -> int:
    settings = settings or get_settings()
    engine = make_engine(settings.database_url)
    ctx = make_context(settings, make_sessionmaker(engine), make_storage(settings))
    worker_id = f"{socket.gethostname()}-{os.getpid()}"
    stopping = False

    def stop(signum, _frame) -> None:
        nonlocal stopping
        log.info("signal %s received; finishing the current job", signum)
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    log.info("worker %s started (ASR=%s, LLM=%s)", worker_id, settings.asr_primary, settings.llm_provider)
    while not stopping:
        did_work = work_once(ctx, worker_id)
        if not did_work:
            if once:
                break
            time.sleep(settings.worker_poll_interval_s)
    engine.dispose()
    return 0
