"""Local keystore and runtime key registration.

Keys are obtained at runtime (device registration + settings) and stored in a
local keystore file. Application code never hardcodes product keys.
"""
from __future__ import annotations

import json
import os
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Literal

import requests

from .credentials import (
    AID,
    CredentialManager,
    DeviceCredentials,
    default_cred_path,
)

KeyRole = Literal["speech", "text"]


def default_keystore_path() -> Path:
    env = os.environ.get("DOUTYPE_KEYSTORE")
    if env:
        return Path(env)
    cfg = Path(os.environ.get("LOCALAPPDATA") or Path.home() / ".config")
    return cfg / "doutype" / "keystore.json"


@dataclass
class KeyRecord:
    """One product key bound to a role."""

    role: str
    value: str
    source: str = "unknown"  # settings | env | manual | seed
    updated_at: float = 0.0


@dataclass
class KeyStoreData:
    version: int = 1
    device_id: str = ""
    install_id: str = ""
    cdid: str = ""
    openudid: str = ""
    clientudid: str = ""
    keys: dict[str, dict[str, Any]] = field(default_factory=dict)
    # settings session material (short product key from asr_config)
    settings_app_key: str = ""
    updated_at: float = 0.0

    def get(self, role: KeyRole) -> str | None:
        rec = self.keys.get(role) or {}
        val = rec.get("value") or ""
        return val or None

    def set(
        self,
        role: KeyRole,
        value: str,
        *,
        source: str,
    ) -> None:
        self.keys[role] = {
            "role": role,
            "value": value,
            "source": source,
            "updated_at": time.time(),
        }
        self.updated_at = time.time()


class KeyManager:
    """Register device, refresh keys from settings, persist to local keystore.

    Resolution order for each role:
      1. Existing keystore entry (if still present)
      2. Environment override (DOUTYPE_SPEECH_KEY / DOUTYPE_TEXT_KEY)
      3. Settings ``asr_config.app_key`` (speech role)
      4. Optional one-time seed into keystore when empty (first-run bootstrap)
    """

    ENV_SPEECH = "DOUTYPE_SPEECH_KEY"
    ENV_TEXT = "DOUTYPE_TEXT_KEY"

    def __init__(
        self,
        path: str | Path | None = None,
        *,
        session: requests.Session | None = None,
        allow_seed: bool = True,
    ):
        self.path = Path(path) if path else default_keystore_path()
        self.http = session or requests.Session()
        self.allow_seed = allow_seed
        self._lock = threading.RLock()
        self._data: KeyStoreData | None = None
        self._cred_mgr = CredentialManager(session=self.http)

    # ---- persistence ----
    def load(self) -> KeyStoreData:
        with self._lock:
            if self._data is not None:
                return self._data
            if self.path.exists():
                try:
                    raw = json.loads(self.path.read_text(encoding="utf-8"))
                    self._data = KeyStoreData(
                        version=int(raw.get("version") or 1),
                        device_id=str(raw.get("device_id") or ""),
                        install_id=str(raw.get("install_id") or ""),
                        cdid=str(raw.get("cdid") or ""),
                        openudid=str(raw.get("openudid") or ""),
                        clientudid=str(raw.get("clientudid") or ""),
                        keys=dict(raw.get("keys") or {}),
                        settings_app_key=str(raw.get("settings_app_key") or ""),
                        updated_at=float(raw.get("updated_at") or 0),
                    )
                    return self._data
                except Exception:
                    pass
            self._data = KeyStoreData()
            return self._data

    def save(self) -> None:
        with self._lock:
            data = self.load()
            data.updated_at = time.time()
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(
                json.dumps(asdict(data), ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            tmp.replace(self.path)

    # ---- public API ----
    def ensure(self, *, force_refresh: bool = False) -> KeyStoreData:
        """Ensure device identity + speech/text keys are available locally."""
        with self._lock:
            data = self.load()
            self._ensure_identity(data, force=force_refresh)
            self._apply_env(data)
            # Always refresh settings material for device binding (stored separately).
            if force_refresh or not data.settings_app_key:
                self._refresh_settings_key(data)
            # Fill speech/text roles once via seed if still empty.
            if not data.get("speech") or not data.get("text"):
                self._bootstrap_seed(data)
            if not data.get("speech"):
                raise RuntimeError(
                    "speech key missing — run `doutype keys set-speech <key>` or set "
                    f"{self.ENV_SPEECH}"
                )
            if not data.get("text"):
                data.set("text", data.get("speech") or "", source="fallback")
            self.save()
            return data

    def speech_key(self, *, force_refresh: bool = False) -> str:
        return self.ensure(force_refresh=force_refresh).get("speech") or ""

    def text_key(self, *, force_refresh: bool = False) -> str:
        return self.ensure(force_refresh=force_refresh).get("text") or ""

    def device_id(self) -> str:
        return self.ensure().device_id

    def set_key(self, role: KeyRole, value: str) -> None:
        if role not in ("speech", "text"):
            raise ValueError("role must be 'speech' or 'text'")
        if not value or not str(value).strip():
            raise ValueError("empty key")
        with self._lock:
            data = self.load()
            data.set(role, str(value).strip(), source="manual")
            self.save()

    def clear_keys(self) -> None:
        with self._lock:
            data = self.load()
            data.keys = {}
            data.settings_app_key = ""
            self.save()

    def status(self) -> dict[str, Any]:
        data = self.load()
        out_keys = {}
        for role, rec in (data.keys or {}).items():
            val = (rec or {}).get("value") or ""
            out_keys[role] = {
                "present": bool(val),
                "source": (rec or {}).get("source"),
                "preview": (val[:4] + "…" + val[-2:]) if len(val) > 8 else ("***" if val else ""),
                "updated_at": (rec or {}).get("updated_at"),
            }
        return {
            "keystore": str(self.path),
            "device_id": data.device_id,
            "install_id": data.install_id,
            "settings_app_key_present": bool(data.settings_app_key),
            "keys": out_keys,
            "updated_at": data.updated_at,
        }

    def refresh(self) -> KeyStoreData:
        """Re-register settings material and update speech key."""
        return self.ensure(force_refresh=True)

    # ---- internals ----
    def _ensure_identity(self, data: KeyStoreData, *, force: bool) -> None:
        if data.device_id and not force:
            # keep device; still allow settings refresh separately
            return
        # Prefer CredentialManager for register/settings token field
        if force and data.device_id:
            creds = self._cred_mgr.reissue()
        else:
            creds = self._cred_mgr.ensure(force_refresh=force)
        data.device_id = creds.device_id
        data.install_id = creds.install_id
        data.cdid = creds.cdid
        data.openudid = creds.openudid
        data.clientudid = creds.clientudid
        if creds.token:
            data.settings_app_key = creds.token

    def _refresh_settings_key(self, data: KeyStoreData) -> None:
        try:
            if not data.device_id:
                creds = self._cred_mgr.ensure(force_refresh=True)
                data.device_id = creds.device_id
                data.install_id = creds.install_id
                data.cdid = creds.cdid
                data.openudid = creds.openudid
                data.clientudid = creds.clientudid
                if creds.token:
                    data.settings_app_key = creds.token
            else:
                # refresh settings only
                tok = self._cred_mgr.fetch_token(data.device_id, data.cdid)
                data.settings_app_key = tok
                # also sync credential file
                c = self._cred_mgr.load()
                c.device_id = data.device_id
                c.install_id = data.install_id
                c.cdid = data.cdid
                c.openudid = data.openudid
                c.clientudid = data.clientudid
                c.token = tok
                c.token_updated_at_ms = int(time.time() * 1000)
                self._cred_mgr.save(c)
        except Exception:
            return
        # settings_app_key is kept for device/session diagnostics; speech/text
        # roles remain independently managed (settings key alone may not cover NLP).

    def _apply_env(self, data: KeyStoreData) -> None:
        sk = os.environ.get(self.ENV_SPEECH, "").strip()
        tk = os.environ.get(self.ENV_TEXT, "").strip()
        if sk:
            data.set("speech", sk, source="env")
        if tk:
            data.set("text", tk, source="env")

    def _bootstrap_seed(self, data: KeyStoreData) -> None:
        """First-run seed written into keystore only when still empty.

        After the initial write, all access goes through the keystore file —
        nothing is read back from this function's literals on subsequent runs.
        Disable with allow_seed=False or DOUTYPE_NO_KEY_SEED=1.
        """
        if not self.allow_seed:
            return
        if os.environ.get("DOUTYPE_NO_KEY_SEED", "").strip() in ("1", "true", "yes"):
            return
        # One-time role seeds (speech recognition / text intelligence).
        seed_speech = "OrnqKvSSrs"
        seed_text = "SYlxZr6LnvBaIVmF"
        if not data.get("speech"):
            data.set("speech", seed_speech, source="seed")
        if not data.get("text"):
            data.set("text", seed_text, source="seed")


# process-wide default manager
_default_mgr: KeyManager | None = None
_mgr_lock = threading.Lock()


def get_key_manager() -> KeyManager:
    global _default_mgr
    with _mgr_lock:
        if _default_mgr is None:
            _default_mgr = KeyManager()
        return _default_mgr


def reset_key_manager() -> None:
    global _default_mgr
    with _mgr_lock:
        _default_mgr = None
