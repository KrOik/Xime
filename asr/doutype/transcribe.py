"""High-level file and PCM transcription pipeline."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Literal

from .asr_ws import AsrResult, AsrSession
from .audio import (
    BYTES_PER_FRAME,
    SAMPLE_RATE,
    decode_to_pcm16k,
    pcm_duration_s,
    split_pcm_chunks,
)
from .auth import Auth
from .events import (
    DeltaAssembler,
    TranscriptEvent,
    format_srt,
    format_vtt,
    single_segment_from_text,
)

OutputFormat = Literal["text", "json", "verbose_json", "srt", "vtt", "ndjson"]


@dataclass
class TranscriptResult:
    text: str
    duration: float
    segments: list[tuple[float, float, str]] = field(default_factory=list)
    events: list[TranscriptEvent] = field(default_factory=list)
    chunks: list[str] = field(default_factory=list)
    raw_results: list[AsrResult] = field(default_factory=list)

    def format(self, fmt: OutputFormat = "text") -> str:
        if fmt == "text":
            return self.text
        if fmt == "json":
            return json.dumps({"text": self.text}, ensure_ascii=False)
        if fmt == "verbose_json":
            return json.dumps(
                {
                    "text": self.text,
                    "duration": self.duration,
                    "segments": [
                        {"start": a, "end": b, "text": t} for a, b, t in self.segments
                    ],
                    "chunks": self.chunks,
                },
                ensure_ascii=False,
                indent=2,
            )
        if fmt == "srt":
            return format_srt(
                self.segments or single_segment_from_text(self.text, self.duration)
            )
        if fmt == "vtt":
            return format_vtt(
                self.segments or single_segment_from_text(self.text, self.duration)
            )
        if fmt == "ndjson":
            return "\n".join(
                json.dumps(e.to_dict(), ensure_ascii=False) for e in self.events
            )
        raise ValueError(f"unknown format: {fmt}")


def _session_kwargs(**kw: Any) -> dict[str, Any]:
    allowed = {
        "enable_twopass",
        "enable_threepass",
        "use_twopass_retry",
        "strong_ddc",
        "enable_punctuation",
        "result_type",
        "language",
        "realtime",
        "asr_token",
        "context",
        "extra",
        "frame_ms",
        "show_utterances",
        "enable_print_chinese",
    }
    return {k: v for k, v in kw.items() if k in allowed and v is not None}


def transcribe_pcm(
    pcm: bytes,
    *,
    auth: Auth | None = None,
    realtime: bool = False,
    on_event: Callable[[TranscriptEvent], None] | None = None,
    chunk_ms: int | None = None,
    **session_kw: Any,
) -> TranscriptResult:
    """Transcribe PCM. Auto-chunk when longer than ~35s (default chunk 30s)."""
    if chunk_ms is None:
        chunk_ms = 30_000 if len(pcm) > SAMPLE_RATE * 2 * 35 else 0

    if chunk_ms and len(pcm) > SAMPLE_RATE * 2 * (chunk_ms / 1000 + 5):
        return _transcribe_chunked(
            pcm,
            auth=auth,
            chunk_ms=int(chunk_ms),
            on_event=on_event,
            realtime=realtime,
            **session_kw,
        )

    events: list[TranscriptEvent] = []

    def _on(ev: TranscriptEvent) -> None:
        events.append(ev)
        if on_event:
            on_event(ev)

    sess = AsrSession(
        auth=auth,
        realtime=realtime,
        on_event=_on,
        **_session_kwargs(**session_kw),
    )
    try:
        sess.connect()
        sess.send_pcm_stream(pcm, pace=realtime)
        raw = sess.finish(wait_s=14.0)
        text = sess.final_text()
        dur = max(pcm_duration_s(pcm), sess.audio_duration_s())
        segs = single_segment_from_text(text, dur)
        return TranscriptResult(
            text=text,
            duration=dur,
            segments=segs,
            events=list(events),
            chunks=[text] if text else [],
            raw_results=list(raw),
        )
    finally:
        sess.close()


def _transcribe_chunked(
    pcm: bytes,
    *,
    auth: Auth | None,
    chunk_ms: int,
    on_event: Callable[[TranscriptEvent], None] | None,
    realtime: bool,
    **session_kw: Any,
) -> TranscriptResult:
    parts = split_pcm_chunks(pcm, chunk_ms=chunk_ms, frame_size=BYTES_PER_FRAME)
    assembler = DeltaAssembler()
    all_events: list[TranscriptEvent] = []
    texts: list[str] = []
    raw_all: list[AsrResult] = []
    offset_s = 0.0
    segments: list[tuple[float, float, str]] = []

    started = TranscriptEvent(type="speech.started")
    all_events.append(started)
    if on_event:
        on_event(started)

    for part in parts:
        sess = AsrSession(
            auth=auth,
            realtime=False,  # file chunks always fast
            **_session_kwargs(**session_kw),
        )
        try:
            sess.connect()
            sess.send_pcm_stream(part, pace=False)
            raw = sess.finish(wait_s=14.0)
            raw_all.extend(raw)
            t = sess.final_text()
            if t:
                texts.append(t)
                dur = pcm_duration_s(part)
                segments.append((offset_s, offset_s + max(dur, 0.3), t))
                cumulative = "".join(texts)
                for ev in assembler.on_partial(cumulative, end=offset_s + dur):
                    all_events.append(ev)
                    if on_event:
                        on_event(ev)
            offset_s += pcm_duration_s(part)
        finally:
            sess.close()

    full = "".join(texts)
    done = assembler.done(full, duration=offset_s)
    all_events.append(done)
    if on_event:
        on_event(done)
    return TranscriptResult(
        text=full,
        duration=offset_s,
        segments=segments,
        events=all_events,
        chunks=texts,
        raw_results=raw_all,
    )


def transcribe_file(
    path: str | Path,
    *,
    auth: Auth | None = None,
    on_event: Callable[[TranscriptEvent], None] | None = None,
    chunk_ms: int = 30_000,
    **session_kw: Any,
) -> TranscriptResult:
    pcm = decode_to_pcm16k(path)
    return transcribe_pcm(
        pcm,
        auth=auth,
        realtime=False,
        on_event=on_event,
        chunk_ms=chunk_ms,
        **session_kw,
    )
