# BigEyes-TV 无线投屏接收端

> Android TV 电视端全屏播放器，同时支持 **Apple AirPlay 视频播放投屏** 与 **DLNA / UPnP 投屏**，无需在发送端安装任何 App。

---

## 架构示意图

```
┌─────────────────────────────────────────────────────────────┐
│                 BigEyes-TV (Android TV)                     │
│                                                             │
│  ┌───────────────────────┐       ┌───────────────────────┐  │
│  │ AirPlay mDNS 服务     │       │ DLNA SSDP 多播服务    │  │
│  │ (_airplay._tcp:7000)  │       │ (UDP 1900 /alive/resp)│  │
│  └──────────┬────────────┘       └───────────┬───────────┘  │
│             │                                │              │
│             ▼                                ▼              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │           内嵌 NanoHTTPD HTTP 服务器 (:7000)           │  │
│  │  - AirPlay 路由: /server-info, /play, /playback-info │  │
│  │  - DLNA 路由: /description.xml, /upnp/control/...     │  │
│  │  - dd-plist (Binary Plist & XML Plist 编解码)         │  │
│  └──────────────────────────┬────────────────────────────┘  │
│                             │                               │
│                             ▼                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │        统一播放控制层 (TvPlayerManager / ExoPlayer)      │  │
│  │            HLS (.m3u8) / MP4 / DASH 流媒体自适应拉流   │  │
│  └──────────────────────────┬────────────────────────────┘  │
│                             │                               │
│                             ▼                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │      全屏播放 UI (PlayerView) + 待机仪表盘 (Dashboard)     │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 一、特性与能力

1. **AirPlay 视频播放 (Video Playback)**:
   - iPhone / iPad 打开 Safari 网页视频或各大视频 App，在系统 AirPlay 列表中选择 `BigEyes TV`，无需安装任何第三方 App；
   - 自动解析 iPhone 发送的 Binary Plist (`bplist00`) 与 XML 格式，精准提取 `Content-Location` 与起播进度；
   - 完整支持心跳状态同步 (`/playback-info`)、进度调整 (`/scrub`)、暂停继续 (`/rate`) 与断开 (`/stop`)。
2. **DLNA / UPnP 投屏 (MediaRenderer)**:
   - 遵循 UPnP AV 1.0 标准，支持 Android 手机相册、B站/腾讯/爱奇艺等 App 的 `TV` 投屏按钮；
   - 支持 `SetAVTransportURI`、`Play`、`Pause`、`Seek`、`Stop`、`GetPositionInfo`。
3. **单端口统一架构**:
   - HTTP 服务统一运行于 `7000` 端口，同时服务 AirPlay 与 DLNA 路由，无多端口冲突。
4. **后台常驻与锁管理**:
   - 前台服务持有 `PARTIAL_WAKE_LOCK` 与 `WifiLock`，防止电视息屏或进入低功耗模式时服务被杀死。

---

## 二、构建与安装

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 运行测试
./gradlew test
```

构建生成的 APK 位于：`app/build/outputs/apk/debug/app-debug.apk`。
