# 安装与环境

## 依赖

- Python 3.10+
- 网络访问（语音与文本服务）
- 可选：`ffmpeg`（用于非 PCM 音视频解码）

## 安装

```bash
cd /path/to/doutype
pip install -e .
```

开发依赖（可选）：

```bash
pip install -e ".[dev]"
```

## 验证

```bash
doutype doctor
```

正常时会显示帧参数与会话就绪状态。

## 配置

| 环境变量 | 含义 |
|----------|------|
| `DOUTYPE_KEYSTORE` | 本地密钥库路径 |
| `DOUTYPE_SPEECH_KEY` | 语音角色产品密钥（覆盖密钥库） |
| `DOUTYPE_TEXT_KEY` | 文本角色产品密钥（覆盖密钥库） |
| `DOUTYPE_NO_KEY_SEED` | 设为 `1` 时禁用首次种子写入 |
| `DOUTYPE_API_TOKEN` | HTTP 服务可选访问令牌 |
| `DOUTYPE_CRED_PATH` | 设备身份文件路径（可选） |
| `FFMPEG` | ffmpeg 可执行文件路径 |
| `HOST` / `PORT` | `serve` 默认监听地址 |

首次运行会自动完成设备注册，并将密钥写入本机密钥库。详见 [密钥管理](./keys.md)。

## 音频约定

| 参数 | 值 |
|------|-----|
| 采样率 | 16000 Hz |
| 声道 | 1（单声道） |
| 采样格式 | s16le |
| 帧长 | 20 ms（640 字节） |

向实时接口推送的二进制体应为上述 PCM；文件接口会自动尝试解码。
