from __future__ import annotations

import json
from pathlib import Path

import pytest
from audio_fixtures import make_adts

from rafkhata import cli
from rafkhata.config import get_settings


@pytest.fixture(autouse=True)
def offline_env(monkeypatch, tmp_path: Path):
    monkeypatch.chdir(tmp_path)  # keep any .env out of the way
    monkeypatch.setenv("ASR_PRIMARY", "mock")
    monkeypatch.setenv("LLM_PROVIDER", "fake")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def test_process_file_writes_everything(tmp_path: Path, capsys) -> None:
    audio = make_adts(tmp_path / "lecture.aac", 60)
    glossary = tmp_path / "glossary.txt"
    glossary.write_text("deadlock\ncircular wait\n", encoding="utf-8")
    out = tmp_path / "out"

    code = cli.main(["process-file", str(audio), "--out", str(out), "--glossary", str(glossary), "--course", "OS"])
    assert code == 0
    for name in ("transcript.raw.json", "transcript.json", "transcript.txt", "patches.json", "notes.json",
                 "notes.md", "notes.html", "study.json", "cost.json", "asr.mock.json"):  # fmt: skip
        assert (out / name).exists(), name
    assert "notes:" in capsys.readouterr().out
    assert json.loads((out / "cost.json").read_text())["total_usd"] == 0
    assert "katex" in (out / "notes.html").read_text()

    # Score the corrected transcript against the raw one: some fixes, so not identical.
    assert cli.main(["score", str(out / "transcript.json"), str(out / "transcript.raw.txt"),
                     "--glossary", str(glossary)]) == 0  # fmt: skip
    report = json.loads(capsys.readouterr().out)
    assert 0 < report["wer"] < 0.5
