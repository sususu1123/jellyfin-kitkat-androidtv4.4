# v0.1.5 — Android 4.4.2 精简客户端 / Lightweight client

## 中文

首个仓库发布版本，包含目前完成的媒体浏览、原画直放、服务端转码、字幕和直播功能。

### 本版重点

- 替换为官方 Jellyfin PNG 图标，保留 Android 4.4 的安装和启动器兼容配置。
- 修复 MKV 内嵌字幕选择被忽略：固定对应视频源和字幕轨，明确要求服务端烧录。
- 修复直播直放结束后不自动转码；补充约 15 秒无首帧的检测。
- 回退和切台时先关闭旧直播流，再打开新流；兼容打开直播后视频源 ID 发生变化。

### 包含的功能

- 海报网格、向下自动加载、搜索、剧集浏览、继续观看和返回位置保留。
- 默认优先尝试原画直放；失败后默认转码为 1080p，可选 4K、720p、480p。
- 按住快进／快退只预览目标时间，松开后只跳转一次。
- 读取 Jellyfin 已配置的 Live TV 频道，并支持切台。

### 下载文件

- `jellyfin-kitkat-0.1.5.apk`：可直接安装的成品包。
- `jellyfin-kitkat-0.1.5-source.zip`：包含构建脚本、测试和图标许可的源码包。
- `SHA256SUMS.txt`：下载文件的 SHA-256 校验值。

最低系统为 Android 4.4 / API 19；主要面向 Android 4.4.2，开发与 API 验证使用 Jellyfin Server 10.10.7。同包名、同签名版本可覆盖升级；保留登录和设置。安装后需要自行填写服务器地址。

已通过 66 项 HTTP 接口断言，以及 Android 4.4.2 模拟器上的长按跳转、续播和两类直播回退测试。生成的双字幕 MKV 已验证选中轨的 FFmpeg 烧录。真实电视、实际片源、字体以及服务端 1080p/4K 编码性能仍需自行验证；4K 选项不代表旧电视具有 4K 解码能力。

这是独立精简实现，**不是 Jellyfin 官方发行版**。字幕由服务器烧录，开启字幕会触发转码；直播源需要预先在服务端配置。

## English

The first repository release, collecting the current browsing, original-quality playback, server transcoding, subtitle and Live TV features.

### Highlights

- Uses unchanged official Jellyfin PNG icons while retaining Android 4.4 installation and launcher compatibility.
- Fixes ignored MKV embedded-subtitle selections by pinning the selected media source and subtitle stream and explicitly requesting server burn-in.
- Fixes missing automatic fallback when a direct Live TV stream completes; also detects approximately 15 seconds without a first video frame.
- Closes the previous live stream before opening a replacement, including tuners that replace the provisional media-source ID after opening.

### Included features

- Poster grid, incremental scrolling, search, episode browsing, resume playback and preserved browsing position.
- Original-quality direct-play attempts by default; 1080p transcoding fallback, with optional 4K, 720p and 480p presets.
- Holding rewind/fast-forward previews a target; releasing performs a single seek.
- Server-configured Jellyfin Live TV channels and channel switching.

### Assets

- `jellyfin-kitkat-0.1.5.apk`: installable application.
- `jellyfin-kitkat-0.1.5-source.zip`: rebuildable source, build scripts, tests and icon notices.
- `SHA256SUMS.txt`: SHA-256 checksums of the downloadable files.

Requires Android 4.4 / API 19, primarily targeting Android 4.4.2. Development and API validation target Jellyfin Server 10.10.7. Updates with the same package name and signing key preserve login and settings. Enter your own server address after installation.

Validated with 66 HTTP contract assertions and Android 4.4.2 emulator tests for hold-to-seek, resume and both Live TV fallback cases. A generated MKV with two embedded subtitles was used to validate FFmpeg burn-in of the selected stream. Actual TVs, media, fonts and server-side 1080p/4K encoding performance still require testing. The 4K preset does not add 4K decoding capability to older hardware.

This is an independent, minimal implementation, **not an official Jellyfin release**. Subtitles require server transcoding; Live TV sources must already be configured on the server.
