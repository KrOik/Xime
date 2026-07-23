# Xime 流式 ASR 集成契约

本文档是所有在线 ASR 后端必须遵守的最小运行时契约。供应商协议可以不同，但不得绕过这些语义直接操作输入法界面。

## 音频

- 输入为 16 kHz、单声道、16-bit little-endian PCM。
- 会话确认就绪前不得直接丢弃或无限缓存音频；传输层应使用有界缓冲。
- 用户停止录音表示“发送收尾并等待 final”，不等于取消或立即关闭连接。

## 事件

- `SessionReady`：鉴权及服务端会话初始化已经完成，可以发送音频。
- `Partial(text)`：可覆盖当前 composing 文本，不得提交文字或退出语音模式。
- `Final(text)`：一次会话最多向上层分发一次；先结束 composing，再提交最终文本。
- `Failure(message)`：终止当前会话、清理 composing，并向用户显示脱敏错误。
- `RemoteClosed`：若此前没有收到 final 或主动取消，应视为异常结束。

## 生命周期

```text
IDLE/终态 → CONNECTING → LISTENING → FINISHING → COMPLETED
                       ↘ CANCELLED
                       ↘ FAILED
```

- `finish` 仅把活动会话置为 `FINISHING`，继续接受 partial，直到 final 或超时。
- 用户可能在握手完成前调用 `finish`；随后到达的 `SessionReady` 仍须被接受，传输层应立即发送结束帧，状态保持 `FINISHING`。
- `cancel` 进入 `CANCELLED`，之后的 partial、final 和错误回调全部丢弃。
- final 后的重复 final 或迟到 partial 全部丢弃。
- 连接关闭但没有 final 时不得把最后一次 partial 冒充最终结果。
- 完成、取消或失败后允许创建下一次会话，但旧连接回调必须通过会话标识或独立守卫隔离。

## 自动化验收

`StreamingAsrSessionTest` 是协议生命周期的第一层门禁。每个供应商后端还必须用固定协议夹具覆盖：

- 服务端就绪消息；
- partial 与 final 的真实消息样本；
- 重复、乱序和迟到消息；
- 用户收尾、取消、超时及无 final 断线；
- 相邻两次会话之间不串流。
