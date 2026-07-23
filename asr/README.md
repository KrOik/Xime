# DouType

Speech recognition and text intelligence toolkit for Python applications.

## Install

```bash
pip install -e . --no-build-isolation
# optional: ffmpeg on PATH for non-PCM media
```

## Quick start

```python
from doutype import AsrService, NlpService, LiveAsrService, create_app

asr = AsrService()
print(asr.transcribe_file("meeting.wav").text)

nlp = NlpService()
print(nlp.entities("明天飞北京开会"))
print(nlp.proofread("我明天做飞机去北京"))
```

```bash
doutype doctor
doutype keys status
doutype transcribe speech.wav --format srt -o out.srt
doutype serve --host 127.0.0.1 --port 8080
doutype serve --no-live --no-nlp   # transcription only
```

## Services

| Service | Role |
|---------|------|
| `AsrService` | File / PCM transcription, chunking, SRT/VTT/JSON |
| `NlpService` | Entities, hotwords, correction pairs, proofreading |
| `LiveAsrService` | Streaming session API |
| `create_app()` | Minimal HTTP engine (`/health`, `/v1/*`) |
| `KeyManager` | Runtime keystore for product keys |

## Repository layout

```
doutype/            # installable engine package
docs/               # interface documentation
pyproject.toml      # packaging
setup.py
requirements.txt
README.md
CHANGELOG.md
archive/            # historical demos, scripts, samples (not required at runtime)
```

## Documentation

See [docs/](docs/).

## Audio format

- 16 kHz mono s16le PCM  
- Frame: 20 ms (640 bytes)  
- Non-PCM media decoded when `ffmpeg` is available  

## Keys

Product keys are stored in a local keystore at runtime (not in application code).

```bash
doutype keys set-speech <key>
doutype keys set-text <key>
```

Details: [docs/keys.md](docs/keys.md).
