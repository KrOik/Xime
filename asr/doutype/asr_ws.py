"""Realtime speech recognition over WebSocket."""
from __future__ import annotations

import json
import ssl
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

import websocket

from .audio import (
    BYTES_PER_FRAME,
    FRAME_MS,
    SAMPLE_RATE,
    frame_bytes,
    iter_frames,
    pad_frame,
)
from .auth import APP_ID, APP_VERSION, Auth, TokenBundle
from .events import DeltaAssembler, TranscriptEvent
from .proto import bytes_field, int_field, parse_pb, str_field

WS_URL = "wss://frontier-audio-ime-ws.doubao.com/ocean/api/v1/ws"
SERVICE_ASR = "ASR"

FRAME_FIRST = 1
FRAME_MIDDLE = 3
FRAME_LAST = 9

# Re-export timing constants
UPSTREAM_FRAME_MS = FRAME_MS
UPSTREAM_BYTES_PER_FRAME = BYTES_PER_FRAME
UPSTREAM_SAMPLE_RATE = SAMPLE_RATE

# Response fields (same on both layouts)
R_EVENT = 4
R_STATUS_CODE = 5
R_STATUS_TEXT = 6
R_PAYLOAD = 7


def marshal_start_task_compact(
    *,
    token: str,
    task_id: str,
    payload: str = "",
) -> bytes:
    """Encode StartTask message."""
    parts = [
        str_field(2, token),
        str_field(3, SERVICE_ASR),
        str_field(5, "StartTask"),
    ]
    if payload:
        parts.append(str_field(6, payload))
    parts.append(str_field(8, task_id))
    return b"".join(parts)


def marshal_start_session_compact(
    *,
    token: str,
    task_id: str,
    payload: str = "",
) -> bytes:
    parts = [
        str_field(2, token),
        str_field(3, SERVICE_ASR),
        str_field(5, "StartSession"),
    ]
    if payload:
        parts.append(str_field(6, payload))
    parts.append(str_field(8, task_id))
    return b"".join(parts)


def marshal_finish_session_compact(*, token: str, task_id: str) -> bytes:
    return b"".join(
        [
            str_field(2, token),
            str_field(3, SERVICE_ASR),
            str_field(5, "FinishSession"),
            str_field(8, task_id),
        ]
    )


def marshal_handshake(
    *,
    token: str,
    appkey: str,
    event: str,
    task_id: str,
    session_id: str = "",
    namespace: str = SERVICE_ASR,
    version: str = "v2",
    payload: str = "",
) -> bytes:
    """Encode session control message."""
    parts = [
        str_field(1, token),
        str_field(2, appkey),
    ]
    if namespace:
        parts.append(str_field(3, namespace))
    if version:
        parts.append(str_field(4, version))
    parts.append(str_field(5, event))
    if payload:
        parts.append(str_field(6, payload))
    parts.append(str_field(7, task_id))
    if session_id:
        parts.append(str_field(8, session_id))
    return b"".join(parts)


def marshal_audio_request(
    *,
    payload: str,
    audio: bytes,
    request_id: str,
    frame_state: int,
    service: str = SERVICE_ASR,
    method: str = "TaskRequest",
) -> bytes:
    """Encode audio frame request (binary PCM on field 7)."""
    parts: list[bytes] = [
        str_field(3, service),
        str_field(5, method),
    ]
    if payload:
        parts.append(str_field(6, payload))
    if audio:
        parts.append(bytes_field(7, audio))
    if request_id:
        parts.append(str_field(8, request_id))
    if frame_state:
        parts.append(int_field(9, frame_state))
    return b"".join(parts)


@dataclass
class AsrResult:
    text: str = ""
    is_interim: bool = True
    is_final: bool = False
    is_vad_finished: bool = False
    stream_asr_finish: bool = False
    is_offline_result: bool = False
    pass_hint: str = ""
    utterances: list[Any] = field(default_factory=list)
    raw: dict[str, Any] = field(default_factory=dict)
    event: str = ""
    status_code: int | None = None


class AsrSession:
    def __init__(
        self,
        auth: Auth | None = None,
        device_id: str | None = None,
        *,
        enable_twopass: bool = True,
        enable_threepass: bool = True,
        use_twopass_retry: bool = True,
        strong_ddc: bool = True,
        format: str = "raw",
        sample_rate: int = 16000,
        rate: int | None = None,
        bits: int = 16,
        channel: int = 1,
        language: str = "zh-CN",
        result_type: str = "full",
        show_utterances: bool = False,
        enable_punctuation: bool = True,
        enable_speech_rejection: bool = False,
        enable_sentence_seg: bool = True,
        enable_text_seg: bool = False,
        enable_timestamp: bool = True,
        enable_print_chinese: bool = False,
        remove_space_between_han_num: bool = True,
        remove_space_between_han_eng: bool = True,
        disable_user_words: bool = False,
        app_name: str = "com.android.chrome",
        input_mode: str = "tool",
        end_smooth_window_ms: int | None = None,
        context: str | None = None,
        extra: dict[str, Any] | None = None,
        frame_ms: int = FRAME_MS,
        # When True, pace send rate to wall-clock audio time for live input.
        realtime: bool = False,
        asr_token: str | None = None,  # optional session token override
        on_result: Callable[[AsrResult], None] | None = None,
        on_event: Callable[[TranscriptEvent], None] | None = None,
    ):
        self.auth = auth or Auth(device_id=device_id)
        self.device_id = self.auth.device_id
        self.enable_twopass = enable_twopass
        self.enable_threepass = enable_threepass
        self.use_twopass_retry = use_twopass_retry
        self.strong_ddc = strong_ddc
        # raw/pcm both mean s16le PCM on wire; speech_opus needs Opus encoder
        if format in ("pcm", "raw", ""):
            self.format = "raw"
        else:
            self.format = format
        self.sample_rate = int(rate if rate is not None else sample_rate)
        self.rate = self.sample_rate
        self.bits = bits
        self.channel = channel
        self.language = language
        self.result_type = result_type
        self.show_utterances = show_utterances
        self.enable_punctuation = enable_punctuation
        self.enable_speech_rejection = enable_speech_rejection
        self.enable_sentence_seg = enable_sentence_seg
        self.enable_text_seg = enable_text_seg
        self.enable_timestamp = enable_timestamp
        self.enable_print_chinese = enable_print_chinese
        self.remove_space_between_han_num = remove_space_between_han_num
        self.remove_space_between_han_eng = remove_space_between_han_eng
        self.disable_user_words = disable_user_words
        self.app_name = app_name
        self.input_mode = input_mode
        self.end_smooth_window_ms = end_smooth_window_ms
        self.context = context
        self.extra_overrides = dict(extra or {})
        self.frame_ms = int(frame_ms) if frame_ms else FRAME_MS
        self.realtime = realtime
        self._token_override = asr_token
        self.on_result = on_result
        self.on_event = on_event

        self._ws: websocket.WebSocket | None = None
        self._ws_lock = threading.RLock()  # concurrent send+recv safety
        self._token: TokenBundle | None = None
        self.task_id = str(uuid.uuid4())
        self.request_id = self.task_id
        self.session_id = ""
        self._seq = 0  # number of audio frames already sent
        self._t0_ms = 0  # logical audio timeline origin (ms)
        self._pace_origin: float | None = None  # wall-clock start for realtime pacing
        self.results: list[AsrResult] = []
        self.events: list[str] = []  # wire protocol events
        self.transcript_events: list[TranscriptEvent] = []
        self._assembler = DeltaAssembler()
        self.last_error: str | None = None
        self.started = False
        self.frames_in = 0
        self.frames_sent = 0
        self.last_payloads: list[dict[str, Any]] = []
        # Feature counters for validation
        self.feature_hits: dict[str, int] = {
            "interim": 0,
            "final": 0,
            "sentence": 0,
            "offline": 0,
            "vad_start": 0,
            "vad_end": 0,
            "punctuation": 0,
            "timestamp": 0,
            "utterances": 0,
            "alternatives": 0,
            "words": 0,
            "stream_asr_finish": 0,
            "nonstream": 0,
            "mixed_latin": 0,
            "digits": 0,
        }

    @property
    def frame_size(self) -> int:
        return frame_bytes(self.rate, self.bits, self.channel, self.frame_ms)

    def connect(self, timeout: float = 12.0) -> None:
        if self._token_override:
            try:
                speech_key = self.auth.speech_appkey()
            except Exception:
                speech_key = ""
            appkey = (
                self._token_override
                if len(self._token_override) < 40
                else (speech_key or self._token_override)
            )
            self._token = TokenBundle(
                token=self._token_override,
                appkey=appkey,
                claims={},
                fetched_at=time.time(),
            )
            # short product key uses compact control envelope
            self._use_compact_envelope = (
                len(self._token_override) < 80 and self._token_override.count(".") < 2
            )
        else:
            self._token = self.auth.asr_token()
            self._use_compact_envelope = False

        url = f"{WS_URL}?aid={APP_ID}&device_id={self.device_id}"
        headers = [
            f"Device-ID: {self.device_id}",
            f"App-ID: {APP_ID}",
            "Proto-Version: v2",
            "proto-version: v2",
            "Sec-WebSocket-Protocol: frontier-v2",
            f"User-Agent: DoubaoIME/{APP_VERSION}",
            f"x-tt-e-k: {self.device_id}+W",
            "x-tt-e-b: 1",
            "x-custom-keepalive: true",
        ]
        # Attach bearer when using long-form session tokens
        if not getattr(self, "_use_compact_envelope", False):
            headers.insert(0, f"Authorization: Bearer {self._token.token}")
        else:
            # include bearer for gateways that expect it
            headers.insert(0, f"Authorization: Bearer {self._token.token}")

        self._ws = websocket.create_connection(
            url,
            header=headers,
            sslopt={"cert_reqs": ssl.CERT_NONE, "check_hostname": False},
            timeout=timeout,
        )

        payload = self._session_payload()
        if getattr(self, "_use_compact_envelope", False):
            # Compact StartTask carries no session payload
            body = marshal_start_task_compact(
                token=self._token.token, task_id=self.task_id, payload=""
            )
        else:
            body = marshal_handshake(
                token=self._token.token,
                appkey=self._token.appkey,
                event="StartTask",
                task_id=self.task_id,
                payload=payload,
            )
        self._ws_send(body)
        if not self._pump_until({"TaskStarted"}, timeout=timeout):
            raise RuntimeError(f"StartTask failed: {self.last_error or self.events}")

        if getattr(self, "_use_compact_envelope", False):
            body = marshal_start_session_compact(
                token=self._token.token,
                task_id=self.task_id,
                payload=payload,
            )
        else:
            body = marshal_handshake(
                token=self._token.token,
                appkey=self._token.appkey,
                event="StartSession",
                task_id=self.task_id,
                session_id=self.session_id or self.task_id,
                payload=payload,
            )
        self._ws_send(body)
        if not self._pump_until({"SessionStarted"}, timeout=timeout):
            raise RuntimeError(f"StartSession failed: {self.last_error or self.events}")
        self.started = True
        # Logical timeline base (ms) + frameIndex * frame_ms
        self._t0_ms = int(time.time() * 1000)
        self._seq = 0
        self.frames_sent = 0
        self._pace_origin = time.monotonic() if self.realtime else None
        self._assembler = DeltaAssembler()

    def _session_payload(self) -> str:
        extra: dict[str, Any] = {
            "app_name": self.app_name,
            "cell_compress_rate": 8,
            "did": self.device_id,
            "input_mode": self.input_mode,
            "enable_asr_twopass": self.enable_twopass,
            "enable_asr_threepass": self.enable_threepass,
            "use_twopass_retry": self.use_twopass_retry,
            "strong_ddc": self.strong_ddc,
            "remove_space_between_han_num": self.remove_space_between_han_num,
            "remove_space_between_han_eng": self.remove_space_between_han_eng,
            "enable_print_chinese": self.enable_print_chinese,
            "disable_user_words": self.disable_user_words,
            "language": self.language,
            "result_type": self.result_type,
            "show_utterances": self.show_utterances,
            "enable_sentence_seg": self.enable_sentence_seg,
            "enable_text_seg": self.enable_text_seg,
            "enable_timestamp": self.enable_timestamp,
        }
        if self.end_smooth_window_ms is not None:
            extra["end_smooth_window_ms"] = int(self.end_smooth_window_ms)
        if self.context is not None:
            extra["context"] = self.context
        extra.update(self.extra_overrides)
        body: dict[str, Any] = {
            "audio_info": {
                "channel": self.channel,
                "format": self.format,
                "sample_rate": self.rate,
            },
            "enable_punctuation": self.enable_punctuation,
            "enable_speech_rejection": self.enable_speech_rejection,
            "language": self.language,
            "result_type": self.result_type,
            "show_utterances": self.show_utterances,
            "extra": extra,
        }
        return json.dumps(body, separators=(",", ":"))

    def _ws_send(self, body: bytes) -> None:
        with self._ws_lock:
            if not self._ws:
                raise RuntimeError("websocket closed")
            self._ws.send(body, opcode=websocket.ABNF.OPCODE_BINARY)

    def _ws_recv(self, timeout: float) -> bytes | None:
        with self._ws_lock:
            if not self._ws:
                return None
            self._ws.settimeout(timeout)
            try:
                fr = self._ws.recv()
            except Exception as e:
                self.last_error = str(e)
                return None
        if fr is None:
            return None
        if isinstance(fr, str):
            fr = fr.encode("utf-8", errors="replace")
        return fr

    def _recv_one(self, timeout: float = 5.0) -> bytes | None:
        fr = self._ws_recv(timeout)
        if fr is None:
            return None
        self.frames_in += 1
        self._handle(fr)
        return fr

    def _pump_until(self, want: set[str], timeout: float = 8.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if any(e in want for e in self.events):
                return True
            if any("Failed" in e for e in self.events[-3:]):
                return False
            remaining = max(0.2, deadline - time.time())
            fr = self._recv_one(timeout=min(1.5, remaining))
            if fr is None and self.last_error:
                if "timed out" in (self.last_error or "").lower():
                    self.last_error = None
                    continue
                return False
        return any(e in want for e in self.events)

    @staticmethod
    def _looks_uuid(s: str) -> bool:
        if not isinstance(s, str) or s.startswith("{"):
            return False
        if s in (
            "TaskStarted",
            "SessionStarted",
            "TaskFailed",
            "SessionFailed",
            "SessionFinished",
            "TaskFinished",
            "Pong",
            "ASR",
            "OK",
        ):
            return False
        return len(s) == 36 and s.count("-") == 4

    def _handle(self, data: bytes) -> None:
        fields = parse_pb(data)
        event = ""
        status_code = None
        status_text = ""
        payload_obj: Any = None

        for fn in (1, 2, 7, 8):
            if fn not in fields:
                continue
            kind, val = fields[fn]
            if kind == "string" and self._looks_uuid(str(val)):
                self.session_id = str(val)
                break

        if R_EVENT in fields and fields[R_EVENT][0] == "string":
            event = str(fields[R_EVENT][1])
            self.events.append(event)

        if R_STATUS_CODE in fields and fields[R_STATUS_CODE][0] == "varint":
            status_code = int(fields[R_STATUS_CODE][1])

        if R_STATUS_TEXT in fields and fields[R_STATUS_TEXT][0] == "string":
            status_text = str(fields[R_STATUS_TEXT][1])

        for fn in (R_PAYLOAD, 6, 8):
            if payload_obj is not None:
                break
            if fn not in fields:
                continue
            kind, val = fields[fn]
            if kind == "string" and isinstance(val, str) and val.startswith("{"):
                try:
                    payload_obj = json.loads(val)
                except json.JSONDecodeError:
                    continue

        if event in ("SessionFailed", "TaskFailed") or (
            status_code is not None and status_code >= 40000000 and event not in ("Pong",)
        ):
            self.last_error = status_text or event or str(status_code)

        if isinstance(payload_obj, dict):
            if len(self.last_payloads) < 64:
                self.last_payloads.append(payload_obj)
            self._emit(payload_obj, event=event, status_code=status_code)

    def _classify_pass(
        self, item: dict[str, Any], extra: dict[str, Any]
    ) -> tuple[bool, bool, str]:
        is_offline = bool(
            item.get("is_offline_result")
            or extra.get("is_offline_result")
            or extra.get("nonstream_result")
            or (item.get("extra") or {}).get("nonstream_result")
        )
        stream_done = bool(item.get("stream_asr_finish"))
        vad_done = bool(item.get("is_vad_finished") or extra.get("vad_end"))
        wire_interim = bool(item.get("is_interim", True))
        if extra.get("vad_start") and not (item.get("text") or "").strip():
            return True, False, "vad_start"
        if is_offline:
            return False, True, "offline"
        if stream_done or vad_done:
            return False, True, "sentence"
        if not wire_interim:
            return False, True, "final"
        return True, False, "interim"

    def _emit(self, payload: dict[str, Any], *, event: str, status_code: int | None) -> None:
        results = payload.get("results")
        extra = payload.get("extra") if isinstance(payload.get("extra"), dict) else {}
        if not isinstance(extra, dict):
            extra = {}

        if not isinstance(results, list):
            if extra.get("vad_start") or extra.get("vad_end"):
                hint = "vad_start" if extra.get("vad_start") else "vad_end"
                ar = AsrResult(
                    pass_hint=hint,
                    is_vad_finished=bool(extra.get("vad_end")),
                    raw={"payload": payload},
                    event=event,
                    status_code=status_code,
                )
                self.results.append(ar)
                self.feature_hits[hint] = self.feature_hits.get(hint, 0) + 1
                if self.on_result:
                    try:
                        self.on_result(ar)
                    except Exception:
                        pass
            return

        for item in results:
            if not isinstance(item, dict):
                continue
            text = item.get("text") or ""
            if not isinstance(text, str):
                text = str(text)
            item_extra = item.get("extra") if isinstance(item.get("extra"), dict) else {}
            merged_extra = {**extra, **item_extra}
            is_interim, is_final, pass_hint = self._classify_pass(item, merged_extra)
            utt = item.get("utterances")
            if not isinstance(utt, list):
                utt = []
            ar = AsrResult(
                text=text,
                is_interim=is_interim,
                is_final=is_final,
                is_vad_finished=bool(item.get("is_vad_finished")),
                stream_asr_finish=bool(item.get("stream_asr_finish")),
                is_offline_result=bool(
                    item.get("is_offline_result") or merged_extra.get("nonstream_result")
                ),
                pass_hint=pass_hint,
                utterances=list(utt),
                raw={"item": item, "extra": merged_extra},
                event=event,
                status_code=status_code,
            )
            self.results.append(ar)
            self._note_features(ar, item, merged_extra)
            self._emit_transcript_event(ar)
            if self.on_result:
                try:
                    self.on_result(ar)
                except Exception:
                    pass

    def _emit_transcript_event(self, ar: AsrResult) -> None:
        if ar.pass_hint == "vad_start":
            ev = TranscriptEvent(type="speech.started")
            self.transcript_events.append(ev)
            if self.on_event:
                try:
                    self.on_event(ev)
                except Exception:
                    pass
            return
        if not ar.text:
            return
        end = None
        raw_item = (ar.raw or {}).get("item") or {}
        if isinstance(raw_item, dict) and raw_item.get("end_time") is not None:
            try:
                end = float(raw_item["end_time"])
            except Exception:
                end = None
        for ev in self._assembler.on_partial(ar.text, end=end):
            self.transcript_events.append(ev)
            if self.on_event:
                try:
                    self.on_event(ev)
                except Exception:
                    pass

    def _note_features(
        self, ar: AsrResult, item: dict[str, Any], extra: dict[str, Any]
    ) -> None:
        hits = self.feature_hits
        if ar.pass_hint in hits:
            hits[ar.pass_hint] += 1
        if ar.is_final and ar.pass_hint not in ("sentence", "offline"):
            hits["final"] += 1
        if ar.stream_asr_finish:
            hits["stream_asr_finish"] += 1
        if ar.is_offline_result or extra.get("nonstream_result"):
            hits["nonstream"] += 1
            hits["offline"] += 1
        text = ar.text or ""
        if any(ch in text for ch in "，。？！、；：,.!?;:"):
            hits["punctuation"] += 1
        if item.get("start_time") is not None or item.get("end_time") is not None:
            hits["timestamp"] += 1
        if ar.utterances:
            hits["utterances"] += 1
        alts = item.get("alternatives")
        if isinstance(alts, list) and alts:
            hits["alternatives"] += 1
            for a in alts:
                if isinstance(a, dict) and a.get("words"):
                    hits["words"] += 1
                    break
        words = item.get("words")
        if isinstance(words, list) and words:
            hits["words"] += 1
        if any("a" <= c.lower() <= "z" for c in text):
            hits["mixed_latin"] += 1
        if any(c.isdigit() for c in text):
            hits["digits"] += 1

    def pump(self, wait_s: float = 0.5) -> None:
        end = time.time() + wait_s
        while time.time() < end:
            fr = self._recv_one(timeout=max(0.05, end - time.time()))
            if fr is None:
                if self.last_error and "timed out" in self.last_error.lower():
                    self.last_error = None
                    continue
                break

    def _pace_wait(self) -> None:
        """Wall-clock pacing so live capture keeps natural timing."""
        if not self.realtime or self._pace_origin is None:
            return
        # After sending frame index (self._seq - 1), next audio time is seq * frame_ms
        target = self._pace_origin + (self._seq * self.frame_ms) / 1000.0
        delay = target - time.monotonic()
        if delay > 0.0005:
            time.sleep(delay)

    def send_pcm(
        self,
        pcm: bytes,
        *,
        last: bool = False,
        first: bool | None = None,
        pad: bool = True,
    ) -> None:
        """Send one audio frame.

        - Audio must be raw s16le mono @ session sample_rate.
        - Frame size defaults to 20ms = 640 bytes @ 16kHz.
        - Undersized frames are zero-padded when pad=True.
        - timestamp_ms is logical audio time: frame_index * frame_ms (not wall clock).
        """
        if not self.started or not self._ws:
            raise RuntimeError("session not started")
        fs = self.frame_size
        if pad:
            if len(pcm) > fs:
                # split oversized externally; only send first frame here
                pcm = pcm[:fs]
            elif len(pcm) < fs:
                pcm = pad_frame(pcm, fs)
        elif len(pcm) == 0:
            return

        if first is None:
            first = self._seq == 0
        if last:
            state = FRAME_LAST
        elif first:
            state = FRAME_FIRST
        else:
            state = FRAME_MIDDLE

        # Logical timeline: frame 0 → 0ms, frame 1 → 20ms, ...
        ts = int(self._t0_ms + self._seq * self.frame_ms)
        self._seq += 1
        self.frames_sent = self._seq

        extra: dict[str, Any] = {}
        if last:
            extra = {"finish_audio": True, "force_asr_twopass": True}
        meta = json.dumps(
            {"extra": extra, "timestamp_ms": ts},
            separators=(",", ":"),
        )
        body = marshal_audio_request(
            payload=meta,
            audio=pcm,
            request_id=self.task_id,
            frame_state=state,
        )
        self._ws_send(body)
        self._pace_wait()

    def send_pcm_stream(
        self,
        pcm: bytes,
        *,
        frame_ms: int | None = None,
        mode: str = "raw_f7",
        pace: bool | None = None,
        pump_each: bool = True,
        finish_audio: bool = True,
    ) -> None:
        """Stream PCM as fixed 20ms frames.

        pace:
          None  → use self.realtime (True = wall-clock, False = as-fast-as-possible)
          True  → force realtime pacing
          False → send as fast as possible (files)
        """
        if frame_ms is not None and frame_ms != self.frame_ms:
            self.frame_ms = int(frame_ms)
        use_pace = self.realtime if pace is None else bool(pace)
        # temporarily override realtime for pacing helper
        prev = self.realtime
        self.realtime = use_pace
        if use_pace and self._pace_origin is None:
            self._pace_origin = time.monotonic()
        try:
            fs = self.frame_size
            first = self._seq == 0
            for chunk in iter_frames(pcm, frame_size=fs, pad_last=True):
                self.send_pcm(chunk, last=False, first=first)
                first = False
                if pump_each:
                    self.pump(0.005 if use_pace else 0.01)
            if finish_audio:
                # trailing silence + finish flags
                self.send_pcm(b"\x00" * fs, last=True)
                self.pump(0.3)
        finally:
            self.realtime = prev

    def send_end_frame(self, *, mode: str = "raw_f7", trail_ms: int = 400) -> None:
        """Send trailing silence then finish_audio last frame.

        trail_ms: silence before the last frame so upstream VAD can close the
        utterance cleanly (prevents cutting mid-word like 「我明」).
        """
        fs = self.frame_size
        n_trail = max(0, int(trail_ms) // max(self.frame_ms, 1))
        silence = b"\x00" * fs
        for _ in range(n_trail):
            self.send_pcm(silence, last=False)
            self.pump(0.01)
        self.send_pcm(silence, last=True)

    def finish(
        self,
        wait_s: float = 12.0,
        *,
        send_end_frame: bool = False,
        mode: str = "raw_f7",
        wait_offline: bool = True,
    ) -> list[AsrResult]:
        """Close session and wait for multipass/offline corrections.

        wait_offline: after SessionFinished (or last frame), keep pumping briefly
        so threepass/offline results can overwrite truncated stream text.
        """
        if self.started and self._ws and self._token:
            if send_end_frame:
                try:
                    self.send_end_frame()
                    self.pump(0.2)
                except Exception:
                    pass
            try:
                if getattr(self, "_use_compact_envelope", False):
                    body = marshal_finish_session_compact(
                        token=self._token.token,
                        task_id=self.task_id,
                    )
                else:
                    body = marshal_handshake(
                        token=self._token.token,
                        appkey=self._token.appkey,
                        event="FinishSession",
                        task_id=self.task_id,
                        session_id=self.session_id or self.task_id,
                    )
                self._ws_send(body)
            except Exception:
                pass
            end = time.time() + wait_s
            saw_finished = False
            best_len = len(self.final_text())
            while time.time() < end:
                if "SessionFinished" in self.events:
                    saw_finished = True
                    # grace for offline/nonstream multipass after finish
                    grace = 4.0 if wait_offline else 0.8
                    grace_end = time.time() + grace
                    stable_since = time.time()
                    while time.time() < grace_end:
                        fr = self._recv_one(timeout=0.25)
                        cur = len(self.final_text())
                        if cur > best_len:
                            best_len = cur
                            stable_since = time.time()
                        # if text stable for 0.8s and we have offline or enough length, stop early
                        if (
                            wait_offline
                            and time.time() - stable_since > 0.8
                            and (
                                any(r.is_offline_result for r in self.results)
                                or any(r.stream_asr_finish for r in self.results)
                            )
                        ):
                            break
                        if fr is None and self.last_error:
                            if "timed out" in self.last_error.lower():
                                self.last_error = None
                                continue
                            break
                    break
                if self._recv_one(timeout=0.4) is None:
                    if self.last_error and "timed out" not in self.last_error.lower():
                        break
                    self.last_error = None
            if wait_offline and not saw_finished:
                self.pump(min(2.0, max(0.0, end - time.time())))
        # settle transcript.done
        final = self.final_text()
        done = self._assembler.done(final)
        self.transcript_events.append(done)
        if self.on_event:
            try:
                self.on_event(done)
            except Exception:
                pass
        self.close()
        return list(self.results)

    def close(self) -> None:
        with self._ws_lock:
            if self._ws:
                try:
                    self._ws.close()
                except Exception:
                    pass
                self._ws = None
        self.started = False

    def final_text(self) -> str:
        """Best cumulative transcript — never prefer a shorter offline rewrite.

        Stream partials are cumulative full text. Offline/threepass may refine
        wording but sometimes arrive truncated; always take the longest non-empty
        candidate, breaking ties toward offline/sentence/final.
        """
        candidates: list[tuple[int, int, str]] = []
        rank = {
            "offline": 5,
            "sentence": 4,
            "final": 3,
            "twopass": 2,
            "interim": 1,
            "vad_start": 0,
            "vad_end": 0,
        }
        for r in self.results:
            if not r.text:
                continue
            candidates.append(
                (len(r.text), rank.get(r.pass_hint, 1), r.text)
            )
        if not candidates:
            return ""
        # max by length first, then quality rank
        candidates.sort(key=lambda x: (x[0], x[1]))
        return candidates[-1][2]

    def audio_duration_s(self) -> float:
        return (self.frames_sent * self.frame_ms) / 1000.0


def transcribe_pcm(
    pcm: bytes,
    *,
    auth: Auth | None = None,
    mode: str = "raw_f7",
    enable_twopass: bool = True,
    enable_threepass: bool = True,
    result_type: str = "full",
    realtime: bool = False,
    asr_token: str | None = None,
    on_result: Callable[[AsrResult], None] | None = None,
    on_event: Callable[[TranscriptEvent], None] | None = None,
) -> list[AsrResult]:
    sess = AsrSession(
        auth=auth,
        enable_twopass=enable_twopass,
        enable_threepass=enable_threepass,
        result_type=result_type,
        realtime=realtime,
        asr_token=asr_token,
        on_result=on_result,
        on_event=on_event,
    )
    try:
        sess.connect()
        sess.send_pcm_stream(pcm, pace=realtime)
        return sess.finish(wait_s=12.0)
    finally:
        sess.close()
