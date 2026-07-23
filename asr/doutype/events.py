"""Transcript events and cumulative-to-delta assembly."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterator


@dataclass
class TranscriptEvent:
    type: str  # speech.started | transcript.partial | transcript.done | error
    text: str = ""
    end: float | None = None
    duration: float | None = None
    delta: str = ""
    raw: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {"type": self.type}
        if self.text:
            d["text"] = self.text
        if self.delta:
            d["delta"] = self.delta
        if self.end is not None:
            d["end"] = self.end
        if self.duration is not None:
            d["duration"] = self.duration
        return d


class DeltaAssembler:
    """Convert cumulative full-text partials into append-only deltas.

    Upstream returns the whole transcript so far; we emit a delta only when
    the new text extends the previous (prefix match). In-place revisions
    settle on done without inventing backwards deltas.
    """

    def __init__(self) -> None:
        self.prev = ""
        self.latest = ""
        self.got_any = False

    def on_partial(self, text: str, end: float | None = None) -> list[TranscriptEvent]:
        if not text or text == self.prev:
            return []
        events: list[TranscriptEvent] = []
        if text.startswith(self.prev):
            delta = text[len(self.prev) :]
            if delta:
                events.append(
                    TranscriptEvent(
                        type="transcript.partial",
                        text=text,
                        delta=delta,
                        end=end,
                    )
                )
        else:
            # in-place revision: expose full snapshot, empty delta
            events.append(
                TranscriptEvent(
                    type="transcript.partial",
                    text=text,
                    delta="",
                    end=end,
                )
            )
        self.prev = text
        self.latest = text
        self.got_any = True
        return events

    def done(self, text: str | None = None, duration: float | None = None) -> TranscriptEvent:
        final = text if text is not None else self.latest
        self.latest = final
        return TranscriptEvent(
            type="transcript.done",
            text=final,
            duration=duration,
            delta="",
        )


def format_srt(segments: list[tuple[float, float, str]]) -> str:
    """segments: list of (start_s, end_s, text)."""
    lines: list[str] = []
    for i, (start, end, text) in enumerate(segments, 1):
        if not text.strip():
            continue
        lines.append(str(i))
        lines.append(f"{_ts(start)} --> {_ts(end)}")
        lines.append(text.strip())
        lines.append("")
    return "\n".join(lines)


def format_vtt(segments: list[tuple[float, float, str]]) -> str:
    lines = ["WEBVTT", ""]
    for start, end, text in segments:
        if not text.strip():
            continue
        lines.append(f"{_ts(start, vtt=True)} --> {_ts(end, vtt=True)}")
        lines.append(text.strip())
        lines.append("")
    return "\n".join(lines)


def _ts(seconds: float, *, vtt: bool = False) -> str:
    if seconds < 0:
        seconds = 0
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = seconds % 60
    if vtt:
        return f"{h:02d}:{m:02d}:{s:06.3f}"
    # SRT uses comma
    whole = int(s)
    ms = int(round((s - whole) * 1000))
    return f"{h:02d}:{m:02d}:{whole:02d},{ms:03d}"


def single_segment_from_text(text: str, duration: float) -> list[tuple[float, float, str]]:
    if not text:
        return []
    return [(0.0, max(duration, 0.5), text)]
