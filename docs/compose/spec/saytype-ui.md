---
feature: saytype-ui
status: delivered
updated: 2026-09-12
branch: feat/mvp
commits: (uncommitted on feat/mvp; worktree add blocked by session)
---

# SayType Android UI（按 index.html 原型）

## Report

**What was built** — Android 端按根目录 `index.html` 原型重做成 SayType v0.3.0（applicationId 仍为 `com.phonetype.app`）。主界面改为三 Tab：配对引导 Connect（权限条 + 三步说明 + 扫码/手动）、Home（状态 hero、悬浮球开关、面板预览、最近发送）、History（发送记录/我的笔记 JSON 本地存储）、Settings（自动贴边、发送后清空、关于）。FloatingBubbleService 改为冰透玻璃 S 球：面板打开时隐藏球（P0-1）、收起后约 2s 自动贴边椭圆（P0-1b）、贴边点击先恢复完整球。扫码经 Manifest 覆盖 `CaptureActivity` 为竖屏（P0-2）。连接状态经 `AppBus` 在 Activity 与 Service 间共享 `WsManager`。

**Verification** — `gradlew assembleDebug` BUILD SUCCESSFUL；`node --test test/inject.unit.mjs` 2 passed。Tab S6（192.168.28.188:5555）安装启动：首屏 Home/Connect 正常；打开悬浮球开关后 overlay 窗口存在；点球打开面板时球隐藏，面板含清空/存笔记/发送；收起后约 2s overlay 尺寸变为 72×144（贴边态，dpi 360 下 32×64dp）。曾因 `bottomNav.selectedItemId` 与 listener 互调导致 StackOverflow，已用 `navProgrammatic` 防护。

**Journey log** —
1. 会话环境禁止 `git worktree add`，实现落在主工作区 `feat/mvp` 未提交改动之上。
2. `ScanOptions.setOrientationLock` 在 zxing-android-embedded 4.3.0 不存在 → Manifest `tools:replace="android:screenOrientation"`。
3. XML 不可写 `padding="8dp 4dp"` 简写 → 拆成四向 padding。
4. BottomNav 程序化选中会重入 listener → `navProgrammatic` 防护；`switchBall` 同类坑改为先同步再挂 listener。
5. 评审 critical 已修：贴边 UP 误开面板 → `wasDockedOnDown`；共享 `WsManager` 回调绑死 Activity → `AppBus` Listener 集合。真机复验贴边点击不再开面板。
6. 悬浮球最终改用用户提供的 ChatGPT 设计图（`D:\下载\ChatGPT Image 2026年9月12日 09_29_15.png`）圆形羽化抠图为 `drawable-nodpi/ic_ball_s_photo.png`，贴边态由该图横向压缩生成 `ic_ball_docked_photo.png`；脚本 `scripts/extract_ball_assets.py`。

## [S1] Problem

现有 Android 界面与已确认 `index.html` 原型差距大；悬浮球非玻璃 S 触点；面板与球不互斥；收起后不自动贴边；扫码横屏。

## [S2] Design

### 视觉与品牌

| Token | 值 |
|-------|-----|
| 显示名 | SayType |
| applicationId | `com.phonetype.app` |
| 主色 | `#2E6BFF` |
| 背景 | `#F4F6F9` |

### 信息架构

```text
Connect（未配对，无 Tab）→ 扫码/手动 → Home | History | Settings
```

悬浮球契约：面板开→球隐藏；关→完整球 + 2s idle 贴边；贴边点→完整球；设置可关自动贴边与发送后清空。

### 资产

- `drawable-nodpi/ic_ball_s_photo.png`：用户设计图抠图（冰透 S 球，512×512，圆形羽化透明底）
- `drawable-nodpi/ic_ball_docked_photo.png`：贴边胶囊（由完整球横向压缩）
- 主题禁用动态取色以保证品牌蓝 `#2E6BFF`

## [S3] Out of Scope

PC 黑窗口/端口占用、文件传输、UDP 发现、真实 RTT、iOS、改 applicationId。

## Tasks

- [x] T1: 主题与字符串 — SayType / #2E6BFF (covers: S2)
- [x] T2: 悬浮球矢量资产 (covers: S2)
- [x] T3: Connect 配对引导 (covers: S2; depends: T1)
- [x] T4: 三 Tab + Home (covers: S2; depends: T1,T3)
- [x] T5: History + Settings (covers: S2; depends: T4)
- [x] T6: FloatingBubbleService 重做 — 真机验证 P0-1/P0-1b (covers: S2; depends: T2)
- [x] T7: 扫码竖屏 (covers: S2; depends: T3)
- [x] T8: versionCode 6 + assembleDebug + S6 安装 (covers: S2)
