# ASR 转写服务接入说明

## 架构

Xime 不在 APK 内运行 Python，也不直接保存上游语音服务的产品密钥。推荐部署关系：

```text
Xime（Android，PCM） → HTTPS /v1/live/sessions → ASR 服务 → 上游语音识别
```

Android 端只需要服务基础 URL，以及服务开启鉴权时使用的 Bearer token。服务端负责上游协议、凭据刷新、限流与审计。

## 服务端

在 ASR 项目环境中启动仅包含转写能力的服务：

```bash
doutype serve --host 127.0.0.1 --port 8080 --no-nlp --api-token <随机长令牌>
```

不要把 `127.0.0.1:8080` 直接填写到手机中：手机的 localhost 指向手机自身。生产或跨设备使用时，应在服务前配置 HTTPS 反向代理，并只暴露必要路由。

服务需要提供：

- `POST /v1/live/sessions`
- `POST /v1/live/sessions/{id}/audio`
- `GET /v1/live/sessions/{id}`
- `DELETE /v1/live/sessions/{id}`

音频格式固定为 16 kHz、单声道、PCM 16-bit little-endian。

## Android 端安全约束

- 服务 URL 默认必须是 HTTPS，且不得携带 query 或 fragment。
- Bearer token 通过 Android Keystore 的 AES-GCM 密钥加密后保存。
- token 密文偏好不参与云备份和设备迁移；换机后需要重新配置。
- URL、token、完整转写文本和音频不得写入日志。
- 轮询事件只用于 composing 预览；只有结束接口的结算文本会作为 final 提交。

## 当前实现状态

- 已实现供应商无关的流式会话状态机。
- 已实现有界音频队列、顺序推送、partial 轮询、停止结算和取消丢弃。
- 已实现 HTTP 客户端和 Keystore token 存储。
- 设置页面尚待接入；在 UI 完成前，功能不视为用户可用。
- 仍需使用真实部署服务完成 Android 真机端到端验收。

## 验收记录要求

真机验收至少记录：应用版本、设备与 Android 版本、服务版本、网络环境、服务 URL 是否为 HTTPS，以及以下场景结果：

1. 正常说话，partial 持续覆盖 composing，松手后只提交一次 final。
2. 握手完成前快速松手，仍能正确结算。
3. 取消、无声、服务端 401/429/500、断网和超时均不提交脏文本。
4. 连续两次识别不串会话。
5. 切换应用或输入法后不保留 composing 文本和后台录音。
