"""Command-line entry: doutype doctor / keys / transcribe / serve.

Web UI is an example/test harness under examples/webui_test/, not a CLI command.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def cmd_keys(args: argparse.Namespace) -> int:
    from .keys import get_key_manager

    km = get_key_manager()
    if args.action == "status":
        print(json.dumps(km.status(), ensure_ascii=False, indent=2))
        return 0
    if args.action == "refresh":
        km.refresh()
        print(json.dumps(km.status(), ensure_ascii=False, indent=2))
        return 0
    if args.action == "set-speech":
        if not args.value:
            print("usage: doutype keys set-speech <key>", file=sys.stderr)
            return 2
        km.set_key("speech", args.value)
        print("speech key saved")
        return 0
    if args.action == "set-text":
        if not args.value:
            print("usage: doutype keys set-text <key>", file=sys.stderr)
            return 2
        km.set_key("text", args.value)
        print("text key saved")
        return 0
    if args.action == "clear":
        km.clear_keys()
        print("keys cleared (device identity retained)")
        return 0
    print("unknown action", args.action, file=sys.stderr)
    return 2


def cmd_doctor(_: argparse.Namespace) -> int:
    from . import __version__
    from .audio import BYTES_PER_FRAME, FRAME_MS, SAMPLE_RATE, find_ffmpeg
    from .auth import Auth
    from .keys import get_key_manager

    print(f"DouType {__version__}")
    ff = find_ffmpeg()
    print(f"  ffmpeg: {ff or 'not found (optional for non-PCM files)'}")
    print(
        f"  frame: {FRAME_MS} ms / {BYTES_PER_FRAME} bytes @ {SAMPLE_RATE} Hz mono s16le"
    )
    try:
        st = get_key_manager().status()
        print(f"  keystore: {st['keystore']}")
        for role, info in (st.get("keys") or {}).items():
            print(
                f"  key.{role}: {'yes' if info.get('present') else 'no'} "
                f"source={info.get('source')} preview={info.get('preview')}"
            )
    except Exception as e:
        print(f"  keystore: error ({e})")
    try:
        a = Auth()
        t = a.asr_token()
        print(f"  session: ready (device={a.device_id})")
        _ = t
    except Exception as e:
        print(f"  session: unavailable ({e})")
        return 1
    return 0


def cmd_transcribe(args: argparse.Namespace) -> int:
    from .services.asr import AsrService

    svc = AsrService(
        enable_twopass=not args.no_twopass,
        enable_threepass=not args.no_threepass,
        language=args.language,
        chunk_ms=args.chunk_ms,
    )

    def on_event(ev):
        if args.stream:
            print(json.dumps(ev.to_dict(), ensure_ascii=False), flush=True)

    path = args.input
    if path == "-":
        pcm = sys.stdin.buffer.read()
        result = svc.transcribe_pcm(
            pcm, realtime=args.realtime, on_event=on_event if args.stream else None
        )
    else:
        result = svc.transcribe_file(
            path, on_event=on_event if args.stream else None
        )

    out = result.format(args.format)
    if args.output:
        Path(args.output).write_text(out, encoding="utf-8")
        print(f"wrote {args.output}", file=sys.stderr)
    elif not args.stream or args.format != "ndjson":
        print(out)
    return 0 if result.text else 2


def cmd_serve(args: argparse.Namespace) -> int:
    import os

    from .services.api import create_app

    if args.api_token:
        os.environ["DOUTYPE_API_TOKEN"] = args.api_token
    app = create_app(
        enable_live=not args.no_live,
        enable_nlp=not args.no_nlp,
        api_token=args.api_token or None,
    )
    print(f"DouType API → http://{args.host}:{args.port}/")
    print("  routes: /health /v1/models /v1/audio/transcriptions", end="")
    if not args.no_nlp:
        print(" /v1/nlp/*", end="")
    if not args.no_live:
        print(" /v1/live/*", end="")
    print()
    app.run(host=args.host, port=args.port, debug=False, threaded=True)
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        prog="doutype",
        description="DouType — speech recognition and text intelligence engine",
    )
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("doctor", help="check runtime environment")
    d.set_defaults(func=cmd_doctor)

    k = sub.add_parser("keys", help="manage local product keys")
    k.add_argument(
        "action",
        choices=["status", "refresh", "set-speech", "set-text", "clear"],
    )
    k.add_argument("value", nargs="?", default=None)
    k.set_defaults(func=cmd_keys)

    t = sub.add_parser("transcribe", help="transcribe audio/video or stdin PCM")
    t.add_argument("input", help="file path, or - for raw PCM on stdin")
    t.add_argument(
        "--format",
        default="text",
        choices=["text", "json", "verbose_json", "srt", "vtt", "ndjson"],
    )
    t.add_argument("-o", "--output", default=None)
    t.add_argument("--stream", action="store_true")
    t.add_argument("--realtime", action="store_true")
    t.add_argument("--chunk-ms", type=int, default=30_000)
    t.add_argument("--language", default="zh-CN")
    t.add_argument("--no-twopass", action="store_true")
    t.add_argument("--no-threepass", action="store_true")
    t.set_defaults(func=cmd_transcribe)

    s = sub.add_parser("serve", help="start minimal HTTP engine API")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8080)
    s.add_argument("--api-token", default="", help="optional bearer token")
    s.add_argument("--no-live", action="store_true", help="omit /v1/live/* routes")
    s.add_argument("--no-nlp", action="store_true", help="omit /v1/nlp/* routes")
    s.set_defaults(func=cmd_serve)

    args = p.parse_args(argv)
    return int(args.func(args) or 0)


if __name__ == "__main__":
    raise SystemExit(main())
