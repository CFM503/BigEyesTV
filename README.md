# BigEyes-TV 无线投屏接收端

> Android TV 电视端全屏播放器，实现 **Apple AirPlay 视频播放投屏** 与 **DLNA / UPnP 投屏** 协议，无需在发送端安装任何额外 App。

---

## ⚠️ 验证状态与兼容性说明 (请务必阅读)

本项目目前处于早期开发迭代阶段，各项协议的测试验证状态如下：

1. **AirPlay 视频播放协议 (Video Playback)**:
   - **已验证**：通过自动化单元测试与 `curl` 模拟请求（包括携带真实 `bplist00` 二进制载荷），验证了 `/server-info`、`/play`、`/playback-info`、`/rate`、`/scrub`、`/stop` 等 HTTP 接口及 Property List 编解码逻辑的正确性；
   - **待验证**：**尚未经过实体 iPhone / iPad 真机设备在局域网内的 AirPlay 发现与实际投屏播放链路验证**。
2. **DLNA / UPnP 协议 (MediaRenderer)**:
   - **已验证**：通过单元测试验证了 SSDP 广播报文构造、设备描述 XML 生成及 AVTransport SOAP 指令解析；
   - **待验证**：尚未在多品牌 Android 手机客户端（如华为/小米/OPPO系统投屏、第三方视频App）及复杂局域网组播网络下进行全面的真机兼容性验证。

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

## 一、已实现功能模块 (待真机实测)

1. **AirPlay 视频播放 (Video Playback 协议，不含屏幕镜像)**:
   - 基于 Android 原生 `NsdManager` + `MulticastLock` 广播 `_airplay._tcp.` mDNS 服务（端口 7000）；
   - 支持解析 iOS 客户端发送的 Binary Plist (`bplist00`) 与 XML 格式，提取 `Content-Location` 与播放进度；
   - 支持心跳状态同步 (`/playback-info`)、进度调整 (`/scrub`)、暂停继续 (`/rate`) 与断开 (`/stop`)。
2. **DLNA / UPnP 投屏 (MediaRenderer)**:
   - 遵循 UPnP AV 1.0 标准，支持 `ssdp:alive` 组播通知与 `M-SEARCH` 响应；
   - 实现了 UPnP 描述文件 (`/description.xml`) 及 AVTransport 控制接口（`SetAVTransportURI`、`Play`、`Pause`、`Seek`、`Stop`、`GetPositionInfo`）。
3. **单端口统一服务**:
   - HTTP 服务统一运行于 `7000` 端口，同时分发 AirPlay 与 DLNA 路由。
4. **后台常驻与锁管理**:
   - 前台服务 `TvReceiverService` 持有 `PARTIAL_WAKE_LOCK` 与 `WifiLock`，防止电视息屏时网络休眠。

---

## 二、构建与安装

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 运行单元与集成测试
./gradlew test
```

构建生成的 APK 位于：`app/build/outputs/apk/debug/app-debug.apk`。
