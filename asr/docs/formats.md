# 事件与输出格式

## 流式事件

处理过程中可通过 `on_event` 或 CLI `--stream` 获得：

| type | 含义 |
|------|------|
| `speech.started` | 检测到语音起点 |
| `transcript.partial` | 累计全文快照；`delta` 为相对上次的追加（修订时可能为空） |
| `transcript.done` | 结束；`text` 为最终全文，`duration` 为秒 |

`text` 始终为**从开头累计的全文**，不是碎片。UI 直接覆盖显示即可。

## TranscriptResult

| 字段 | 说明 |
|------|------|
| `text` | 最终文本 |
| `duration` | 秒 |
| `segments` | `(start, end, text)` 列表（分块时按块切分） |
| `events` | 事件列表 |
| `chunks` | 分块原文 |

### format()

| 值 | 输出 |
|----|------|
| `text` | 纯文本 |
| `json` | `{"text":"..."}` |
| `verbose_json` | 含 duration / segments |
| `srt` | SubRip 字幕 |
| `vtt` | WebVTT |
| `ndjson` | 每行一个事件 JSON |

## 帧参数

识别链路按 20 ms 帧推进；时间戳为逻辑音频时间（帧序号 × 20 ms）。  
文件模式默认全速上传；实时模式可按墙钟对齐（`realtime=True`）。
