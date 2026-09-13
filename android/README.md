# Android 端（SayType / SayType）

Kotlin 原生 App：悬浮球 + 输入面板，通过局域网 WebSocket 把文字发到 Windows 电脑光标处。

## 构建

用 Android Studio 打开本目录，或命令行：

```bash
# 需要 JDK 17 + Android SDK
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

当前 `versionName` 见 `app/build.gradle.kts`。

## 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | 局域网 WebSocket |
| `SYSTEM_ALERT_WINDOW` | 悬浮球 / 输入面板 |
| `FOREGROUND_SERVICE` | 保持悬浮球存活 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |

## 说明

- `local.properties` 的 `sdk.dir` 由本机生成，已在 `.gitignore` 中。
- 扫码依赖 zxing；具体配对流程见根目录 [README](../README.md)。
- 厂商杀后台、权限坑见 [docs/pitfalls.md](../docs/pitfalls.md)。
