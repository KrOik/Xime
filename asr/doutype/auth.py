"""Session token acquisition and cache.

Product keys are resolved via :class:`doutype.keys.KeyManager` (local keystore,
runtime settings registration, or environment). They are not defined as public
module constants.
"""
from __future__ import annotations

import base64
import json
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

import requests

from .keys import get_key_manager
from .credentials import APP_ID, APP_VERSION

TOKEN_URLS = (
    "https://ime.oceancloudapi.com/api/v1/user/get_config",
    "https://ime.doubao.com/api/v1/user/get_config",
)


@dataclass
class TokenBundle:
    token: str
    appkey: str
    claims: dict[str, Any] = field(default_factory=dict)
    fetched_at: float = 0.0
    # refresh slightly before expiry
    ttl_s: float = 9 * 3600

    @property
    def expired(self) -> bool:
        return (time.time() - self.fetched_at) > self.ttl_s

    @property
    def user_id(self) -> int | str | None:
        return self.claims.get("user_id")

    @property
    def app_id(self) -> int | str | None:
        return self.claims.get("app_id")


def _decode_claims(token: str) -> dict[str, Any]:
    try:
        part = token.split(".")[1]
        part += "=" * (-len(part) % 4)
        return json.loads(base64.urlsafe_b64decode(part))
    except Exception:
        return {}


class Auth:
    """Fetches and caches session tokens for speech and text roles."""

    def __init__(
        self,
        device_id: str | None = None,
        session: requests.Session | None = None,
        *,
        key_manager=None,
    ):
        self._keys = key_manager or get_key_manager()
        # ensure device + keys before first use
        store = self._keys.ensure()
        self.device_id = device_id or store.device_id or f"device_{uuid.uuid4().hex[:16]}"
        # keep keystore device as source of truth when present
        if store.device_id and not device_id:
            self.device_id = store.device_id
        self._sess = session or requests.Session()
        self._lock = threading.Lock()
        self._cache: dict[str, TokenBundle] = {}

    def speech_appkey(self, force_refresh: bool = False) -> str:
        """Product key used for speech recognition sessions."""
        return self._keys.speech_key(force_refresh=force_refresh)

    def text_appkey(self, force_refresh: bool = False) -> str:
        """Product key used for text intelligence endpoints."""
        return self._keys.text_key(force_refresh=force_refresh)

    # backward-compatible names (not hardcoded values)
    @property
    def KEY_ASR(self) -> str:
        return self.speech_appkey()

    @property
    def KEY_HTTP(self) -> str:
        return self.text_appkey()

    def get(self, appkey: str, force: bool = False) -> TokenBundle:
        with self._lock:
            cached = self._cache.get(appkey)
            if cached and not cached.expired and not force:
                return cached
            bundle = self._fetch(appkey)
            self._cache[appkey] = bundle
            return bundle

    def http_token(self, force: bool = False) -> TokenBundle:
        return self.get(self.text_appkey(force_refresh=force), force=force)

    def asr_token(self, force: bool = False) -> TokenBundle:
        return self.get(self.speech_appkey(force_refresh=force), force=force)

    def refresh_all(self) -> dict[str, Any]:
        """Force-refresh keys from settings and re-issue tokens."""
        self._keys.refresh()
        http = self.http_token(force=True)
        asr = self.asr_token(force=True)
        return {
            "device_id": self.device_id,
            "speech_key_source": (self._keys.load().keys.get("speech") or {}).get("source"),
            "text_key_source": (self._keys.load().keys.get("text") or {}).get("source"),
            "http": {
                "appkey_preview": _preview(http.appkey),
                "claims": http.claims,
            },
            "asr": {
                "appkey_preview": _preview(asr.appkey),
                "claims": asr.claims,
            },
        }

    def _fetch(self, appkey: str) -> TokenBundle:
        last_err: Exception | None = None
        headers = {
            "Content-Type": "application/json",
            "X-Request-Id": str(uuid.uuid4()),
            "User-Agent": f"DoubaoIME/{APP_VERSION} (Android; appid={APP_ID})",
        }
        body = {"sami_app_key": appkey}
        for url in TOKEN_URLS:
            try:
                resp = self._sess.post(url, headers=headers, json=body, timeout=15)
                data = resp.json() if resp.content else {}
                tok = (
                    (data.get("Data") or data.get("data") or {}).get("sami_token")
                    or data.get("sami_token")
                )
                if resp.status_code == 200 and tok:
                    return TokenBundle(
                        token=tok,
                        appkey=appkey,
                        claims=_decode_claims(tok),
                        fetched_at=time.time(),
                    )
                last_err = RuntimeError(f"{url} -> {resp.status_code} {resp.text[:200]}")
            except Exception as e:
                last_err = e
        raise RuntimeError(f"failed to fetch token for role key: {last_err}")


def _preview(value: str) -> str:
    if not value:
        return ""
    if len(value) <= 6:
        return "***"
    return value[:3] + "…" + value[-2:]


# Module-level accessors for legacy imports — values come from keystore at call time.
def KEY_ASR() -> str:  # type: ignore[misc]
    return get_key_manager().speech_key()


def KEY_HTTP() -> str:  # type: ignore[misc]
    return get_key_manager().text_key()


# Keep names importable as callables; asr_ws will use token bundle appkey instead.
