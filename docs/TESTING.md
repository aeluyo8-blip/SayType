# 测试环境交接文档（给 AI / 新开发者）

> 目标读者：接手本项目做构建和测试的 AI 或开发者。读完本文档应能在不问任何问题的情况下完成：构建 APK → 装机 → 启动 PC 服务 → 端到端验证。
> 配套文档：`docs/KNOWN-ISSUES.md`（待办任务清单）、`docs/pitfalls.md`（厂商/系统坑）、`CLAUDE.md`（项目安全约束，必读）、根目录 `README.md`（用户视角使用说明）。

## 项目是什么

手机当电脑的"打字遥控器"：手机端是 Kotlin APP（悬浮球 + 输入面板），通过 WebSocket 把文字发到电脑端 Node 服务，电脑端写入剪贴板后合成 Ctrl+V 粘贴到当前焦点窗口。

```
手机 APP（悬浮球/面板） ──WebSocket ws://PC局域网IP:8787──▶ Node 服务 ──▶ PowerShell 剪贴板 + Ctrl+V ──▶ 焦点窗口
```

- 协议：JSON 行。客户端发 `hello{text, port, pin}` / `text{seq, text}` / `ping`；服务端回 `welcome` / `ack{seq, ok, detail}` / `pong` / `error`。PIN 认证，错误码 `bad_pin`。
- 安全约束（来自 CLAUDE.md，改动时必须遵守）：PIN 每次启动随机生成、不提交进 git、不硬编码；**不记录用户输入的完整内容，只记长度和成败**；服务只绑 0.0.0.0:8787 局域网使用。

## 环境现状（这台电脑已全部配好，不要重装）

| 组件 | 路径 / 版本 | 备注 |
|------|------|------|
| JDK 17 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` | 需要 `JAVA_HOME` 指向它 |
| Android SDK | `C:\Users\14737\AppData\Local\Android\Sdk` | `android/local.properties` 已写好 `sdk.dir` |
| Gradle | 项目自带 wrapper 8.9 | 已改腾讯镜像，国内下载无障碍 |
| adb | `C:\Users\14737\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v4.1\adb.exe` | 来自 scrcpy 包，建议加入 PATH 或用全路径 |
| Node.js | ≥ 20 | 跑 PC 服务用 |
| 测试真机 | 三星 Tab S6，serial `192.168.28.188:5555`（adb over WiFi，同局域网） | 可能未连接，先 `adb devices` 看 |

## 构建 APK

Git Bash：

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
cd /d/Workspace/phone-type/android
./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

cmd 里则用 `gradlew.bat assembleDebug`。发布包用 `assembleRelease`（当前无签名配置，release 也能出 debug 签名包，根目录 APK 就是这么出的）。

## 安装到真机

```bash
adb connect 192.168.28.188:5555        # WiFi 设备先连
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.phonetype.app SYSTEM_ALERT_WINDOW allow   # 免手动授予悬浮窗权限
adb shell am start -n com.phonetype.app/.MainActivity
```

模拟全新安装（验证首启状态、扫码引导等）：`adb shell pm clear com.phonetype.app`。

USB 调试时可用 `adb reverse tcp:8787 tcp:8787`，让手机填 `127.0.0.1:8787` 就能连本机服务，绕过局域网/防火墙问题。

## 启动 PC 服务

**推荐：双击 `启动服务.bat`（托盘模式）**
- 服务在后台隐藏运行，任务栏/系统托盘有蓝色 **S** 图标
- 双击图标或右键「显示 PIN / 地址」可再看 PIN
- 右键「重启服务」「停止服务并退出」
- 端口 8787 被占用时会弹窗 Y/N 询问是否结束旧进程
- 状态文件：`runtime/status.json`（pid/pin/addrs）；日志：`runtime/server.log`

也可用 `显示PIN.bat` / `停止服务.bat`。开发时仍可前台跑：

```bash
cd /d/Workspace/phone-type
npm install            # 仅首次
node server/index.mjs
```

- 启动横幅会打印端口、6 位 PIN、局域网地址列表和配置二维码（内容 `phonetype://IP:8787?pin=xxxxxx`）。
- 想固定 PIN 方便自动化测试：`PHONE_TYPE_PIN=246810 node server/index.mjs`（仅测试用，别把 PIN 提交进 git）。
- 可调环境变量：`PHONE_TYPE_HEARTBEAT_MS`（默认 30000，心跳间隔）、`PHONE_TYPE_PS_TIMEOUT`（PowerShell 注入超时，默认 5000）。
- 端口被占用会打印友好提示退出（这是现状，改进项见 KNOWN-ISSUES #3）。

## 自动化测试

```bash
node --test test/inject.unit.mjs     # 单测：randomPin 6 位、listLanIPv4
```

协议层可用任意 WebSocket 客户端测：连上后发 `{"type":"hello","pin":"<PIN>","port":8787}` 收 `welcome`；发 `{"type":"text","seq":1,"text":"hi"}` 收 `ack`；心跳：把 `PHONE_TYPE_HEARTBEAT_MS` 调小，造一个连上不回 pong 的"死客户端"，服务端应在一到两个周期内主动断开它，活客户端不受影响。**测试文本用英文或无意义短句，不要往电脑剪贴板/聊天窗口里注入真实隐私内容。**

## UI 自动化（zcode 环境）

- 本机 zcode 装有 `android-dev` skill：`C:\Users\14737\.zcode\cli\plugins\cache\zcode-plugins-official\android-emulator\0.1.0\skills\android-dev\SKILL.md`。zcode 会话里输入 `/android-dev` 即可调起，它提供 `android_build_and_run`、`android_screenshot`、`android_ui_describe`、`android_ui_tap`、`android_ui_swipe`、`android_ui_type_text`、`android_logs` 等 MCP 工具。
- **非 zcode 的 AI**：没有这些 MCP 工具，但可以直接读上面那个 SKILL.md 当手册，用等价的 adb 命令完成同样的事：
  - 布局树：`adb shell uiautomator dump && adb shell cat /sdcard/window_dump.xml`
  - 点击/滑动/返回：`adb shell input tap x y` / `input swipe x1 y1 x2 y2 ms` / `input keyevent BACK|WAKEUP`
  - 文本（**只能英文**，`input text` 输中文会崩）：`adb shell input text hello`
  - 截图：`adb exec-out screencap -p > shot.png`

## 已知坑（踩过的，别再踩）

见 `docs/pitfalls.md`（厂商坑）+ 以下自动化测试专用坑：

- Git Bash 里 adb 参数带设备路径要加 `export MSYS_NO_PATHCONV=1`，否则 `/sdcard/...` 会被转成 Windows 路径。
- Git Bash 里杀进程用双斜杠：`taskkill //PID 1234 //F`。
- 设备息屏时截图全黑：先 `adb shell input keyevent KEYCODE_WAKEUP`，必要时上滑解锁。
- bat 脚本里 chcp 65001 下长中文行会解析错乱（已因此把逻辑拆进 `scripts/start-service.ps1`，UTF-8 BOM，改 PS1 时别丢 BOM）。
- Service 里 inflate Material 组件必须套 `ContextThemeWrapper`（FloatingBubbleService 已处理）。
- `test/` 下的 verify 类脚本在 Win11 商店版记事本上有误报（PowerShell 拉起 notepad 会阻塞），验证注入结果建议人工看屏幕或用无窗口控件。

## 每次发版手动验收清单

1. `./gradlew assembleDebug` 通过，无新 lint 错误。
2. 真机安装启动，主界面正常（亮色/暗色都看一眼）。
3. 扫码配置流程：PC 端启动出二维码 → APP「扫码配置」→ 三栏自动填好 → 连接成功。
4. 悬浮球：拖动、贴边收缩、点击展开面板；面板里发英文、发中文，电脑焦点窗口粘贴正确。
5. 断网/杀服务端后 APP 自动重连（改回退间隔上限 30s）。
6. 主界面三个输入框空/聚焦/已填三种状态下提示文字无重叠（历史 bug，回归验证）。
7. 改了功能就 bump `android/app/build.gradle.kts` 的 versionCode/versionName，并更新根目录 `phone-type-v*.apk`（删旧包）。
