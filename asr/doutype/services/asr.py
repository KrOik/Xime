"""Speech recognition service — file and stream transcription."""
from __future__ import annotations

from pathlib import Path
from typing import Any, Callable, Iterator

from ..auth import Auth
from ..client import DoubaoIMEClient
from ..events import TranscriptEvent
from ..transcribe import TranscriptResult, transcribe_file, transcribe_pcm


class AsrService:
    """High-level speech-to-text service.

    Example::

        from doutype import AsrService
        svc = AsrService()
        result = svc.transcribe_file("meeting.wav")
        print(result.text)
        print(result.format("srt"))
    """

    def __init__(
        self,
        *,
        device_id: str | None = None,
        client: DoubaoIMEClient | None = None,
        enable_twopass: bool = True,
        enable_threepass: bool = True,
        enable_punctuation: bool = True,
        language: str = "zh-CN",
        chunk_ms: int = 30_000,
    ):
        self.client = client or DoubaoIMEClient(device_id=device_id)
        self.enable_twopass = enable_twopass
        self.enable_threepass = enable_threepass
        self.enable_punctuation = enable_punctuation
        self.language = language
        self.chunk_ms = chunk_ms

    @property
    def device_id(self) -> str:
        return self.client.device_id

    def _opts(self, **extra: Any) -> dict[str, Any]:
        return {
            "auth": self.client.auth,
            "enable_twopass": self.enable_twopass,
            "enable_threepass": self.enable_threepass,
            "enable_punctuation": self.enable_punctuation,
            "language": self.language,
            "chunk_ms": self.chunk_ms,
            **extra,
        }

    def transcribe_file(
        self,
        path: str | Path,
        *,
        on_event: Callable[[TranscriptEvent], None] | None = None,
        **kwargs: Any,
    ) -> TranscriptResult:
        """Transcribe an audio or video file (decoded via ffmpeg if needed)."""
        return transcribe_file(path, on_event=on_event, **self._opts(**kwargs))

    def transcribe_pcm(
        self,
        pcm: bytes,
        *,
        realtime: bool = False,
        on_event: Callable[[TranscriptEvent], None] | None = None,
        **kwargs: Any,
    ) -> TranscriptResult:
        """Transcribe raw 16 kHz mono s16le PCM bytes."""
        return transcribe_pcm(
            pcm, realtime=realtime, on_event=on_event, **self._opts(**kwargs)
        )

    def stream_file(
        self, path: str | Path, **kwargs: Any
    ) -> Iterator[TranscriptEvent]:
        """Yield transcript events while processing a file."""
        events: list[TranscriptEvent] = []

        def _on(ev: TranscriptEvent) -> None:
            events.append(ev)

        self.transcribe_file(path, on_event=_on, **kwargs)
        yield from events
