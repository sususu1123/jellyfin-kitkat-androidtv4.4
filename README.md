# Jellyfin KitKat Android TV 4.4

**Android 4.4.2（API 19）轻量电视客户端 / Lightweight TV client for Android 4.4.2 (API 19)**

[中文](#中文) · [English](#english) · [下载 / Downloads](https://github.com/xbl916/jellyfin-kitkat-androidtv4.4/releases) · [更新说明 / Release notes](RELEASE_NOTES.md)

这是为旧安卓电视编写的独立精简客户端，**不是 Jellyfin 官方发行版，也不是官方 Android TV 客户端的完整移植**。使用官方 Jellyfin 图标，应用代码和功能由本项目独立维护。

An independently implemented, minimal client for older Android TVs. **This is not an official Jellyfin release or a full port of the official Android TV application.** The launcher icon is an unchanged official Jellyfin asset.

## 中文

### 下载与安装

- [下载最新版](https://github.com/xbl916/jellyfin-kitkat-androidtv4.4/releases/latest)
- [下载 v0.1.5 APK](https://github.com/xbl916/jellyfin-kitkat-androidtv4.4/releases/download/v0.1.5/jellyfin-kitkat-0.1.5.apk)
- 最低系统：**Android 4.4 / API 19**，以 Android 4.4.2 电视为主要兼容目标。
- 对接开发与验证的 Jellyfin 服务端版本：**10.10.7**；其他版本尚未系统验证。

1. 将 `.apk` 文件复制到 U 盘，用电视上的文件管理器安装；按电视提示允许相应的安装来源。
2. 打开 **Jellyfin 精简版**，输入服务器地址，例如 `http://192.168.1.10:8096`，再填写用户名和密码。
3. 进入媒体库或电视直播，使用方向键和确认键操作。
4. 同包名、同签名的新版可以直接覆盖安装，保留登录与设置。无需 ADB、Android Studio 或 Google 服务。

APK、可重建源码包及 `SHA256SUMS.txt` 位于 Releases。下载后可在 PowerShell 中检查文件摘要：

```powershell
Get-FileHash .\jellyfin-kitkat-0.1.5.apk -Algorithm SHA256
```

### 主要功能

- **原生轻量界面**：不使用 WebView、Compose、VLC 或内置 FFmpeg；调用系统播放器、网络接口及解码器。
- **海报网格**：媒体库、电影、季、剧集及直播频道使用方块卡片；向下浏览自动分批加载，返回时保留位置。
- **媒体浏览**：文件夹浏览、名称搜索、继续观看、播放前选择音轨与字幕。
- **原画优先**：先尝试原始文件，不使用转码档位限制原视频的分辨率、码率、封装或编码；实际解码失败后再转码。
- **转码档位**：默认 **1080p / 12 Mbps**，可选 **4K / 40 Mbps**、720p / 6 Mbps、480p / 2 Mbps。档位是转码上限，不会强行放大低分辨率源片，也不会限制成功直放的原片。
- **内嵌字幕**：固定所选视频源与字幕索引，要求服务器将选中字幕烧录到视频画面。开启字幕会使用服务端转码。
- **长按快进／快退**：按住时只调整预览时间，松开后只跳转一次；支持取消预览、播放进度回传与续播。
- **电视直播**：读取服务端已经配置的 Live TV 频道及当前节目信息，支持切台；直放出错、提前结束或约 15 秒无首帧时尝试转码。
- **旧系统安装兼容**：普通 PNG 图标、普通与电视启动入口、适用于 Android 4.4 的 v1 APK 签名。

### 遥控器操作

| 场景 | 按键 | 操作 |
| --- | --- | --- |
| 浏览 | 方向键、确认 | 选择卡片，打开文件夹或播放选项 |
| 浏览 | 返回 | 返回上层；顶层询问是否退出 |
| 视频控制栏隐藏 | 确认 | 显示控制栏 |
| 视频控制栏隐藏 | 按住左／右 | 连续调整目标时间；松开后跳转一次 |
| 点播播放中 | 按住专用快进／快退键 | 连续调整目标时间；松开后跳转一次 |
| 控制栏选中快进／快退 | 按住确认 | 连续调整目标时间；松开后跳转一次 |
| 时间预览中 | 返回 | 取消跳转，恢复原位置 |
| 直播控制栏隐藏 | 上／下 | 切换当前已加载列表中的频道 |
| 播放中 | 返回 | 停止播放并返回浏览界面 |

原本暂停的视频在跳转后仍保持暂停。HLS 跳转可能对齐邻近分片或关键帧，不保证逐帧精确。

### 服务端与兼容性

- 账号需要媒体库、直播及相应转码权限；转码需要服务端 FFmpeg 和编码配置正常工作。
- 直播源应先在 Jellyfin 服务端配置。本应用不自动抓取频道，也不单独导入 M3U。
- 4K 选项不能赋予电视原本没有的解码能力；实际可播放格式、分辨率及码率取决于电视、网络和服务端性能。
- 字幕由服务器烧录；效果还受源字幕、字体和服务端配置影响。
- 不含完整 EPG、录制预约、直播回看、下载、投屏、自动连播及完整官方客户端的全部功能。
- 密码不保存；登录令牌保存在应用私有目录，退出登录时清除本地令牌。应用已禁止系统备份。
- HTTP 仅适合可信局域网；HTTPS 仍验证证书，旧系统的 TLS／证书支持可能影响连接。

### 构建

支持的构建环境为 **Windows + Python 3.11 或以上版本**。不需要预先安装 Android Studio、Gradle 或配置系统 PATH。

```powershell
git clone git@github.com:xbl916/jellyfin-kitkat-androidtv4.4.git
cd jellyfin-kitkat-androidtv4.4

# 默认将工具链、中间文件、测试输出与 APK 放到 E:\JellyfinKitKat。
# 如果没有 E 盘，可改为其他可写目录。
$env:KITKAT_WORK = 'E:\JellyfinKitKat'
$env:KITKAT_TOOLCHAIN = Join-Path $env:KITKAT_WORK 'toolchain'

python bootstrap.py
python test.py
python build.py
```

`bootstrap.py` 下载并校验独立的 Temurin JDK 8、Android Platform 19 和 Build Tools 30.0.3。APK 与源码 ZIP 输出到 `$env:KITKAT_WORK\dist`。图标 PNG 已包含在源码内，不需要重新下载才能构建。

签名密钥保存在本机工作目录，不上传仓库。使用不同密钥构建的 APK 无法直接覆盖安装现成的 Release APK；维护升级包时需保留原密钥。脚本中的本地构建口令不是生产环境的密钥管理方案。

### 验证范围

- **66 项**本地 HTTP 接口断言：认证、源选择、原画与转码协商、字幕轨道、烧录参数、直播清理、进度回传及 URL 处理。
- **Android 4.4.2 / API 19 模拟器**：安装、启动、按住预览后一次跳转、快退、续播；直播直放结束与无画面时自动转码；旧流关闭后再打开新流。
- **网格测试**：模拟 125 个条目，检查自动加载和文件夹返回位置。
- **生成的 MKV 测试片**：包含两条内嵌 SubRip 字幕，验证第二条字幕可以经 FFmpeg 烧录，并检查生成的可见字幕帧。

这些测试不等于所有旧电视、所有 Jellyfin 版本、真实频道及字体均已验证。早期版本已在一台 Android 4.4.2 夏普电视上确认可安装、登录和播放；v0.1.5 的实际片源仍需设备侧复测。

额外的媒体与模拟器测试需要现有 FFmpeg 和单独准备的 API 19 模拟器：`test_seek.py`、`test_grid.py`、`test_subtitle.py`。模拟器测试固定使用 `emulator-5580` 和生成的模拟账号／片源，不使用真实账号。

## English

### Download and install

- [Latest release](https://github.com/xbl916/jellyfin-kitkat-androidtv4.4/releases/latest)
- [Download the v0.1.5 APK](https://github.com/xbl916/jellyfin-kitkat-androidtv4.4/releases/download/v0.1.5/jellyfin-kitkat-0.1.5.apk)
- Minimum OS: **Android 4.4 / API 19**, primarily targeting Android 4.4.2 TVs.
- Development and API validation target **Jellyfin Server 10.10.7**. Other server versions have not been comprehensively tested.

1. Copy the `.apk` to a USB drive and install it with the TV's file manager, allowing the installation source when prompted.
2. Open **Jellyfin 精简版**, enter your server URL, such as `http://192.168.1.10:8096`, and sign in.
3. Browse your libraries or Live TV with the remote's directional and confirmation keys.
4. Updates with the same package name and signing key can be installed over existing versions without clearing login or settings. ADB and Google services are not required.

Releases include the APK, a rebuildable source ZIP, and `SHA256SUMS.txt`. Use `Get-FileHash .\jellyfin-kitkat-0.1.5.apk -Algorithm SHA256` to verify the APK.

### Features

- **Small native client:** uses Android's built-in UI, networking, player and codecs; no WebView, Compose, bundled VLC or FFmpeg.
- **Poster grid:** browse libraries, movies, seasons, episodes and channels; scrolling automatically loads more items and preserves your position when returning.
- **Library access:** folder navigation, title search, resume playback, and audio/subtitle selection before playback.
- **Original quality first:** tries the original file without imposing the fallback resolution, bitrate, container or codec restrictions. Decoder failures trigger server transcoding.
- **Transcoding presets:** defaults to **1080p / 12 Mbps**; also offers **4K / 40 Mbps**, 720p / 6 Mbps and 480p / 2 Mbps. These are transcoding ceilings, not limits on successful direct playback; lower-resolution sources are not forcibly upscaled.
- **Embedded subtitles:** pins the selected media source and subtitle stream, then asks the server to burn subtitles into the video. Enabling subtitles requires server transcoding.
- **Hold-to-seek:** holding a key only adjusts a target-time preview; releasing it performs one seek. Includes cancellation, progress reporting and resume playback.
- **Live TV:** browses server-configured channels and current program information, switches channels, and attempts transcoding after a direct-play error, early completion or approximately 15 seconds without a first video frame.
- **Legacy installation support:** conventional PNG icons, phone/TV launcher entries and v1 APK signatures for Android 4.4.

### Remote controls

| Context | Key | Action |
| --- | --- | --- |
| Browsing | Directions / confirm | Select a card or open a folder or playback options |
| Browsing | Back | Return to the parent; ask to exit at the top level |
| Playback, controls hidden | Confirm | Show controls |
| On-demand video, controls hidden | Hold left / right | Adjust the target time; release to seek once |
| On-demand video | Hold dedicated rewind / fast-forward | Adjust the target time; release to seek once |
| Rewind / fast-forward control focused | Hold confirm | Adjust the target time; release to seek once |
| Seek preview | Back | Cancel and return to the original position |
| Live TV, controls hidden | Up / down | Switch channels in the currently loaded list |
| Playback | Back | Stop and return to browsing |

Seeking preserves the paused state. HLS seeks may align to a nearby segment or keyframe rather than an exact frame.

### Requirements and limitations

- The account must have access to the requested libraries, Live TV and transcoding. Server-side FFmpeg and encoding must be configured correctly.
- Configure Live TV sources on the server first. This client does not discover channels or import standalone M3U playlists.
- The 4K preset does not add decoding capabilities to old hardware. Playback depends on the TV, network and server performance.
- Subtitle appearance also depends on the source subtitles, fonts and server configuration.
- No complete EPG, recording scheduling, live timeshift, downloads, casting, automatic next-episode playback or full official-client feature set.
- Passwords are not retained. Login tokens are stored in the app's private storage and removed locally on logout; system backup is disabled.
- Use HTTP only on trusted local networks. HTTPS certificate validation remains enabled; legacy TLS and certificate support can affect connectivity.

### Build

Use **Windows and Python 3.11+**. Android Studio, a global Gradle installation and system PATH changes are not required.

```powershell
git clone git@github.com:xbl916/jellyfin-kitkat-androidtv4.4.git
cd jellyfin-kitkat-androidtv4.4

$env:KITKAT_WORK = 'E:\JellyfinKitKat'
$env:KITKAT_TOOLCHAIN = Join-Path $env:KITKAT_WORK 'toolchain'

python bootstrap.py
python test.py
python build.py
```

Change the work directory if drive E: is unavailable. The bootstrap downloads and verifies a private Temurin JDK 8, Android Platform 19 and Build Tools 30.0.3. Build products and the source ZIP are written to `$env:KITKAT_WORK\dist`. The required icon PNGs are included in the repository.

Signing keys stay in the local work directory and are excluded from the repository. APKs signed with a different key cannot upgrade the prebuilt release in place. Retain your signing key for future updates; the script's local-build password is not a production secret-management solution.

### Validation

- **66 local HTTP contract assertions:** authentication, media source selection, direct/transcoded negotiation, subtitle streams, burn-in URL parameters, live-stream cleanup, progress and URL handling.
- **Android 4.4.2 / API 19 emulator:** installation and launch, hold-to-preview and single seek on release, rewind and resume; automatic Live TV fallback on completion or missing video frames; ordered close-before-open cleanup.
- **Grid fixture:** 125 generated items, incremental loading and preserved position after leaving a folder.
- **Generated MKV fixture:** two embedded SubRip streams; FFmpeg burn-in of the second stream with a visually inspected output frame.

This does not establish compatibility with every TV, server version, real channel or font. Earlier builds were confirmed to install, sign in and play on one Android 4.4.2 Sharp TV. The v0.1.5 changes still require testing with actual device-side media.

Optional media/emulator tests use existing FFmpeg and a separately prepared API 19 emulator: `test_seek.py`, `test_grid.py` and `test_subtitle.py`. Emulator scripts target only `emulator-5580` and use generated fixtures and mock accounts.

## 图标许可 / Icon attribution

Official icon artwork: **The Jellyfin Project and contributors**, from [jellyfin/jellyfin-ux](https://github.com/jellyfin/jellyfin-ux/tree/69a2b7b1237732a134dd501ff55b9404cf2d1601/branding/android/tv/app), provided unchanged under **CC BY-SA 4.0**. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [ICON_LICENSE.txt](ICON_LICENSE.txt). These notices are also packaged in the APK.
