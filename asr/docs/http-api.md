# HTTP API（引擎）

启动：

```bash
doutype serve --host 127.0.0.1 --port 8080
# 可选鉴权
doutype serve --api-token my-secret
# 收缩暴露面
doutype serve --no-live          # 不注册实时会话路由
doutype serve --no-nlp           # 不注册文本智能路由
doutype serve --no-live --no-nlp # 仅文件转写
```

若设置了令牌，请求需带：

```http
Authorization: Bearer my-secret
```

## 默认路由一览

| 方法 | 路径 | 说明 | 可用开关关闭 |
|------|------|------|----------------|
| GET | `/health` | 健康检查 | 否 |
| GET | `/v1/models` | 模型列表 | 否 |
| POST | `/v1/audio/transcriptions` | 文件转写 | 否 |
| POST | `/v1/nlp/entities` | 实体 | `--no-nlp` |
| POST | `/v1/nlp/hotwords` | 热词 | `--no-nlp` |
| POST | `/v1/nlp/correction` | 纠正对 | `--no-nlp` |
| POST | `/v1/nlp/proofread` | 校对 | `--no-nlp` |
| POST | `/v1/live/sessions` | 创建实时会话 | `--no-live` |
| POST | `/v1/live/sessions/{id}/audio` | 推送 PCM | `--no-live` |
| GET | `/v1/live/sessions/{id}` | 轮询 partial | `--no-live` |
| DELETE | `/v1/live/sessions/{id}` | 结束会话 | `--no-live` |

**不包含**：静态页面、`/api/*` 别名、WebSocket 控制台。浏览器测试页见 `archive/examples_bundle/webui_test/`。

## 语音转写

### `POST /v1/audio/transcriptions`

`multipart/form-data`：

| 字段 | 说明 |
|------|------|
| `file` | 音频 / 视频文件 |
| `response_format` | `json`（默认）/`text`/`srt`/`vtt`/`verbose_json` |

```bash
curl -F file=@speech.wav -F response_format=json \
  http://127.0.0.1:8080/v1/audio/transcriptions
```

```json
{"text": "识别结果"}
```

## 文本智能

### `POST /v1/nlp/entities`

```json
{"text": "明天飞北京"}
```

### `POST /v1/nlp/hotwords`

```json
{"words": ["热词A", "热词B"]}
```

### `POST /v1/nlp/correction`

```json
{"source": "错词", "target": "正词", "text": "上下文", "position": 0}
```

### `POST /v1/nlp/proofread`

```json
{"text": "待校对句子"}
```

## 实时会话

```bash
SID=$(curl -s -X POST http://127.0.0.1:8080/v1/live/sessions \
  -H "Content-Type: application/json" -d "{}" | python -c "import sys,json;print(json.load(sys.stdin)['session_id'])")
curl -s -X POST http://127.0.0.1:8080/v1/live/sessions/$SID/audio --data-binary @chunk.pcm
curl -s http://127.0.0.1:8080/v1/live/sessions/$SID
curl -s -X DELETE http://127.0.0.1:8080/v1/live/sessions/$SID
```

PCM：16 kHz 单声道 s16le。

## 错误格式

```json
{"error": {"message": "...", "type": "...", "code": "..."}}
```

## Python 装配

```python
from doutype import create_app
app = create_app(enable_live=False, enable_nlp=True, api_token="secret")
```
