# CLI

入口：`doutype`（安装后可用）。

## doctor

```bash
doutype doctor
```

## keys

```bash
doutype keys status
doutype keys refresh
doutype keys set-speech <key>
doutype keys set-text <key>
doutype keys clear
```

详见 [keys.md](./keys.md)。

## transcribe

```bash
doutype transcribe <file|-> [options]
```

| 选项 | 说明 |
|------|------|
| `--format text\|json\|verbose_json\|srt\|vtt\|ndjson` | 输出格式 |
| `-o / --output` | 写入文件 |
| `--stream` | 运行中打印事件行 |
| `--realtime` | stdin PCM 按实时节奏 |
| `--chunk-ms` | 长音频分块（默认 30000） |
| `--language` | 默认 `zh-CN` |
| `--no-twopass` / `--no-threepass` | 关闭多路校正 |

```bash
doutype transcribe meeting.mp3 --format srt -o meeting.srt
ffmpeg -i in.wav -ac 1 -ar 16000 -f s16le - | doutype transcribe - --format text
```

## serve

启动**最小引擎 HTTP API**（无 Web UI）。

```bash
doutype serve --host 127.0.0.1 --port 8080
doutype serve --api-token secret
doutype serve --no-live --no-nlp   # 仅文件转写
```

| 选项 | 说明 |
|------|------|
| `--host` / `--port` | 监听地址 |
| `--api-token` | 可选 Bearer |
| `--no-live` | 不注册 `/v1/live/*` |
| `--no-nlp` | 不注册 `/v1/nlp/*` |

详见 [http-api.md](./http-api.md)。

## Web UI（测试，非 CLI）

浏览器冒烟在示例目录，不进入默认引擎命令：

```bash
python archive/examples_bundle/webui_test/run_webui.py --port 8765
```

见 [archive/examples_bundle/webui_test/README.md](../archive/examples_bundle/webui_test/README.md)。
