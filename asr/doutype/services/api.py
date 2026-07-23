"""HTTP application factory — minimal engine surface.

Exposed routes (engine only):
  GET  /health
  GET  /v1/models
  POST /v1/audio/transcriptions
  POST /v1/nlp/entities
  POST /v1/nlp/hotwords
  POST /v1/nlp/correction
  POST /v1/nlp/proofread
  POST /v1/live/sessions
  POST /v1/live/sessions/<id>/audio
  GET  /v1/live/sessions/<id>
  DELETE /v1/live/sessions/<id>

No UI static hosting and no legacy /api/* aliases.
"""
from __future__ import annotations

import os
import tempfile
from pathlib import Path

from flask import Flask, Response, jsonify, request

from .asr import AsrService
from .live import LiveAsrService
from .nlp import NlpService


def create_app(
    *,
    asr: AsrService | None = None,
    nlp: NlpService | None = None,
    live: LiveAsrService | None = None,
    api_token: str | None = None,
    enable_live: bool = True,
    enable_nlp: bool = True,
) -> Flask:
    """Create a Flask app with a minimal speech/text engine API.

    Parameters
    ----------
    enable_live:
        When False, live session routes are not registered.
    enable_nlp:
        When False, text intelligence routes are not registered.
    """
    app = Flask("doutype")
    asr = asr or AsrService()
    nlp = nlp or NlpService(client=asr.client)
    live = live or LiveAsrService(client=asr.client)
    token = (
        api_token
        if api_token is not None
        else os.environ.get("DOUTYPE_API_TOKEN", "")
    )

    def _auth_ok() -> bool:
        if not token:
            return True
        h = request.headers.get("Authorization") or ""
        return (
            h == f"Bearer {token}"
            or h == token
            or request.headers.get("api-key") == token
        )

    def _err(message: str, code: int = 400, type_: str = "invalid_request"):
        return (
            jsonify(
                {"error": {"message": message, "type": type_, "code": str(code)}}
            ),
            code,
        )

    @app.get("/health")
    def health():
        return jsonify(
            {
                "ok": True,
                "device_id": asr.device_id,
                "version": "1.0.0",
                "features": {
                    "transcription": True,
                    "nlp": enable_nlp,
                    "live": enable_live,
                },
            }
        )

    @app.get("/v1/models")
    def models():
        if not _auth_ok():
            return _err("access denied", 401, "auth")
        return jsonify(
            {
                "object": "list",
                "data": [
                    {"id": "doutype", "object": "model", "owned_by": "doutype"}
                ],
            }
        )

    @app.post("/v1/audio/transcriptions")
    def transcriptions():
        if not _auth_ok():
            return _err("access denied", 401, "auth")
        f = request.files.get("file")
        if not f:
            return _err("file required")
        response_format = (
            request.form.get("response_format")
            or request.form.get("format")
            or "json"
        )
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / (f.filename or "audio.bin")
            f.save(path)
            try:
                result = asr.transcribe_file(path)
            except Exception as e:
                return _err(str(e), 500, "asr_error")
        if response_format in ("text", "plain"):
            return Response(result.text, mimetype="text/plain; charset=utf-8")
        if response_format == "srt":
            return Response(
                result.format("srt"), mimetype="text/plain; charset=utf-8"
            )
        if response_format == "vtt":
            return Response(
                result.format("vtt"), mimetype="text/plain; charset=utf-8"
            )
        if response_format == "verbose_json":
            return Response(
                result.format("verbose_json"), mimetype="application/json"
            )
        return jsonify({"text": result.text})

    if enable_nlp:

        @app.post("/v1/nlp/entities")
        def nlp_entities():
            if not _auth_ok():
                return _err("access denied", 401, "auth")
            body = request.get_json(silent=True) or {}
            return jsonify(nlp.entities(body.get("text") or ""))

        @app.post("/v1/nlp/hotwords")
        def nlp_hotwords():
            if not _auth_ok():
                return _err("access denied", 401, "auth")
            body = request.get_json(silent=True) or {}
            return jsonify(nlp.set_hotwords(body.get("words") or []))

        @app.post("/v1/nlp/correction")
        def nlp_correction():
            if not _auth_ok():
                return _err("access denied", 401, "auth")
            body = request.get_json(silent=True) or {}
            return jsonify(
                nlp.add_correction(
                    body.get("source") or body.get("src") or "",
                    body.get("target") or body.get("dst") or "",
                    text=body.get("text") or "",
                    position=int(body.get("position") or 0),
                )
            )

        @app.post("/v1/nlp/proofread")
        def nlp_proofread():
            if not _auth_ok():
                return _err("access denied", 401, "auth")
            body = request.get_json(silent=True) or {}
            return jsonify(nlp.proofread(body.get("text") or ""))

    if enable_live:

        @app.post("/v1/live/sessions")
        def live_start():
            if not _auth_ok():
                return _err("access denied", 401, "auth")
            body = request.get_json(silent=True) or {}
            try:
                sid = live.start(**body)
            except Exception as e:
                return _err(str(e), 500, "session_error")
            return jsonify({"ok": True, "session_id": sid})

        @app.post("/v1/live/sessions/<sid>/audio")
        def live_audio(sid: str):
            if not _auth_ok():
                return _err("access denied", 401, "auth")
            data = request.get_data()
            try:
                return jsonify(live.push_audio(sid, data))
            except KeyError:
                return _err("session not found", 404)
            except Exception as e:
                return _err(str(e), 500, "audio_error")

        @app.get("/v1/live/sessions/<sid>")
        def live_poll(sid: str):
            if not _auth_ok():
                return _err("access denied", 401, "auth")
            try:
                return jsonify(live.poll(sid))
            except KeyError:
                return _err("session not found", 404)

        @app.delete("/v1/live/sessions/<sid>")
        def live_stop(sid: str):
            if not _auth_ok():
                return _err("access denied", 401, "auth")
            try:
                return jsonify(live.stop(sid))
            except KeyError:
                return _err("session not found", 404)
            except Exception as e:
                return _err(str(e), 500, "stop_error")

    return app


def run(
    host: str = "127.0.0.1",
    port: int = 8080,
    *,
    enable_live: bool = True,
    enable_nlp: bool = True,
) -> None:
    app = create_app(enable_live=enable_live, enable_nlp=enable_nlp)
    print(f"DouType API → http://{host}:{port}/")
    app.run(host=host, port=port, debug=False, threaded=True)


if __name__ == "__main__":
    run(
        host=os.environ.get("HOST", "127.0.0.1"),
        port=int(os.environ.get("PORT", "8080")),
        enable_live=os.environ.get("DOUTYPE_ENABLE_LIVE", "1") not in ("0", "false"),
        enable_nlp=os.environ.get("DOUTYPE_ENABLE_NLP", "1") not in ("0", "false"),
    )
