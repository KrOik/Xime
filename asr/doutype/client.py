"""Text intelligence and speech client facade."""
from __future__ import annotations

import uuid
from typing import Any

import requests

from .auth import APP_ID, APP_VERSION, Auth
from .asr_ws import AsrSession, AsrResult, transcribe_pcm


class Client:
    """Facade for speech and text intelligence APIs."""

    def __init__(self, device_id: str | None = None, session: requests.Session | None = None):
        self.auth = Auth(device_id=device_id, session=session)
        self.device_id = self.auth.device_id
        self._sess = session or requests.Session()

    # ---- auth ----
    def refresh_tokens(self) -> dict[str, Any]:
        return self.auth.refresh_all()

    def _speech_headers(self) -> dict[str, str]:
        tok = self.auth.http_token()
        return {
            "Content-Type": "application/json",
            "User-Agent": f"DoubaoIME/{APP_VERSION} (Android; appid={APP_ID})",
            "app_version": APP_VERSION,
            "app_id": APP_ID,
            "os_type": "Android",
            "did": self.device_id,
            "X-Api-Resource-Id": "asr.user.context",
            "X-Api-App-Key": tok.appkey,
            "X-Api-Token": tok.token,
            "X-Api-Request-Id": str(uuid.uuid4()),
            "X-Api-Sequence": "-1",
            "x-tt-e-k": f"{self.device_id}+W",
            "x-tt-e-b": "1",
        }

    def _ime_headers(self) -> dict[str, str]:
        return {
            "Content-Type": "application/json",
            "User-Agent": f"DoubaoIME/{APP_VERSION} (Android; appid={APP_ID})",
            "app_version": APP_VERSION,
            "app_id": APP_ID,
            "os_type": "Android",
            "did": self.device_id,
            "X-Request-Id": str(uuid.uuid4()),
        }

    def _user_block(self) -> dict[str, Any]:
        return {
            "uid": self.device_id,
            "did": self.device_id,
            "app_name": "com.bytedance.doubaoime",
            "app_version": APP_VERSION,
            "sdk_version": "",
            "platform": "android",
            "experience_improve": True,
        }

    # ---- text intelligence ----
    def ner(self, text: str) -> dict[str, Any]:
        """Named entity recognition."""
        url = "https://speech.bytedance.com/api/v3/context/ime/ner"
        body = {"user": self._user_block(), "text": text, "additions": {}}
        return self._post(url, body, headers=self._speech_headers())

    def modify_pair(
        self, src: str, dst: str, text: str = "", position: int = 0
    ) -> dict[str, Any]:
        """Upload correction pair src→dst for personalization."""
        url = "https://speech.bytedance.com/api/v3/context/ime/modify_pair"
        body = {
            "user": self._user_block(),
            "modify_data": {
                "pair_src": src,
                "pair_dst": dst,
                "position": position,
                "text": text or f"{src}{dst}",
                "additions": {},
            },
        }
        return self._post(url, body, headers=self._speech_headers())

    def user_words(
        self,
        words: list[str] | str | None = None,
        common_words: list[str] | str | None = None,
    ) -> dict[str, Any]:
        """Upload hot words / personal lexicon.

        Server requires words as *string* (not list) — typically comma/space
        separated. We accept list and join.
        """
        url = "https://speech.bytedance.com/api/v3/context/ime/user_words"

        def as_str(v: list[str] | str | None) -> str | None:
            if v is None:
                return None
            if isinstance(v, str):
                return v
            return ",".join(v)

        # try the shapes that match server error ("convert key words to str")
        w = as_str(words)
        cw = as_str(common_words)
        candidates: list[dict[str, Any]] = []
        if w is not None:
            candidates.extend(
                [
                    {"user": self._user_block(), "user_words": w, "additions": {}},
                    {"user": self._user_block(), "user_words": {"words": w}, "additions": {}},
                    {"user": self._user_block(), "words": w, "additions": {}},
                    {
                        "user": self._user_block(),
                        "user_words": w,
                        "common_words": cw or "",
                        "additions": {},
                    },
                ]
            )
        last: dict[str, Any] = {"ok": False, "status": 0, "headers": {}, "data": {}}
        for body in candidates:
            last = self._post(url, body, headers=self._speech_headers())
            data = last.get("data") or {}
            header = data.get("header") or {}
            code = header.get("code") or data.get("code")
            if last["ok"] and code in (None, 0, 20000000, "0"):
                last["matched_body"] = body
                return last
            # also accept empty success
            if last["status"] == 200 and not header:
                last["matched_body"] = body
                return last
        return last

    # ---- IME main APIs ----
    def rectify_text(
        self, text: str, rectify_type: int = 0, grammar: bool = True
    ) -> dict[str, Any]:
        """AI text correction."""
        url = "https://ime.doubao.com/api/v1/rectify_text"
        body = {"text": text, "rectify_type": rectify_type, "grammar": grammar}
        return self._post(url, body, headers=self._ime_headers())

    def asr_fmt(self, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        """ASR formatting helper endpoint."""
        url = "https://ime.doubao.com/api/v1/asr/fmt"
        return self._post(url, payload or {}, headers=self._ime_headers())

    # ---- ASR ----
    def open_asr(self, **kwargs) -> AsrSession:
        return AsrSession(auth=self.auth, **kwargs)

    def transcribe_pcm(self, pcm: bytes, **kwargs) -> list[AsrResult]:
        return transcribe_pcm(pcm, auth=self.auth, **kwargs)

    # ---- internal ----
    def _post(
        self, url: str, body: dict[str, Any], headers: dict[str, str]
    ) -> dict[str, Any]:
        resp = self._sess.post(url, json=body, headers=headers, timeout=20)
        try:
            data = resp.json()
        except Exception:
            data = {"_raw": resp.text[:500]}
        return {
            "ok": 200 <= resp.status_code < 300,
            "status": resp.status_code,
            "headers": {
                k: resp.headers.get(k)
                for k in ("x-tt-logid", "x-api-status-code", "content-type")
                if resp.headers.get(k)
            },
            "data": data,
        }

# backward-compatible alias
DoubaoIMEClient = Client
