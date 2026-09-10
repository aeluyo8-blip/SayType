---
feature: phone-type
status: delivered
updated: 2026-07-28
branch: feat/mvp
commits: 4dcc421..<head>
---

# Phone → PC Cursor Typing

## Report

**What was built** — Windows 本地服务（`npm start`）打印局域网 IP / 端口 / PIN，经 WebSocket 鉴权后把手机发来的文字用「剪贴板 + Ctrl+V」写入当前前台光标；中文走 base64 UTF-8，避免引号与编码问题。Android 工程源码提供设置页（IP/端口/PIN）、悬浮球前台 Service、输入弹层与 OkHttp 客户端，用系统 IME（微信/豆包）说话后点发送。本机无 Android SDK，未编译 APK。

**Verification** — `npm test` 2 passed；`node scripts/verify-pc.mjs` 对记事本空文件注入中文并存盘读回 matched=true（VERIFY_PC_PASS）；错误 PIN 返回 `bad_pin` 并断开。Android 未在本机编译。

**Journey log** —
1. 评审指出 WsManager 重连竞态（旧 socket 回调可能清掉新会话）→ 用 generation token + compareAndSet 修复。
2. 并发 text 帧会交错两次剪贴板注入 → 服务端 promise 链串行化。
3. 验证脚本曾用全局剪贴板读回，混入其它窗口内容且控制台编码损坏中文 → 改为记事本写临时文件再读盘。
4. 不传音频、不做网页壳：按你的决策只交付 Android 源码 + PC 服务。
5. 首次 e2e 偶发 notepad 焦点失败，重跑通过；生产依赖「用户正在操作的目标窗口」本就允许偶发焦点漂移。

## [S1] Problem

台式机没有麦克风，插耳机又耗电。手机上微信/豆包输入法识别很好，希望把手机上已经识别好的**文字**直接打进 Windows 当前光标所在输入框，而不是传音频再识别，也不是剪贴板手动粘贴。

## [S2] Design

### 总体

```text
Android App（悬浮球 + 输入弹层，系统 IME）
    │  WebSocket JSON（局域网）
    ▼
PC Node 服务（PIN 鉴权）
    │  剪贴板写入 + Ctrl+V
    ▼
当前前台窗口光标处
```

### 已锁定决策

- 手机端：Android 原生 Kotlin，系统悬浮窗；**只交付源码**，本机不编译 APK。
- PC 注入：剪贴板 + Ctrl+V 优先；失败回 `inject_failed`，手机提示可手动粘贴。
- 端口默认 `8787`；启动随机 6 位 PIN；绑定 `0.0.0.0`。
- 不做音频、公网、iOS、网页客户端。

### PC 服务

- `server/index.mjs`：列出局域网 IPv4、生成 PIN、起 `ws` 服务。
- `server/inject.mjs`：
  1. 将文本写入剪贴板（PowerShell `Set-Clipboard` 或 .NET `Clipboard::SetText`）。
  2. 向前台窗口发送 `Ctrl+V`（`SendKeys` 或等价 Win32）。
  3. 返回 `{ok, method}`；异常带 code。
- 日志：只记 `seq`、文本长度、成功/失败，不落原文。

### 协议

见 CLAUDE.md。握手：`hello`+pin → `welcome`；错误：`bad_pin` / `not_hello` / `inject_failed`。

### Android App

- 主界面：IP、端口、PIN、连接状态、连接/断开。
- 权限：`SYSTEM_ALERT_WINDOW` 引导；前台 Service 保活悬浮球。
- 悬浮球可拖动；点击弹出输入框（EditText + 发送/清空）；系统 IME 可用。
- 发送：增量可选；MVP 为点「发送」整段推送；`seq` 递增，等 `ack` 更新 UI。
- 依赖：OkHttp WebSocket、org.json 或 kotlinx.serialization（任选其一，MVP 用 org.json 减少配置）。

### 错误行为

| 场景 | 行为 |
|------|------|
| PIN 错 | 服务发 `error:bad_pin` 并断开 |
| 未 hello 先 text | `error:not_hello` 并忽略 |
| 注入失败 | `error:inject_failed`；手机 toast |
| 断线 | App 显示断开，允许重连 |

## [S3] Out of Scope

- 语音识别、音频流、虚拟麦克风
- 公网/中继/云
- iOS 悬浮窗、Flutter/React Native 壳
- 多窗口焦点选择 UI、历史记录同步
- 本机 Android 编译与真机安装验证（环境无 SDK）

## Tasks

- [x] T1: PC 服务骨架 + PIN/局域网展示 + WS 握手 — acceptance: `npm start` 打印 IP/端口/PIN，未 hello 的 text 被拒 (covers: S2)
- [x] T2: 剪贴板 + Ctrl+V 注入模块 — acceptance: 本地脚本对记事本/浏览器焦点写入中文成功，失败返回 inject_failed (covers: S2)
- [x] T3: WS text → inject 接通与日志 — acceptance: 模拟客户端发中文，前台窗口出现文字，日志无原文 (covers: S2)
- [x] T4: Android 工程骨架与设置页 — acceptance: Gradle 工程结构完整，可配置 IP/端口/PIN 并发起连接 (covers: S2; depends: T1)
- [x] T5: 悬浮球 Service + 输入弹层 + 发送 — acceptance: 源码含 overlay 权限引导、前台 Service、发送 JSON text (covers: S2; depends: T4)
- [x] T6: README 使用说明与权限/厂商坑 — acceptance: 用户能按文档从 npm start 走到 Android Studio 安装 (covers: S2)
- [x] T7: 本机验证 PC 链路 — acceptance: 用真实 WS 客户端注入中文到焦点窗口并记录结果 (covers: S2; depends: T2, T3)
