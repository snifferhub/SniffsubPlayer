<!-- <p align="center">
  <img src="iosApp/SniffSubPlayer/Assets.xcassets/AppIcon.appiconset/AppIcon.png" width="112" height="112" alt="SniffSubPlayer 金色小狗应用图标">
</p> -->

<h1 align="center">SniffSubPlayer</h1>

<p align="center">
  <strong>发现网页视频，配上你的字幕。</strong><br>
  集内置浏览器、媒体嗅探与外挂字幕于一体的 Android / iOS 播放器。
</p>

<p align="center">
  <a href="https://github.com/snifferhub/SniffSubPlayer-Releases/releases/latest"><strong>下载 Android</strong></a>
  &nbsp; · &nbsp;
  <a href="#开始使用">使用指南</a>
  &nbsp; · &nbsp;
  <a href="docs/DEVELOPMENT.md">开发文档</a>
</p>

<p align="center">
  Android 9+ · arm64 &nbsp; / &nbsp; iOS 源码构建 &nbsp; / &nbsp; KMP 迁移中
</p>

---

在内置浏览器中打开视频网页，扫描媒体资源，再交给原生播放器播放。你可以导入 SRT / VTT 字幕、粘贴字幕文本，或在线搜索字幕并应用到当前视频。

| 浏览网页 | 发现媒体 | 配上字幕 |
| :--- | :--- | :--- |
| 打开站点、收藏页面，按需切换 UA 和广告屏蔽设置。 | 扫描网页中的媒体候选，选择 `.m3u8` / `.mp4` 等资源播放。 | 导入本地文件、粘贴文本或在线搜索，调整字幕偏移与字号。 |

> **当前阶段：** Android 提供 APK 下载，iOS 通过 Xcode 从源码构建。项目正在迁移到 Kotlin Multiplatform 与 Compose Multiplatform，双端功能及运行验证进度尚未完全对齐。

## 开始使用

1. **打开视频网页。** 在应用内输入网址，并在网页中开始播放，让页面加载媒体资源。
2. **扫描媒体候选。** Android 点击右下角圆形嗅探按钮；iOS 可在浏览器菜单中选择「扫描媒体资源」。
3. **选择资源播放。** 优先尝试 `.m3u8` 或 `.mp4` 候选，进入原生播放器。
4. **添加字幕。** 选择字幕文件、粘贴 SRT / VTT 文本，或打开「在线字幕」搜索并应用结果。

需要从 Subtitlecat 找字幕时，可在「在线字幕」中打开其网页，下载文件后再通过「字幕文件」导入。

## 下载安装

### Android

前往 **[发布页](https://github.com/snifferhub/SniffSubPlayer-Releases/releases/latest)**，在 Assets 中下载 `SniffSubPlayer-Android.apk` 并安装。

- **设备要求：** Android 9（API 28）及以上，ARM64。
- **校验文件：** 同一版本附带 `SHA256SUMS.txt`。
- **更新记录：** 每个 Release 包含该版本的更新说明。

### iOS

当前安装方式为源码构建，需要 macOS、Xcode、JDK 和自己的签名配置。

打开 `iosApp/SniffSubPlayer.xcodeproj`，选择自己的 Team 后构建并安装。依赖准备和构建说明见 **[iOS 本地构建](docs/DEVELOPMENT.md#ios-本地构建)**。

## 能做什么

| 功能 | 使用方式与当前范围 |
| :--- | :--- |
| 网页浏览 | Android WebView / iOS WKWebView；支持原生与自定义 UA。Android 当前为单页面，iOS 提供标签页。 |
| 媒体嗅探 | 识别 HLS、MP4、DASH、分片和 Blob 等候选；识别到资源不代表当前播放器一定支持播放。 |
| 原生播放 | Android 使用 Media3，iOS 使用 AVPlayer；播放控制条可自动隐藏。 |
| 外挂字幕 | 导入或粘贴 SRT / VTT；支持字幕偏移、字号调整及在线搜索。 |
| 网页收藏 | 在浏览器菜单收藏当前网页，在「设置 → 收藏夹」中打开或删除。 |
| 广告屏蔽 | 在「设置 → 广告屏蔽」管理 AdGuard / EasyList 规则订阅，支持总开关、每天自动更新与规则统计。 |

## 使用前了解

- **Blob 与分片不等于完整视频。** `blob:` 地址不能直接交给原生播放器；请寻找背后的 `.m3u8` / `.mp4` 等完整资源。
- **部分站点可能无法播放。** 播放器请求尚未接入浏览器 Cookie 服务，也没有本地媒体代理；复杂防盗链、跨域和重定向场景仍需完善。
- **Android 多标签页尚未实现。** 当前保持单会话、单 WebView；iOS 标签页也尚未跨进程保存。
- **在线字幕依赖第三方服务。** Subtitlecat 目前作为网页打开，下载后需手动导入字幕文件。
- **迁移仍在进行。** iOS 工程部署目标为 16.0，最低版本运行兼容性与旧数据迁移仍待设备验证。已记录的编译结果见[构建与验证记录](docs/DEVELOPMENT.md#构建与验证记录)。

## 参与开发

两端在同一仓库维护：`shared` 负责共享业务逻辑与平台服务，`composeApp` 负责共享界面，`app` 和 `iosApp` 分别承载 Android 与 iOS 宿主。

工程结构、迁移边界、构建命令、签名发布配置和后续计划集中在 **[开发与维护](docs/DEVELOPMENT.md)**。修改前请阅读 [仓库协作规则](AGENTS.md)：Android 编译仅在 GitHub CI 执行，iOS 暂时本地构建，测试与设备验证按维护者指定流程进行。

## 致谢与许可

感谢 **Linux.do 时光Alex**。

iOS 广告规则转换使用 [AdguardTeam/SafariConverterLib](https://github.com/AdguardTeam/SafariConverterLib)，其许可信息见[上游 LICENSE](https://github.com/AdguardTeam/SafariConverterLib/blob/master/LICENSE)。

本项目采用 [GNU General Public License v3.0](LICENSE)（`GPL-3.0-only`）。第三方依赖适用各自的许可证。
