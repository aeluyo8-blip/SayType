# phone-type — 项目约定

手机语音输入文字 → 局域网 → Windows 当前光标处。不传音频，只传最终文本。

## 决策（已锁定）

| 项 | 选择 |
|----|------|
| 手机端 | Android 原生 Kotlin + 系统悬浮窗（只要源码，本机不编译 APK） |
| PC 注入 | 剪贴板 + Ctrl+V 优先；失败降级提示 |
| 传输 | 局域网 WebSocket，默认端口 8787 |
| 配对 | 6 位 PIN，PC 控制台展示，手机填写 |
| 新工程位置 | `D:\Workspace\phone-type`（与校招项目隔离） |

## 目录结构

```text
phone-type/
  README.md
  CLAUDE.md
  package.json
  server/           # PC 本地服务（Node，零原生依赖）
    index.mjs       # 入口：打印 IP/PIN，起 WS
    inject.mjs      # 剪贴板 + Ctrl+V（PowerShell/ctypes）
  android/          # Android Studio 工程（用户本机编译）
    app/src/main/...
  docs/compose/spec/phone-type.md
```

## 技术边界

- PC：Node ≥ 20，仅用 `ws`；注入走 `powershell.exe`（`Clipboard`/`SendKeys`），**不用** robotjs/nut-js 等需编译的原生模块。
- Android：minSdk 26，Kotlin，`OkHttp` WebSocket；权限：`INTERNET`、`SYSTEM_ALERT_WINDOW`、前台 Service。
- 不做：音频采集/ASR、公网穿透、多设备队列、iOS、网页版客户端。

## 验证约定

- PC 必须在本机实测：起服务 → 模拟 WS 客户端发中文 → 焦点窗口出现文字。
- Android 源码保持可编译结构完整；本环境无 Android SDK，不在本机 claim「已装机验证」。

## 安全红线

- 默认只绑 `0.0.0.0:8787` 供局域网；PIN 校验失败拒绝。
- 不在日志中打印完整用户输入正文（只记长度与成功/失败）。
- 密钥/PIN 不进 git；启动随机生成 PIN。

## 协议（WebSocket JSON）

```json
// 手机 → PC
{"type":"hello","pin":"123456"}
{"type":"text","text":"要上屏的内容","seq":1}
{"type":"ping"}

// PC → 手机
{"type":"welcome","server":"phone-type","ok":true}
{"type":"error","code":"bad_pin"|"bad_json"|"not_hello"|"empty_text"|"too_long"|"inject_failed"|"unknown_type"}
{"type":"ack","seq":1,"ok":true,"method":"clipboard"}
{"type":"pong"}
```

连接后必须先 `hello` 且 PIN 正确，才能 `text`。
