# 更新日志 (CHANGELOG)

本文档记录 `BigEyes-TV` 的所有版本迭代与变更历史。

---

## [v0.1.0] - 2026-08-18 (首个功能完整版)

### 🌟 新增功能
* **Apple AirPlay 视频播放接收端 (AirPlay Video Playback)**:
  - 基于 Android 原生 `NsdManager` + `MulticastLock` 实现 Bonjour/mDNS `_airplay._tcp.` 服务广播与设备发现；
  - 广播标准 Apple TV TXT 属性（`features=0x7`、`model=AppleTV2,1`、`srcvers=130.14`、`deviceid` 伪 MAC 固定持久化）；
  - 引入 `dd-plist` (v1.30) 库，完整支持现代 iOS 发送的 Binary Plist (`bplist00`) 与标准 XML Plist 编解码；
  - 内嵌 NanoHTTPD 服务实现完整 AirPlay HTTP 播控接口：`/server-info`、`/play`、`/playback-info`、`/rate`、`/scrub`、`/stop`、`/reverse`。
* **DLNA / UPnP MediaRenderer 接收端**:
  - 自建 UDP 1900 多播 SSDP 服务，支持 `ssdp:alive`、`ssdp:byebye` 及 `M-SEARCH` 响应；
  - 完整实现 UPnP 设备描述 (`/description.xml`) 与 AVTransport SOAP 播控接口（`SetAVTransportURI`、`Play`、`Pause`、`Seek`、`Stop`、`GetPositionInfo`、`GetTransportInfo`）。
* **统一 ExoPlayer 全屏播放引擎**:
  - 底层使用 Media3 / ExoPlayer 支持 HLS (`.m3u8`)、MP4、DASH 等格式自适应拉流；
  - AirPlay 与 DLNA 共享同一套 `TvPlayerManager`，UI 保持无缝全屏播放体验与待机信息面板。
* **息屏与后台保活支持**:
  - `TvReceiverService` 前台服务常驻，持有 `PARTIAL_WAKE_LOCK` 与 `WifiLock`，防止电视息屏时网络降频或休眠。
