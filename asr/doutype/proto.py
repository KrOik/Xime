"""Lightweight message field codec for the speech wire format."""
from __future__ import annotations

from typing import Any


def varint(v: int) -> bytes:
    buf = bytearray()
    v &= (1 << 64) - 1
    while v > 0x7F:
        buf.append((v & 0x7F) | 0x80)
        v >>= 7
    buf.append(v & 0x7F)
    return bytes(buf)


def tag(fn: int, wt: int) -> bytes:
    return varint((fn << 3) | wt)


def str_field(fn: int, val: str) -> bytes:
    enc = val.encode("utf-8")
    return tag(fn, 2) + varint(len(enc)) + enc


def bytes_field(fn: int, val: bytes) -> bytes:
    return tag(fn, 2) + varint(len(val)) + val


def int_field(fn: int, val: int) -> bytes:
    return tag(fn, 0) + varint(val)


def parse_pb(data: bytes) -> dict[int, tuple[str, Any]]:
    result: dict[int, tuple[str, Any]] = {}
    pos = 0
    n = len(data)
    while pos < n:
        t = s = 0
        while pos < n:
            b = data[pos]
            pos += 1
            t |= (b & 0x7F) << s
            s += 7
            if not (b & 0x80):
                break
        fn, wt = t >> 3, t & 7
        if wt == 0:
            v = s = 0
            while pos < n:
                b = data[pos]
                pos += 1
                v |= (b & 0x7F) << s
                s += 7
                if not (b & 0x80):
                    break
            result[fn] = ("varint", v)
        elif wt == 2:
            ln = s = 0
            while pos < n:
                b = data[pos]
                pos += 1
                ln |= (b & 0x7F) << s
                s += 7
                if not (b & 0x80):
                    break
            raw = data[pos : pos + ln]
            pos += ln
            try:
                result[fn] = ("string", raw.decode("utf-8"))
            except UnicodeDecodeError:
                result[fn] = ("bytes", raw)
        elif wt == 1:
            if pos + 8 > n:
                break
            result[fn] = ("fixed64", data[pos : pos + 8])
            pos += 8
        elif wt == 5:
            if pos + 4 > n:
                break
            result[fn] = ("fixed32", data[pos : pos + 4])
            pos += 4
        else:
            break
    return result


# WebSocketRequest field numbers (declaration-order heuristic, verified live)
F_TOKEN = 1
F_APPKEY = 2
F_NAMESPACE = 3
F_VERSION = 4
F_EVENT = 5
F_PAYLOAD = 6
F_TASK_ID = 7
F_SESSION_ID = 8


def build_ws_request(
    *,
    token: str,
    appkey: str,
    event: str,
    task_id: str,
    session_id: str = "",
    namespace: str = "",
    version: str = "v2",
    payload: str = "",
    extra: bytes = b"",
) -> bytes:
    parts = [
        str_field(F_TOKEN, token),
        str_field(F_APPKEY, appkey),
    ]
    if namespace:
        parts.append(str_field(F_NAMESPACE, namespace))
    if version:
        parts.append(str_field(F_VERSION, version))
    parts.append(str_field(F_EVENT, event))
    if payload:
        parts.append(str_field(F_PAYLOAD, payload))
    parts.append(str_field(F_TASK_ID, task_id))
    if session_id:
        parts.append(str_field(F_SESSION_ID, session_id))
    if extra:
        parts.append(extra)
    return b"".join(parts)
