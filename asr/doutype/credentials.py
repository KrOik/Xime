"""Optional device identity and settings-based session material."""
from __future__ import annotations

import hashlib
import json
import os
import secrets
import time
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import requests

APP_ID = "401734"
APP_VERSION = "1.1.2"

REGISTER_URL = "https://log.snssdk.com/service/2/device_register/"
SETTINGS_URL = "https://is.snssdk.com/service/settings/v3/"
AID = int(APP_ID)
DEFAULT_UA = (
    f"com.bytedance.android.doubaoime/100102018 "
    f"(Linux; U; Android 16; en_US; Pixel 7 Pro; Build/BP2A.250605.031.A2; "
    f"Cronet/TTNetVersion:94cf429a 2025-11-17 QuicVersion:1f89f732 2025-05-08)"
)
TOKEN_REFRESH_S = 12 * 3600


@dataclass
class DeviceCredentials:
    device_id: str = ""
    install_id: str = ""
    cdid: str = ""
    openudid: str = ""
    clientudid: str = ""
    token: str = ""  # settings session material
    token_updated_at_ms: int = 0

    def token_fresh(self) -> bool:
        if not self.token or not self.token_updated_at_ms:
            return False
        age = time.time() - self.token_updated_at_ms / 1000
        return age < TOKEN_REFRESH_S


def default_cred_path() -> Path:
    base = os.environ.get("DOUTYPE_CRED_PATH")
    if base:
        return Path(base)
    cfg = Path(os.environ.get("LOCALAPPDATA") or Path.home() / ".config")
    return cfg / "doutype" / "credentials.json"


class CredentialManager:
    """Register a device identity and fetch session material from settings."""

    def __init__(
        self,
        path: str | Path | None = None,
        user_agent: str = DEFAULT_UA,
        session: requests.Session | None = None,
    ):
        self.path = Path(path) if path else default_cred_path()
        self.user_agent = user_agent
        self.http = session or requests.Session()

    def load(self) -> DeviceCredentials:
        if not self.path.exists():
            return DeviceCredentials()
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            return DeviceCredentials(**{k: data.get(k, "") for k in DeviceCredentials.__dataclass_fields__})
        except Exception:
            return DeviceCredentials()

    def save(self, creds: DeviceCredentials) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(
            json.dumps(asdict(creds), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def ensure(self, force_refresh: bool = False) -> DeviceCredentials:
        creds = self.load()
        if not creds.device_id:
            creds = self.register()
            self.save(creds)
        if not force_refresh and creds.token_fresh():
            return creds
        try:
            tok = self.fetch_token(creds.device_id, creds.cdid)
            creds.token = tok
            creds.token_updated_at_ms = int(time.time() * 1000)
            self.save(creds)
        except Exception:
            if creds.token:
                return creds
            raise
        return creds

    def reissue(self) -> DeviceCredentials:
        creds = self.register()
        creds.token = self.fetch_token(creds.device_id, creds.cdid)
        creds.token_updated_at_ms = int(time.time() * 1000)
        self.save(creds)
        return creds

    def register(self) -> DeviceCredentials:
        cdid = str(uuid.uuid4())
        openudid = secrets.token_hex(8)
        clientudid = str(uuid.uuid4())
        now = int(time.time() * 1000)
        body = {
            "magic_tag": "ss_app_log",
            "header": {
                "device_id": 0,
                "install_id": 0,
                "aid": AID,
                "app_name": "oime",
                "version_code": 100102018,
                "version_name": "1.1.2",
                "manifest_version_code": 100102018,
                "update_version_code": 100102018,
                "channel": "official",
                "package": "com.bytedance.android.doubaoime",
                "device_platform": "android",
                "os": "android",
                "os_api": "34",
                "os_version": "16",
                "device_type": "Pixel 7 Pro",
                "device_brand": "google",
                "device_model": "Pixel 7 Pro",
                "resolution": "1080*2400",
                "dpi": "420",
                "language": "zh",
                "timezone": 8,
                "access": "wifi",
                "rom": "UP1A.231005.007",
                "rom_version": "UP1A.231005.007",
                "region": "CN",
                "tz_name": "Asia/Shanghai",
                "tz_offset": 28800,
                "sim_region": "cn",
                "carrier_region": "cn",
                "cpu_abi": "arm64-v8a",
                "build_serial": "unknown",
                "not_request_sender": 0,
                "openudid": openudid,
                "clientudid": clientudid,
                "cdid": cdid,
            },
            "_gen_time": now,
        }
        params = {
            "device_platform": "android",
            "os": "android",
            "ssmix": "a",
            "_rticket": str(now),
            "cdid": cdid,
            "channel": "official",
            "aid": str(AID),
            "app_name": "oime",
            "version_code": "100102018",
            "version_name": "1.1.2",
            "manifest_version_code": "100102018",
            "update_version_code": "100102018",
            "resolution": "1080*2400",
            "dpi": "420",
            "device_type": "Pixel 7 Pro",
            "device_brand": "google",
            "language": "zh",
            "os_api": "34",
            "os_version": "16",
            "ac": "wifi",
        }
        resp = self.http.post(
            REGISTER_URL,
            params=params,
            json=body,
            headers={
                "Content-Type": "application/json",
                "User-Agent": self.user_agent,
            },
            timeout=20,
        )
        resp.raise_for_status()
        out = resp.json()
        device_id = str(out.get("device_id_str") or out.get("device_id") or "")
        if not device_id or device_id == "0":
            raise RuntimeError(f"device register missing device_id: {out}")
        return DeviceCredentials(
            device_id=device_id,
            install_id=str(out.get("install_id") or ""),
            cdid=cdid,
            openudid=openudid,
            clientudid=clientudid,
        )

    def fetch_token(self, device_id: str, cdid: str) -> str:
        body = "body=null"
        stub = hashlib.md5(body.encode()).hexdigest().upper()
        params = {
            "device_platform": "android",
            "os": "android",
            "ssmix": "a",
            "channel": "official",
            "aid": str(AID),
            "app_name": "oime",
            "version_code": "100102018",
            "version_name": "1.1.2",
            "device_id": device_id,
            "cdid": cdid,
            "_rticket": str(int(time.time() * 1000)),
        }
        resp = self.http.post(
            SETTINGS_URL,
            params=params,
            data=body,
            headers={
                "User-Agent": self.user_agent,
                "x-ss-stub": stub,
                "Content-Type": "application/x-www-form-urlencoded",
            },
            timeout=20,
        )
        resp.raise_for_status()
        out = resp.json()
        try:
            app_key = out["data"]["settings"]["asr_config"]["app_key"]
        except Exception as e:
            raise RuntimeError(f"settings missing asr_config.app_key: {out}") from e
        if not app_key:
            raise RuntimeError("empty asr_config.app_key")
        return app_key
