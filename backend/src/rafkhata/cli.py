"""`rk` command-line entry point: run the API or worker, migrate the database, process files offline."""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path


def _setup_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


def cmd_api(args: argparse.Namespace) -> int:
    import uvicorn

    uvicorn.run(
        "rafkhata.api.app:create_app",
        factory=True,
        host=args.host,
        port=args.port,
        reload=args.reload,
        proxy_headers=True,
    )
    return 0


def cmd_worker(args: argparse.Namespace) -> int:
    from rafkhata.worker import run_worker

    return run_worker(once=args.once)


def cmd_migrate(args: argparse.Namespace) -> int:
    from alembic import command
    from alembic.config import Config

    config = Config(str(Path(__file__).resolve().parents[2] / "alembic.ini"))
    command.upgrade(config, args.revision)
    return 0


def cmd_process_file(args: argparse.Namespace) -> int:
    from rafkhata.pipeline.offline import process_file

    return process_file(
        audio=Path(args.audio),
        out_dir=Path(args.out),
        asr=args.asr,
        llm=args.llm,
        lang=args.lang,
        glossary_file=Path(args.glossary) if args.glossary else None,
        course_title=args.course,
        study=not args.no_study,
    )


def cmd_score(args: argparse.Namespace) -> int:
    from rafkhata.pipeline.metrics import score_files

    return score_files(Path(args.hyp), Path(args.ref), Path(args.glossary) if args.glossary else None)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="rk", description="Raf-Khata backend tools")
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("api", help="run the HTTP API")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8000)
    p.add_argument("--reload", action="store_true")
    p.set_defaults(func=cmd_api)

    p = sub.add_parser("worker", help="run the processing worker")
    p.add_argument("--once", action="store_true", help="process queued jobs, then exit")
    p.set_defaults(func=cmd_worker)

    p = sub.add_parser("migrate", help="apply database migrations")
    p.add_argument("revision", nargs="?", default="head")
    p.set_defaults(func=cmd_migrate)

    p = sub.add_parser("process-file", help="run the full pipeline on a local audio file (no database)")
    p.add_argument("audio")
    p.add_argument("--out", default="out")
    p.add_argument("--asr", default=None, help="sarvam | soniox | mock (default: ASR_PRIMARY)")
    p.add_argument("--llm", default=None, help="claude | fake (default: LLM_PROVIDER)")
    p.add_argument("--lang", default="bn", choices=["bn", "en", "mixed"], help="notes language")
    p.add_argument("--glossary", help="file with one course term per line")
    p.add_argument("--course", default="", help="course title, used as context")
    p.add_argument("--no-study", action="store_true", help="skip the study pack")
    p.set_defaults(func=cmd_process_file)

    p = sub.add_parser("score", help="CER / WER / term recall of a transcript against a reference")
    p.add_argument("hyp", help="transcript to score (.txt, or transcript.json from process-file)")
    p.add_argument("ref", help="reference transcript (.txt)")
    p.add_argument("--glossary", help="file with one term per line")
    p.set_defaults(func=cmd_score)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    _setup_logging(args.verbose)
    return int(args.func(args) or 0)


if __name__ == "__main__":
    sys.exit(main())
