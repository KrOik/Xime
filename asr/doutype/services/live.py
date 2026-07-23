"""Live microphone / streaming recognition session service."""
from __future__ import annotations

import queue
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

from ..asr_ws import AsrSession, AsrResult
from ..audio import BYTES_PER_FRAME
from ..client import DoubaoIMEClient


@dataclass
class LiveState:
    session: AsrSession
    q: queue.Queue = field(default_factory=lambda: queue.Queue(maxsize=400))
    worker: threading.Thread | None = None
    partials: list[str] = field(default_factory=list)
    events: list[dict[str, Any]] = field(default_factory=list)
    cursor: int = 0
    pcm_bytes: int = 0
    closed: bool = False
    err: str | None = None
    lock: threading.Lock = field(default_factory=threading.Lock)


class LiveAsrService:
    """Manage multi-session live streaming recognition.

    Typical flow::

        live = LiveAsrService()
        sid = live.start()
        live.push_audio(sid, pcm_chunk)   # 16 kHz mono s16le
        print(live.poll(sid)["text"])
        print(live.stop(sid)["text"])
    """

    def __init__(
        self,
        *,
        device_id: str | None = None,
        client: DoubaoIMEClient | None = None,
        **session_defaults: Any,
    ):
        self.client = client or DoubaoIMEClient(device_id=device_id)
        self.session_defaults = session_defaults
        self._live: dict[str, LiveState] = {}
        self._lock = threading.Lock()

    def start(self, **options: Any) -> str:
        """Open a live session; returns session_id."""
        opts = {**self.session_defaults, **options}
        sess = AsrSession(
            auth=self.client.auth,
            enable_twopass=bool(opts.get("enable_twopass", True)),
            enable_threepass=bool(opts.get("enable_threepass", True)),
            enable_punctuation=bool(opts.get("enable_punctuation", True)),
            enable_timestamp=bool(opts.get("enable_timestamp", True)),
            result_type=str(opts.get("result_type") or "full"),
            language=str(opts.get("language") or "zh-CN"),
            enable_print_chinese=bool(opts.get("enable_print_chinese", False)),
            strong_ddc=bool(opts.get("strong_ddc", True)),
            realtime=False,
            frame_ms=20,
            input_mode=str(opts.get("input_mode") or "dictation"),
        )
        state = LiveState(session=sess)

        def on_res(r: AsrResult) -> None:
            with state.lock:
                if r.text and (not state.partials or state.partials[-1] != r.text):
                    state.partials.append(r.text)
                    state.events.append(
                        {
                            "type": "partial" if r.is_interim else "final",
                            "text": r.text,
                            "hint": r.pass_hint,
                        }
                    )

        sess.on_result = on_res
        sess.connect(timeout=15)
        state.worker = threading.Thread(
            target=self._worker, args=(state,), daemon=True
        )
        state.worker.start()
        sid = str(uuid.uuid4())
        with self._lock:
            self._live[sid] = state
        return sid

    def push_audio(self, session_id: str, pcm: bytes) -> dict[str, Any]:
        with self._lock:
            state = self._live.get(session_id)
        if not state or state.closed:
            raise KeyError("session not found")
        if state.err:
            raise RuntimeError(state.err)
        if not pcm:
            return {"ok": True, "pcm_bytes": state.pcm_bytes}
        if len(pcm) % 2:
            pcm = pcm[:-1]
        try:
            state.q.put(pcm, timeout=0.5)
        except queue.Full:
            raise RuntimeError("audio queue full")
        return {"ok": True, "pcm_bytes": state.pcm_bytes, "queued": state.q.qsize()}

    def poll(self, session_id: str) -> dict[str, Any]:
        with self._lock:
            state = self._live.get(session_id)
        if not state:
            raise KeyError("session not found")
        with state.lock:
            text = state.partials[-1] if state.partials else ""
            new_events = state.events[state.cursor :]
            state.cursor = len(state.events)
            return {
                "ok": True,
                "text": text,
                "events": new_events,
                "pcm_bytes": state.pcm_bytes,
                "error": state.err,
            }

    def stop(self, session_id: str) -> dict[str, Any]:
        with self._lock:
            state = self._live.pop(session_id, None)
        if not state:
            raise KeyError("session not found")
        try:
            state.q.put(None, timeout=1)
        except Exception:
            pass
        if state.worker and state.worker.is_alive():
            state.worker.join(timeout=6)
        try:
            if state.session.started:
                state.session.send_end_frame(trail_ms=500)
                state.session.pump(0.5)
        except Exception:
            pass
        state.closed = True
        state.session.finish(wait_s=16.0, wait_offline=True)
        with state.lock:
            stream_best = state.partials[-1] if state.partials else ""
            settled = state.session.final_text() or ""
            text = settled if len(settled) >= len(stream_best) else (stream_best or settled)
            return {
                "ok": True,
                "text": text,
                "stream_text": stream_best,
                "settled_text": settled,
                "n_results": len(state.session.results),
                "error": state.err,
            }

    @staticmethod
    def _worker(state: LiveState) -> None:
        frame = BYTES_PER_FRAME
        buf = bytearray()
        first = True
        sess = state.session
        sess.realtime = False
        frames_since_pump = 0
        try:
            while True:
                try:
                    item = state.q.get(timeout=0.1)
                except queue.Empty:
                    if state.closed:
                        break
                    try:
                        if sess.started:
                            sess.pump(0.05)
                    except Exception as e:
                        state.err = str(e)
                        return
                    continue
                if item is None:
                    break
                buf.extend(item)
                while True:
                    try:
                        more = state.q.get_nowait()
                    except queue.Empty:
                        break
                    if more is None:
                        try:
                            state.q.put_nowait(None)
                        except Exception:
                            state.closed = True
                        break
                    buf.extend(more)
                while len(buf) >= frame and not state.closed:
                    chunk = bytes(buf[:frame])
                    del buf[:frame]
                    try:
                        sess.send_pcm(chunk, last=False, first=first)
                        first = False
                        state.pcm_bytes += frame
                        frames_since_pump += 1
                        if frames_since_pump >= 5:
                            sess.pump(0.02)
                            frames_since_pump = 0
                    except Exception as e:
                        state.err = str(e)
                        return
            if buf and not state.closed:
                pad = bytes(buf)
                if len(pad) % frame:
                    pad = pad + b"\x00" * (frame - len(pad) % frame)
                for i in range(0, len(pad), frame):
                    if state.closed:
                        break
                    sess.send_pcm(pad[i : i + frame], last=False, first=first)
                    first = False
                    state.pcm_bytes += frame
                try:
                    sess.pump(0.05)
                except Exception:
                    pass
        except Exception as e:
            state.err = str(e)
