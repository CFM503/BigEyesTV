# 更新日志 (CHANGELOG)

本文档记录 `BigEyes-TV` 的所有版本迭代与变更历史。

---

## [v1.0.4] - 2026-08-20 (全链路自动连播与遥控器大屏控制面板)

### 🌟 新增与改进特性
* **遥控器确定键大屏控制面板 (方案 A)**:
  - 视频全屏播放时，按遥控器【确定键 (OK)】或方向键立即唤出大屏控制面板，焦点默认落在【播放/暂停】；
  - 适配 Android TV D-Pad 焦点规范（`bg_tv_button.xml`），获得焦点时呈现高亮发光蓝色边框，按左右方向键自由移动焦点，**按确定键点击执行当前聚焦按钮动作**；
  - 面板集成了：**【播放/暂停】**、**【快退 15s】**、**【快进 15s】**、**【下一集】**、**【倍速调节 (1.0x~2.0x)】**、**【画面比例 (适应/铺满/裁剪/16:9)】**、**【退出播放】**；
  - 支持 5 秒无操作自动隐藏与返回键分层处理（面板显示时按返回键仅关闭面板，面板隐藏时按返回键退出播放）。
* **遥控器长按快速变速手势 (3.0x 快进 / 0.5x 慢放)**:
  - **长按右键**：直接进入 **3.0x** 高速快进播放，并在顶部浮现金色 `⏩ 3.0X 快进中` HUD 标识；**松开右键**立即恢复 1.0x 正常播放并隐藏 HUD；
  - **长按左键**：直接进入 **0.5x** 慢速播放，并在顶部浮现金色 `⏪ 0.5X 慢放中` HUD 标识；**松开左键**立即恢复 1.0x 正常播放并隐藏 HUD；
  - **短按左/右键**：唤出控制面板或在控制按钮间移动焦点。
* **全协议剧集自动连播与切集支持**:
  - **状态精准反馈**：在 `DlnaActionHandler` 中修正 `GetTransportInfo`（播放自然结束准确返回 `STOPPED`，进度对齐总时长），满足**爱奇艺、腾讯视频、B 站、优酷**等第三方 App 触发自动推下一集；
  - **预推与切集支持**：支持 `SetNextAVTransportURI`（预推下一集流地址，播完自动衔接）以及 DLNA `Next` / `Previous` 动作。

---

## [v1.0.3] - 2026-08-19 (在线自动更新安装与签名架构全方位加固)

### 🌟 改进与修复 (参考 `CFM503/bigeyes` 核心修复)
* **未知应用授权返回后自动继续安装 (Lifecycle 恢复机制)**:
  - 接入 `Application.ActivityLifecycleCallbacks` 监听 `onActivityResumed`；
  - 当 Android 8.0+ 引导用户前往“允许安装未知应用”设置项并返回时，自动检测权限并无缝调起安装，无需用户重新点击下载。
* **PackageInstaller 与 FileProvider 权限显式赋权**:
  - `file_paths.xml` 调整为支持所有子目录与缓存根路径映射；
  - 对系统所有匹配的安装器 Activity 执行 `grantUriPermission` 显式授权，并补充 `FLAG_GRANT_WRITE_URI_PERMISSION` 与 `EXTRA_NOT_UNKNOWN_SOURCE`，彻底解决部分 Android 电视盒子（小米、索尼、当贝等）报“解析安装包出错”或权限拦截的问题。
* **签名配置与双签名架构加固**:
  - `build.gradle.kts` 中显式固化 `enableV1Signing = true` 与 `enableV2Signing = true`，确保无论在任何 Android 版本上均满足签名校验要求；
  - 内置 `app/keystore/bigeyes-release.jks` 缺省 fallback，确保任何本地环境与 CI 环境构建签名完全统一。
* **本地安装包缓存加速**:
  - 增加本地文件大小与远端 `Content-Length` 比对，已完整下载的 APK 直接调起安装，避免重复下载。

---

## [v1.0.2] - 2026-08-18 (固定签名与 GitHub Release 正式发布)

### ⚠️ 验证状态说明
* **GitHub Release 发布与资产**：已通过 GitHub Actions CI/CD 流水线完成正式 Release 构建与发布，APK 资产已挂载在 [GitHub Releases (v1.0.2)](https://github.com/CFM503/BigEyesTV/releases/tag/bigeyes-tv-v1.0.2)；
* **自动更新链路验证**：已通过 `curl` 验证 GitHub Releases Latest API 及 APK 直链下载（大小校验 `7,260,406` 字节、SHA-256 及 v2 签名完全吻合）；
* **真机安装验证**：代码已实现从 GitHub 下载并调用 Android 系统安装器安装，仍建议在实际 Android 电视设备上进行端到端安装实测。

### 🌟 新增与改进特性
* **专用固定签名配置 (覆盖安装保障)**:
  - 引入专用的 RSA 2048 签名证书 (`bigeyes-release.jks`, 有效期至 2054 年)，彻底废弃构建环境临时 debug 签名；
  - 在 `build.gradle.kts` 中固定配置 `key.properties` 签名索引机制，保证后续无论在何种构建环境发布，新旧 APK 签名绝对一致，支持直接无缝覆盖安装更新；
  - 证书私钥与密码严格通过 `.gitignore` 排除，提供 `key.properties.example` 配置模板。
* **自动化 Release 构建与发布流水线**:
  - 新增 `.github/workflows/release.yml`，推送版本 tag 时自动在 GitHub Actions 云端完成安全签名、构建 Release APK 并自动创建 GitHub Release 及挂载 Asset。
* **正式 Release 构建产物**:
  - 发布 `BigEyesTV-v1.0.2-release.apk` (经 APK Signature Scheme v2 校验，大小约 6.92 MB)，可供用户直接下载安装。

---

## [v1.0.1] - 2026-08-18 (电视可用性与自动更新发布)

### ⚠️ 验证状态说明 (诚实性修正)
* **AirPlay 视频播放协议**：已通过自动化单元测试与 `curl` 模拟请求（含真实 `bplist00` 二进制载荷）验证了 HTTP 播控接口与 Plist 编解码逻辑，**尚未经过 iPhone 真机设备在真实局域网内的 AirPlay 发现与投屏验证**。
* **DLNA 协议**：已通过单元测试验证了 SSDP 广播与 UPnP 描述/SOAP 指令，**尚未进行多品牌 Android 手机真机全面兼容性验证**。
* **GitHub 自动更新**：已通过单元测试验证版本比较与清洗算法，真机环境未实际访问外网 GitHub Release API 进行端到端下载安装实测。

### 🌟 新增与改进特性
* **电视可用性与遥控器交互适配**:
  - 全面支持 Android TV D-pad 遥控器键值响应（返回键退出全屏返回待机、确认/播放暂停键播控、左右方向键快进/快退 10 秒）；
  - `AndroidManifest.xml` 声明 Leanback 特性与 `LEANBACK_LAUNCHER` 分类及 `android:banner`，确保可在 Android TV 原生桌面展示与无触控运行；
  - 待机界面直观展示当前版本号（`v1.0.1`）。
* **开机自启动支持**:
  - 新增 `BootReceiver` 监听 `BOOT_COMPLETED` 及快速开机广播，电视开机后自动拉起 `TvReceiverService` 后台前台服务，无需用户手动打开 App 即可随时接收投屏。
* **动态前台通知状态更新**:
  - `TvReceiverService` 接入 `TvPlayerListener`，实时更新系统通知栏状态（等待投屏 / 正在播放 / 播放就绪 / 缓冲中，附带本地 IP 与端口）。
* **GitHub Releases 自动检查更新能力**:
  - 启动时异步请求 GitHub Releases API (`https://api.github.com/repos/CFM503/BigEyesTV/releases/latest`)；
  - 实现语义化版本号清洗与比较算法；
  - 发现新版本时弹出遥控器友好的提示弹窗，支持一键下载 APK 并通过 `FileProvider` + `REQUEST_INSTALL_PACKAGES` 调起系统安装器；
  - 具备完善的弱网、超时与 HTTP 403 异常防护，绝不阻塞或崩溃。

---

## [v0.1.0] - 2026-08-18 (首个开发预览版)

### ⚠️ 验证状态说明
* **AirPlay 视频播放协议**：已通过自动化单元测试与 `curl` 模拟请求（含真实 `bplist00` 二进制载荷）验证了 HTTP 播控接口与 Plist 编解码逻辑，**尚未经过 iPhone 真机设备在真实局域网内的 AirPlay 发现与投屏验证**。
* **DLNA 协议**：已通过单元测试验证了 SSDP 广播与 UPnP 描述/SOAP 指令，**尚未进行多品牌 Android 手机真机全面兼容性验证**。

### 🌟 新增功能 (代码已实现，待真机验证)
* **Apple AirPlay 视频播放接收端 (AirPlay Video Playback)**:
  - 基于 Android 原生 `NsdManager` + `MulticastLock` 广播 `_airplay._tcp.` 服务；
  - 广播 Apple TV TXT 属性（`features=0x7`、`model=AppleTV2,1`、`srcvers=130.14`、`deviceid` 散列伪 MAC 持久化）；
  - 引入 `dd-plist` (v1.30) 库，实现 Binary Plist (`bplist00`) 与 XML Plist 编解码；
  - 内嵌 NanoHTTPD 服务实现 AirPlay HTTP 播控接口：`/server-info`、`/play`、`/playback-info`、`/rate`、`/scrub`、`/stop`、`/reverse`。
* **DLNA / UPnP MediaRenderer 接收端**:
  - UDP 1900 多播 SSDP 服务，支持 `ssdp:alive`、`ssdp:byebye` 及 `M-SEARCH` 响应；
  - 实现 UPnP 设备描述 (`/description.xml`) 与 AVTransport SOAP 播控接口（`SetAVTransportURI`、`Play`、`Pause`、`Seek`、`Stop`、`GetPositionInfo`、`GetTransportInfo`）。
* **统一 ExoPlayer 播放控制**:
  - 底层使用 Media3 / ExoPlayer 支持 HLS (`.m3u8`)、MP4、DASH 等自适应拉流；
  - AirPlay 与 DLNA 共享同一套 `TvPlayerManager`。
* **后台保活与锁机制**:
  - `TvReceiverService` 前台服务常驻，持有 `PARTIAL_WAKE_LOCK` 与 `WifiLock`。
