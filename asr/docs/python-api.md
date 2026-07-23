# Python API

```python
from doutype import AsrService, NlpService, LiveAsrService, create_app
```

## AsrService

文件与 PCM 转写。

```python
from doutype import AsrService

asr = AsrService(
    language="zh-CN",
    enable_twopass=True,
    enable_threepass=True,
    enable_punctuation=True,
    chunk_ms=30_000,   # 长音频分块，毫秒
)

# 文件（wav/mp3/mp4 等，需 ffmpeg）
r = asr.transcribe_file("meeting.wav")
print(r.text)
print(r.duration)
print(r.format("srt"))
print(r.format("json"))

# 原始 PCM
with open("audio.pcm", "rb") as f:
    r = asr.transcribe_pcm(f.read())

# 事件回调
def on_event(ev):
    print(ev.type, getattr(ev, "text", "")[:40])

r = asr.transcribe_file("a.wav", on_event=on_event)
```

### Transcript 选项（kwargs）

| 参数 | 说明 |
|------|------|
| `enable_twopass` / `enable_threepass` | 多路校正 |
| `enable_punctuation` | 自动标点 |
| `language` | 如 `zh-CN`、`en-US` |
| `enable_print_chinese` | 数字倾向中文数字 |
| `result_type` | `full` / `single` |
| `chunk_ms` | 长音频分块长度 |

## NlpService

```python
from doutype import NlpService

nlp = NlpService()

# 实体
print(nlp.entities("明天飞北京见张总"))

# 热词
nlp.set_hotwords(["产品名", "项目代号"])

# 纠正对
nlp.add_correction("马云", "马化腾", text="见马云", position=1)

# 校对
print(nlp.proofread("我明天做飞机去北京开会。"))
```

## LiveAsrService

推送式实时会话（麦克风或外部采集）。

```python
from doutype import LiveAsrService

live = LiveAsrService()
sid = live.start(language="zh-CN", enable_punctuation=True)

# 循环推送 16k mono s16le 分片
live.push_audio(sid, pcm_bytes)

# 轮询 partial
print(live.poll(sid)["text"])

# 结束并取最终结果（会等待多路校正）
print(live.stop(sid)["text"])
```

## 共享 Client

```python
from doutype import Client, AsrService, NlpService

c = Client()
asr = AsrService(client=c)
nlp = NlpService(client=c)
```

## 创建 HTTP 应用

```python
from doutype import create_app
app = create_app(api_token="optional-secret")
# 交给 gunicorn / waitress，或 app.run(...)
```
