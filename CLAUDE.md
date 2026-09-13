# SayType — 项目约定

手机语音输入文字 → 局域网 → Windows 当前光标处。不传音频，只传最终文本。

## 决策（已锁定）

| 项 | 选择 |
|----|------|
| 手机端 | Android 原生 Kotlin + 系统悬浮窗 |
| PC 注入 | 剪贴板 + Ctrl+V 优先；失败降级提示 |
| 传输 | 局域网 WebSocket，默认端口 8787 |
| 配对 | 6 位 PIN，PC 控制台展示，手机填写 |

## 目录结构

```text
SayType/
  README.md
  LICENSE
  package.json
  server/           # PC 本地服务（Node，零原生依赖）
    index.mjs       # 入口：打印 IP/PIN，起 WS
    inject.mjs      # 剪贴板 + Ctrl+V（PowerShell）
  android/          # Android Studio 工程
  scripts/          # 启动 / 托盘 / 开发辅助
  docs/
    PROTOCOL.md
    pitfalls.md
    prototype/
```

## 技术边界

- PC：Node ≥ 20，仅用 `ws` + `qrcode`；注入走 `powershell.exe`（`Clipboard`/`SendKeys`），**不用** robotjs/nut-js 等需编译的原生模块。
- Android：minSdk 26，Kotlin，`OkHttp` WebSocket；权限：`INTERNET`、`SYSTEM_ALERT_WINDOW`、前台 Service。
- 不做：音频采集/ASR、公网穿透、多设备队列、iOS、网页版客户端。

## 验证约定

- PC 必须在本机实测：起服务 → 模拟 WS 客户端发中文 → 焦点窗口出现文字。
- Android 源码保持可编译结构完整；无 Android SDK 的环境不要 claim「已装机验证」。

## 安全红线

- 默认只绑 `0.0.0.0:8787` 供局域网；PIN 校验失败拒绝。
- 不在日志中打印完整用户输入正文（只记长度与成功/失败）。
- 密钥/PIN 不进 git；启动随机生成 PIN。
- 不要添加遥测、上报、自动更新等网络行为。

## 协议

见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。连接后必须先 `hello` 且 PIN 正确，才能 `text`。
