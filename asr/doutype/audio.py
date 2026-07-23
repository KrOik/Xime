"""PCM frame helpers and media decode utilities."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

# Stream defaults ()
SAMPLE_RATE = 16000
CHANNELS = 1
BITS = 16
FRAME_MS = 20
BYTES_PER_SAMPLE = BITS // 8 * CHANNELS  # 2
SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS // 1000  # 320
BYTES_PER_FRAME = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE  # 640


def frame_bytes(rate: int = SAMPLE_RATE, bits: int = BITS, channel: int = CHANNELS, frame_ms: int = FRAME_MS) -> int:
    bps = max(bits // 8, 1) * max(channel, 1)
    return max(rate * bps * frame_ms // 1000, 320)


def pad_frame(chunk: bytes, frame_size: int = BYTES_PER_FRAME) -> bytes:
    if len(chunk) >= frame_size:
        return chunk[:frame_size]
    return chunk + b"\x00" * (frame_size - len(chunk))


def iter_frames(
    pcm: bytes,
    *,
    frame_size: int = BYTES_PER_FRAME,
    pad_last: bool = True,
):
    """Yield fixed-size PCM frames. Incomplete last frame is zero-padded if pad_last."""
    n = len(pcm)
    if n == 0:
        return
    for i in range(0, n, frame_size):
        chunk = pcm[i : i + frame_size]
        if len(chunk) < frame_size:
            if not pad_last:
                if chunk:
                    yield chunk
                break
            chunk = pad_frame(chunk, frame_size)
        yield chunk


def split_pcm_chunks(
    pcm: bytes,
    *,
    chunk_ms: int = 30_000,
    frame_size: int = BYTES_PER_FRAME,
) -> list[bytes]:
    """Split PCM into ~chunk_ms pieces aligned to frame boundaries."""
    if not pcm:
        return []
    chunk_bytes = max(frame_size, (SAMPLE_RATE * BYTES_PER_SAMPLE * chunk_ms // 1000) // frame_size * frame_size)
    out: list[bytes] = []
    for i in range(0, len(pcm), chunk_bytes):
        part = pcm[i : i + chunk_bytes]
        # align length down to frame if not last? keep full and pad in sender
        out.append(part)
    return out


def find_ffmpeg() -> str | None:
    env = os.environ.get("FFMPEG")
    if env and Path(env).exists():
        return env
    which = shutil.which("ffmpeg")
    if which:
        return which
    # common winget path used in this workspace
    candidates = list(Path(os.environ.get("LOCALAPPDATA", "")).glob(
        "Microsoft/WinGet/Packages/Gyan.FFmpeg*/ffmpeg-*/bin/ffmpeg.EXE"
    ))
    if candidates:
        return str(sorted(candidates)[-1])
    return None


def decode_to_pcm16k(path: str | Path, *, ffmpeg: str | None = None) -> bytes:
    """Decode any audio/video file to 16 kHz mono s16le PCM via ffmpeg."""
    path = Path(path)
    if not path.exists():
        raise FileNotFoundError(path)
    if path.suffix.lower() == ".pcm":
        return path.read_bytes()

    ff = ffmpeg or find_ffmpeg()
    if not ff:
        raise RuntimeError("ffmpeg not found; install ffmpeg or set FFMPEG=")

    with tempfile.TemporaryDirectory() as td:
        out = Path(td) / "out.pcm"
        cmd = [
            ff, "-y", "-i", str(path),
            "-ac", "1", "-ar", str(SAMPLE_RATE),
            "-f", "s16le", str(out),
        ]
        subprocess.run(
            cmd,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=600,
        )
        return out.read_bytes()


def pcm_duration_s(pcm: bytes, rate: int = SAMPLE_RATE, channel: int = CHANNELS, bits: int = BITS) -> float:
    bps = max(bits // 8, 1) * max(channel, 1)
    if bps <= 0:
        return 0.0
    return len(pcm) / (rate * bps)
