"""Audio preparation: join segments, resample, loudness-normalize, detect speech, trim silences.

Uses the system `ffmpeg`/`ffprobe` binaries and numpy. Lectures have long pauses while the teacher
writes on the board; trimming them cuts the audio billed by speech-recognition providers. A time
map converts transcript timestamps back to the original recording.
"""

from __future__ import annotations

import bisect
import json
import subprocess
import wave
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

SAMPLE_RATE = 16000
_FFMPEG = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y"]


class AudioError(RuntimeError):
    pass


def _run(cmd: list[str], timeout: float = 3600) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(cmd, capture_output=True, timeout=timeout, check=False)
    except FileNotFoundError as exc:
        raise AudioError(f"{cmd[0]} is not installed") from exc
    if result.returncode != 0:
        raise AudioError(f"{cmd[0]} failed: {result.stderr.decode(errors='replace')[-2000:]}")
    return result


def probe_duration(path: Path) -> float:
    out = _run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", str(path)])
    try:
        return float(json.loads(out.stdout)["format"]["duration"])
    except (KeyError, ValueError, TypeError) as exc:
        raise AudioError(f"could not read duration of {path.name}") from exc


def concat_to_wav(inputs: list[Path], out_wav: Path, *, loudnorm: bool = True) -> None:
    """Join audio files in order and convert to 16 kHz mono 16-bit WAV."""
    if not inputs:
        raise AudioError("no audio to process")
    filters = ["loudnorm=I=-20:TP=-2:LRA=11"] if loudnorm else []
    if len(inputs) == 1:
        source = ["-i", str(inputs[0])]
    else:
        list_file = out_wav.with_suffix(".concat.txt")
        lines = []
        for path in inputs:
            escaped = str(path.resolve()).replace("'", "'\\''")
            lines.append(f"file '{escaped}'")
        list_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
        source = ["-f", "concat", "-safe", "0", "-i", str(list_file)]
    cmd = [*_FFMPEG, *source, "-vn", "-ac", "1", "-ar", str(SAMPLE_RATE)]
    if filters:
        cmd += ["-af", ",".join(filters)]
    cmd += ["-c:a", "pcm_s16le", str(out_wav)]
    _run(cmd)


def encode_playback(wav: Path, out_m4a: Path, bitrate: str = "48k") -> None:
    """Small AAC file for in-app playback, streamable from the first byte."""
    _run(
        [*_FFMPEG, "-i", str(wav), "-c:a", "aac", "-b:a", bitrate, "-ac", "1", "-movflags", "+faststart", str(out_m4a)]
    )


def read_wav(path: Path) -> np.ndarray:
    """Read 16-bit mono PCM as float32 in [-1, 1]."""
    with wave.open(str(path), "rb") as wf:
        if wf.getsampwidth() != 2 or wf.getnchannels() != 1:
            raise AudioError("expected 16-bit mono WAV")
        frames = wf.readframes(wf.getnframes())
    return np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32768.0


def write_wav(path: Path, samples: np.ndarray, sample_rate: int = SAMPLE_RATE) -> None:
    pcm = (np.clip(samples, -1.0, 1.0) * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm.tobytes())


# ---------------------------------------------------------------------------
# Voice activity detection
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class SpeechRegion:
    start: float
    end: float

    @property
    def duration(self) -> float:
        return self.end - self.start


def frame_levels_db(samples: np.ndarray, sample_rate: int = SAMPLE_RATE, frame_s: float = 0.03) -> np.ndarray:
    frame = max(1, int(sample_rate * frame_s))
    count = len(samples) // frame
    if count == 0:
        return np.array([], dtype=np.float32)
    frames = samples[: count * frame].reshape(count, frame)
    rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
    return (20 * np.log10(np.maximum(rms, 1e-7))).astype(np.float32)


def energy_vad(
    samples: np.ndarray,
    sample_rate: int = SAMPLE_RATE,
    *,
    frame_s: float = 0.03,
    min_speech_s: float = 0.25,
    min_silence_s: float = 0.5,
    pad_s: float = 0.2,
) -> list[SpeechRegion]:
    """Adaptive energy VAD: speech is anything well above the recording's own noise floor.

    The threshold follows the noise floor (10th percentile of frame levels), so a steady ceiling-fan
    hum raises it instead of being taken for speech.
    """
    levels = frame_levels_db(samples, sample_rate, frame_s)
    if levels.size == 0:
        return []
    floor = float(np.percentile(levels, 10))
    threshold = max(floor + 10.0, -55.0)
    active = levels > threshold

    regions: list[list[float]] = []
    start = None
    for i, on in enumerate(active):
        if on and start is None:
            start = i
        elif not on and start is not None:
            regions.append([start * frame_s, i * frame_s])
            start = None
    if start is not None:
        regions.append([start * frame_s, len(active) * frame_s])

    merged: list[list[float]] = []
    for region in regions:
        if merged and region[0] - merged[-1][1] < min_silence_s:
            merged[-1][1] = region[1]
        else:
            merged.append(region)

    total = len(samples) / sample_rate
    out = []
    for s, e in merged:
        if e - s < min_speech_s:
            continue
        out.append(SpeechRegion(max(0.0, s - pad_s), min(total, e + pad_s)))
    # Padding can make neighbours overlap; merge again.
    final: list[SpeechRegion] = []
    for region in out:
        if final and region.start <= final[-1].end:
            final[-1] = SpeechRegion(final[-1].start, max(final[-1].end, region.end))
        else:
            final.append(region)
    return final


def quality_report(samples: np.ndarray, regions: list[SpeechRegion], sample_rate: int = SAMPLE_RATE) -> dict:
    """Rough recording quality: speech level vs noise floor, clipping, how much is speech."""
    levels = frame_levels_db(samples, sample_rate)
    duration = len(samples) / sample_rate
    if levels.size == 0 or duration == 0:
        return {"score": 0.0, "snr_db": 0.0, "speech_ratio": 0.0, "clipping_ratio": 0.0, "duration_s": duration}
    noise = float(np.percentile(levels, 10))
    speech_level = float(np.percentile(levels, 90))
    snr = speech_level - noise
    clipping = float(np.mean(np.abs(samples) > 0.99))
    speech_ratio = sum(r.duration for r in regions) / duration
    # 6 dB SNR -> 0, 30 dB -> 1; clipping above 1% costs up to half the score.
    snr_score = float(np.clip((snr - 6.0) / 24.0, 0.0, 1.0))
    clip_penalty = float(np.clip(clipping / 0.02, 0.0, 0.5))
    score = round(max(0.0, snr_score - clip_penalty) * (1.0 if speech_ratio > 0.05 else 0.3), 3)
    return {
        "score": score,
        "snr_db": round(snr, 1),
        "noise_floor_db": round(noise, 1),
        "speech_ratio": round(speech_ratio, 3),
        "clipping_ratio": round(clipping, 5),
        "duration_s": round(duration, 2),
    }


# ---------------------------------------------------------------------------
# Silence trimming with a time map back to the original recording
# ---------------------------------------------------------------------------


@dataclass
class TimeMap:
    """Maps times in the trimmed audio back to the original. Spans are (trimmed_start, original_start, length)."""

    spans: list[tuple[float, float, float]] = field(default_factory=list)

    @classmethod
    def identity(cls, duration: float) -> TimeMap:
        return cls([(0.0, 0.0, duration)])

    def to_original(self, t: float) -> float:
        if not self.spans:
            return t
        starts = [s[0] for s in self.spans]
        i = max(0, bisect.bisect_right(starts, t) - 1)
        trimmed_start, original_start, length = self.spans[i]
        return original_start + min(max(t - trimmed_start, 0.0), length)

    @property
    def trimmed_duration(self) -> float:
        if not self.spans:
            return 0.0
        last = self.spans[-1]
        return last[0] + last[2]


def trim_silences(
    samples: np.ndarray,
    regions: list[SpeechRegion],
    sample_rate: int = SAMPLE_RATE,
    *,
    min_gap_s: float = 2.0,
    keep_gap_s: float = 0.5,
) -> tuple[np.ndarray, TimeMap]:
    """Shorten every silence longer than `min_gap_s` to `keep_gap_s`."""
    duration = len(samples) / sample_rate
    if not regions:
        return samples, TimeMap.identity(duration)

    blocks: list[list[float]] = []
    for region in regions:
        if blocks and region.start - blocks[-1][1] < min_gap_s:
            blocks[-1][1] = region.end
        else:
            blocks.append([region.start, region.end])

    pieces: list[np.ndarray] = []
    spans: list[tuple[float, float, float]] = []
    gap = np.zeros(int(keep_gap_s * sample_rate), dtype=samples.dtype)
    cursor = 0.0
    for i, (start, end) in enumerate(blocks):
        if i > 0:
            pieces.append(gap)
            cursor += len(gap) / sample_rate
        a, b = int(start * sample_rate), int(end * sample_rate)
        chunk = samples[a:b]
        spans.append((cursor, a / sample_rate, len(chunk) / sample_rate))
        pieces.append(chunk)
        cursor += len(chunk) / sample_rate
    return np.concatenate(pieces), TimeMap(spans)
