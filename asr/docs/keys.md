# 密钥管理

语音与文本能力使用**产品密钥（product key）**换取会话令牌。密钥在**运行时**写入本机密钥库，不在应用代码中写死。

## 密钥库位置

默认：

| 平台 | 路径 |
|------|------|
| Windows | `%LOCALAPPDATA%\doutype\keystore.json` |
| 其他 | `~/.config/doutype/keystore.json` |

可用环境变量覆盖：

```bash
export DOUTYPE_KEYSTORE=/path/to/keystore.json
```

设备身份另存于 `credentials.json`（同目录）。

## 角色

| 角色 | 用途 | 环境变量 |
|------|------|----------|
| `speech` | 语音识别会话 | `DOUTYPE_SPEECH_KEY` |
| `text` | 文本智能（实体/热词/校对等） | `DOUTYPE_TEXT_KEY` |

## CLI

```bash
# 查看（仅显示来源与预览，不回显完整密钥）
doutype keys status

# 从运行时配置刷新设备材料
doutype keys refresh

# 手动写入
doutype keys set-speech <your-speech-key>
doutype keys set-text <your-text-key>

# 清空密钥（保留设备身份）
doutype keys clear
```

`doutype doctor` 会显示密钥是否存在及其来源。

## Python

```python
from doutype import get_key_manager, AsrService, NlpService

km = get_key_manager()
km.ensure()                 # 确保设备与密钥就绪
km.set_key("speech", "...") # 手动设置
km.set_key("text", "...")
print(km.status())

# 业务侧无需关心密钥：服务会自动读取密钥库
asr = AsrService()
nlp = NlpService()
```

## 解析顺序

对每个角色：

1. 环境变量（`DOUTYPE_SPEECH_KEY` / `DOUTYPE_TEXT_KEY`）
2. 密钥库已有条目
3. 首次运行：若仍为空，则**一次性**写入种子值到密钥库（之后只读密钥库）
4. 可通过 `DOUTYPE_NO_KEY_SEED=1` 禁用种子

## 安全建议

- 不要把 `keystore.json` 提交到版本库
- 生产环境用 `set-speech` / `set-text` 或环境变量注入密钥
- 定期 `keys refresh` 更新设备侧材料
