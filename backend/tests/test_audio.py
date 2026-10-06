from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest
from audio_fixtures import bursts, make_adts

from rafkhata.pipeline.audio import (
    SAMPLE_RATE,
    TimeMap,
    concat_to_wav,
    encode_playback,
    energy_vad,
    probe_duration,
    quality_report,
    read_wav,
    trim_silences,
    write_wav,
)
from rafkhata.pipeline.steps import prepare_audio


def test_energy_vad_finds_bursts_despite_noise() -> None:
    samples = bursts(30.0)
    regions = energy_vad(samples)
    assert len(regions) == 5
    for i, region in enumerate(regions):
        assert region.start == pytest.approx(6.0 * i, abs=0.3)
        assert region.duration == pytest.approx(2.5, abs=0.5)


def test_vad_on_silence_and_empty() -> None:
    assert energy_vad(np.zeros(0, dtype=np.float32)) == []
    assert quality_report(np.zeros(0, dtype=np.float32), [])["score"] == 0.0


def test_trim_silences_and_time_map() -> None:
    samples = bursts(30.0)
    regions = energy_vad(samples)
    trimmed, tmap = trim_silences(samples, regions, min_gap_s=2.0, keep_gap_s=0.5)
    # Five ~2.9 s blocks with four 0.5 s gaps instead of 30 s.
    assert len(trimmed) / SAMPLE_RATE == pytest.approx(tmap.trimmed_duration, abs=0.01)
    assert tmap.trimmed_duration < 18
    # Each block's start in the trimmed audio maps back to the original block start.
    for (trimmed_start, _original_start, _), region in zip(tmap.spans, regions, strict=True):
        assert tmap.to_original(trimmed_start) == pytest.approx(region.start, abs=0.01)
        assert tmap.to_original(trimmed_start + 1.0) == pytest.approx(region.start + 1.0, abs=0.01)


def test_time_map_identity_and_clamping() -> None:
    tmap = TimeMap([(0.0, 10.0, 2.0), (2.5, 20.0, 3.0)])
    assert tmap.to_original(1.0) == 11.0
    assert tmap.to_original(2.2) == 12.0  # inside the inserted gap: clamp to the end of the block
    assert tmap.to_original(3.5) == 21.0
    assert TimeMap.identity(5.0).to_original(4.2) == 4.2


def test_quality_score_prefers_clean_audio() -> None:
    clean = bursts(30.0, noise=0.001)
    noisy = bursts(30.0, noise=0.08)
    q_clean = quality_report(clean, energy_vad(clean))
    q_noisy = quality_report(noisy, energy_vad(noisy))
    assert q_clean["score"] > q_noisy["score"]
    assert 0.0 <= q_noisy["score"] <= 1.0


def test_ffmpeg_concat_playback_and_prepare(tmp_path: Path) -> None:
    a = make_adts(tmp_path / "0000.aac", 12)
    b = make_adts(tmp_path / "0001.aac", 12)
    wav = tmp_path / "full.wav"
    concat_to_wav([a, b], wav)
    assert len(read_wav(wav)) / SAMPLE_RATE == pytest.approx(24.0, abs=0.3)

    m4a = tmp_path / "audio.m4a"
    encode_playback(wav, m4a)
    assert probe_duration(m4a) == pytest.approx(24.0, abs=0.3)

    prepared = prepare_audio([a, b], tmp_path / "prep", trim=True)
    assert prepared.duration_s == pytest.approx(24.0, abs=0.3)
    assert prepared.asr_duration_s < prepared.duration_s
    assert prepared.asr_wav.name == "asr.wav"
    assert prepared.quality["trimmed_s"] > 5


def test_wav_roundtrip(tmp_path: Path) -> None:
    samples = bursts(2.0)
    write_wav(tmp_path / "x.wav", samples)
    back = read_wav(tmp_path / "x.wav")
    assert np.max(np.abs(back - samples)) < 1e-3
