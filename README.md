# phone-type

手机上用系统输入法（微信/豆包等）把话打成字，一点发送，字就出现在 Windows 当前光标处。

## 原理

```text
手机 IME → App 输入框 → 局域网 WebSocket → PC 服务 → 剪贴板+Ctrl+V → 当前焦点
```

只传文字，不传音频。延迟通常是几十毫秒。

## 组成

| 目录 | 说明 |
|------|------|
| `server/` | Windows 本地服务，`npm start` |
| `android/` | Android 悬浮窗 App 源码，用 Android Studio 打开编译 |

## 快速开始（PC）

```powershell
cd D:\Workspace\phone-type
npm install
npm start
```

控制台会打印本机局域网 IP、端口 `8787` 和 6 位 PIN。手机 App 填入即可。

## 快速开始（Android）

1. Android Studio 打开 `android/`
2. 连真机，Run
3. 首次按引导授予「显示在其他应用上层」
4. App 内填 `192.168.x.x`、端口、PIN → 连接
5. 点悬浮球 → 弹出输入框 → 用微信/豆包语音 → 发送

## 协议

见 `CLAUDE.md`。连接后先 `hello`+PIN，再 `text`。

## 约束

- 同一 WiFi/局域网
- PIN 随机，每次 `npm start` 重新生成
- 部分全屏游戏 / UAC / 远程桌面可能拒绝粘贴注入
