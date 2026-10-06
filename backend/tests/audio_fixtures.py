"""Generate small test recordings with ffmpeg: tone bursts ('speech') separated by pauses."""

from __future__ import annotations

import subprocess
from pathlib import Path

import numpy as np

from rafkhata.pipeline.audio import SAMPLE_RATE


def bursts(seconds: float, on_s: float = 2.5, period_s: float = 6.0, noise: float = 0.002) -> np.ndarray:
    """Float samples: a 220 Hz tone for `on_s` out of every `period_s` seconds, over light noise."""
    t = np.arange(int(seconds * SAMPLE_RATE)) / SAMPLE_RATE
    on = (np.mod(t, period_s) < on_s).astype(np.float32)
    rng = np.random.default_rng(0)
    return (0.4 * np.sin(2 * np.pi * 220 * t) * on + noise * rng.standard_normal(t.size)).astype(np.float32)


def make_adts(path: Path, seconds: float, *, on_s: float = 2.5, period_s: float = 6.0) -> Path:
    """An AAC (ADTS) file like the ones the app uploads."""
    expr = f"0.4*sin(2*PI*220*t)*lt(mod(t\\,{period_s})\\,{on_s})"
    subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", f"aevalsrc={expr}:s={SAMPLE_RATE}:d={seconds}",
            "-c:a", "aac", "-b:a", "32k", "-ac", "1", "-f", "adts", str(path),
        ],
        check=True,
    )  # fmt: skip
    return path
