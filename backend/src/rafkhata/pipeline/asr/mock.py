"""Offline stand-in for speech recognition, used in development and tests.

It finds speech regions in the audio and fills them with lines of text: from `<audio>.mock.txt` if
that file exists, otherwise from a built-in Banglish lecture script. That exercises every later step
(correction, exam alerts, announcements) without calling a real provider.
"""

from __future__ import annotations

from pathlib import Path

from rafkhata.pipeline.asr.base import ASRRequest, ASRResult, spans_to_segments
from rafkhata.pipeline.audio import SAMPLE_RATE, energy_vad, read_wav
from rafkhata.pipeline.schemas import Transcript

DEFAULT_SCRIPT = [
    "T: আসসালামু আলাইকুম। আজকে আমরা deadlock নিয়ে কথা বলব।",
    "T: Deadlock হলো এমন একটা অবস্থা যেখানে দুই বা তার বেশি process একে অপরের resource এর জন্য অপেক্ষা করে।",
    "T: Deadlock হওয়ার জন্য চারটা condition একসাথে লাগে।",
    "T: প্রথমটা mutual exclusion, মানে একটা resource একবারে একজনই ব্যবহার করতে পারে।",
    "T: দ্বিতীয়টা hold and wait, তৃতীয়টা no preemption, আর চতুর্থটা circular wait।",
    "T: এই চারটা condition মনে রাখবে, এটা পরীক্ষায় আসবে।",
    "S: স্যার, circular wait টা আরেকবার বোঝাবেন?",
    "T: ধরো P1 অপেক্ষা করছে P2 এর জন্য, আর P2 অপেক্ষা করছে P1 এর জন্য, এটাই circular wait।",
    "T: Deadlock avoidance এর জন্য আমরা ব্যাংকার্স অ্যালগরিদম ব্যবহার করি।",
    "T: Safe state মানে এমন একটা sequence আছে যেখানে সব process শেষ করতে পারবে।",
    "T: Need এর formula হলো Need = Max − Allocation।",
    "T: আগামী রবিবার CT হবে, deadlock আর scheduling থেকে প্রশ্ন আসবে।",
    "T: Assignment জমা দিতে হবে পরের সপ্তাহের মধ্যে।",
]


def _load_script(audio_path: Path) -> list[str]:
    sidecar = audio_path.with_suffix(".mock.txt")
    if sidecar.exists():
        lines = [line.strip() for line in sidecar.read_text(encoding="utf-8").splitlines() if line.strip()]
        if lines:
            return lines
    return DEFAULT_SCRIPT


class MockASR:
    name = "mock"

    def __init__(self, script_source: Path | None = None) -> None:
        self.script_source = script_source

    def transcribe(self, request: ASRRequest) -> ASRResult:
        samples = read_wav(request.audio_path)
        duration = len(samples) / SAMPLE_RATE
        regions = energy_vad(samples) or []
        script = _load_script(self.script_source or request.audio_path)

        # One line per speech region; if there are fewer regions than lines, split the audio evenly.
        if len(regions) >= len(script):
            spans_t = [(r.start, r.end) for r in regions[: len(script)]]
        else:
            step = duration / max(len(script), 1)
            spans_t = [(i * step, (i + 1) * step) for i in range(len(script))]

        spans = []
        for (start, end), line in zip(spans_t, script, strict=False):
            speaker = "SPEAKER_1" if line.startswith("S:") else "SPEAKER_0"
            text = line.split(":", 1)[1].strip() if line[:2] in ("T:", "S:") else line
            spans.append((round(start, 2), round(end, 2), text, speaker))

        transcript = Transcript(
            segments=spans_to_segments(spans), provider=self.name, model="mock", language="bn", duration_s=duration
        )
        return ASRResult(transcript=transcript, raw={"lines": script}, billed_seconds=duration)
