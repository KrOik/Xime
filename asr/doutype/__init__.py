"""DouType — speech recognition and text intelligence services.

Public package surface. Prefer importing from here rather than submodules.
"""
from __future__ import annotations

__version__ = "1.0.0"

from .client import Client, DoubaoIMEClient
from .asr_ws import AsrSession, AsrResult
from .transcribe import TranscriptResult, transcribe_file, transcribe_pcm
from .events import TranscriptEvent, DeltaAssembler
from .audio import FRAME_MS, BYTES_PER_FRAME, SAMPLE_RATE, decode_to_pcm16k
from .services.asr import AsrService
from .services.nlp import NlpService
from .services.live import LiveAsrService
from .services.api import create_app
from .keys import KeyManager, get_key_manager

__all__ = [
    "__version__",
    "Client",
    "DoubaoIMEClient",
    "AsrService",
    "NlpService",
    "LiveAsrService",
    "AsrSession",
    "AsrResult",
    "TranscriptResult",
    "TranscriptEvent",
    "DeltaAssembler",
    "transcribe_file",
    "transcribe_pcm",
    "decode_to_pcm16k",
    "FRAME_MS",
    "BYTES_PER_FRAME",
    "SAMPLE_RATE",
    "create_app",
    "KeyManager",
    "get_key_manager",
]
