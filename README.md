# SayType

手机上用语音输入法把话打成字，一点发送，字就出现在电脑光标处。

只传文字，不传音频。手机和电脑在**同一个 WiFi** 下即可，延迟通常几十毫秒。

```text
手机语音/键盘输入 → APP → 局域网 → PC 服务 → 剪贴板+Ctrl+V → 当前光标处
```

**SayType** — Speak here. Type there.

## 功能

- Android 悬浮球 + 输入面板，任意 App 上层唤起
- 扫码配对：IP / 端口 / PIN 一键填入
- 局域网 WebSocket，PIN 鉴权，日志不记正文
- Windows 端零原生依赖（Node ≥ 20 + `ws`）
- 发送历史 / 笔记本地保存

## 上手三步

> PC 端目前仅支持 **Windows**（注入用的是 Windows 剪贴板 + 模拟按键）。

### 第 1 步：手机装 APP

从 [GitHub Releases](../../releases) 下载 APK 装到手机  
（或用 Android Studio 打开 `android/` 自行编译）。  
安装时如提示「未知来源应用」，允许即可。

### 第 2 步：电脑双击「启动服务.bat」

双击项目文件夹里的 **`启动服务.bat`**，它会自动：

1. 检测 Node.js（没装会帮你打开下载页面，装好后重新双击）
2. 首次运行自动安装依赖
3. 首次运行弹窗申请管理员权限，自动放行防火墙
4. 启动服务，并在窗口里显示 PIN、IP，**还会打印一个大二维码**

```text
  port : 8787
  PIN  : 382614        ← 手动填写时抄这个
  LAN  :
         ws://192.168.1.100:8787
  ┌─────────────────┐
  │  ▄▄▄▄▄▄▄ ▄ ▄▄   │  ← 手机扫这个码
  │  █ ▄▄▄ █ ...    │
  └─────────────────┘
```

**PIN 是每次启动随机生成的。** 窗口别关，关了服务就停了。

### 第 3 步：手机扫码连接（推荐）

1. 手机连上和电脑**同一个 WiFi**，打开 SayType APP
2. 首次点「悬浮窗权限」按钮，去系统设置授权「显示在其他应用上层」
3. 点连接配置卡片右上角的 **「扫码配置」**，对准电脑窗口里的二维码扫一下  
   —— IP、端口、PIN 三个格子全部自动填好，零输入
4. 依次点：**保存配置 → 启动悬浮球 → 连接 PC**，显示「已连接」即可
5. 电脑上随便打开一个能打字的地方（记事本/微信输入框），点手机上的悬浮球，  
   用语音输入法说话，点「发送到电脑」——文字直接出现在光标处

> 扫不上码（比如屏幕反光、多网卡电脑）？手动填也一样：  
> IP 照抄窗口 LAN 列表、端口 `8787`、PIN 照抄窗口。

## 常见问题

| 问题 | 原因与解决 |
|------|-----------|
| 手机显示连接失败 / 一直转圈 | 检查：手机和电脑是否同一 WiFi；服务窗口是否开着；防火墙是否放行过（重新双击 bat 会自动检测放行） |
| 提示 PIN 错误 | 服务每次重启 PIN 都会变，看一眼服务窗口重新填 |
| 电脑 IP 换了 / 连不上 | 服务窗口 LAN 列表里显示什么就填什么（连了新网络后 IP 会变） |
| 之前连得好好的，突然断了 | 服务重启后 1 分钟内 APP 会自动重连，不用管；超过 1 分钟就重新点「连接 PC」 |
| 全屏游戏 / UAC 弹窗 / 远程桌面里没反应 | 已知限制：这类窗口会拒绝粘贴注入 |
| 电脑上看不到文字 | 注入是粘贴到「当前焦点窗口」，确保电脑上光标停在输入框里 |

更多厂商/系统坑见 [docs/pitfalls.md](docs/pitfalls.md)。

## 安全说明

- 服务只监听局域网，**不会暴露到公网**
- 手机连接必须先提交 PIN，PIN 每次启动随机生成
- 传输内容只有文字，不采集音频，日志不记录正文

## 工作原理

```text
手机 IME → 悬浮窗 APP → WebSocket(8787) → PC Node 服务 → 剪贴板 + Ctrl+V → 焦点窗口
```

协议细节见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。  
PC 端零原生依赖，只用 Node 20+ 和 `ws`。

## 项目结构

```text
SayType/
  server/                 # Windows 本地服务（Node）
  android/                # Android Studio 工程
  scripts/                # 启动 / 托盘 / 开发辅助
  docs/                   # 协议、坑位、UI 原型
  启动服务.bat             # 小白入口（托盘模式）
  停止服务.bat / 显示PIN.bat
```

## 给开发者

```powershell
# PC 端
npm install
npm start                 # 前台跑服务
npm test                  # 单元测试
npm run verify            # 端到端验证（自动开记事本验证中文注入）

# 命令行模拟手机发文字
node scripts/dev-client.mjs 127.0.0.1 8787 <PIN> "测试"

# Android 端
# 用 Android Studio 打开 android/，或：
cd android
gradlew assembleDebug
```

| 路径 | 作用 |
|------|------|
| `启动服务.bat` + `scripts/start-service.ps1` | 检测 Node / 装依赖 / 放行防火墙 / 启动服务 |
| `scripts/saytype-tray.ps1` + `scripts/start-tray-hidden.vbs` | 系统托盘常驻（无黑窗） |
| `scripts/dev-client.mjs` | CLI 模拟手机客户端 |
| `scripts/verify-pc.mjs` | 端到端验证 |
| `docs/prototype/index.html` | UI 原型页（浏览器打开即可预览） |

开发约定见 [CLAUDE.md](CLAUDE.md)。

## License

[MIT](LICENSE)
