# DouType 文档

面向应用集成的功能说明。通过 **Python 包 / CLI / HTTP** 调用即可，无需修改包内源码。

## 目录

1. [安装与环境](./install.md)
2. [密钥管理](./keys.md)
3. [Python API](./python-api.md)
4. [HTTP API](./http-api.md)
5. [CLI](./cli.md)
6. [事件与输出格式](./formats.md)
7. [Web UI 测试页](./ui.md)（非引擎默认）

## 能力一览

| 能力 | 入口 |
|------|------|
| 文件 / 音视频转写 | `AsrService.transcribe_file` · `POST /v1/audio/transcriptions` |
| 原始 PCM 转写 | `AsrService.transcribe_pcm` |
| 实时流式会话 | `LiveAsrService` · `/v1/live/sessions/*` |
| 命名实体 | `NlpService.entities` · `POST /v1/nlp/entities` |
| 热词 | `NlpService.set_hotwords` · `POST /v1/nlp/hotwords` |
| 纠正对 | `NlpService.add_correction` · `POST /v1/nlp/correction` |
| 文本校对 | `NlpService.proofread` · `POST /v1/nlp/proofread` |
| 字幕导出 | `TranscriptResult.format("srt"\|"vtt")` |

## 设计原则

- **服务化封装**：业务侧只依赖 `doutype` 公开接口。
- **多形态接入**：库调用、命令行、HTTP 任选。
- **可替换实现**：会话与 NLP 均通过服务类传入，便于测试与扩展。
