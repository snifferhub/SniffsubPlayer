# 开发与维护

[返回应用介绍](../README.md)

这里记录工程边界、构建入口、发布配置与迁移进度。参与修改前请先阅读 [AGENTS.md](../AGENTS.md)。

## 工程结构与迁移边界

两端源码已放在根目录的同一工作区。Android 使用 `app`，iOS 宿主及 Xcode 工程统一放在 `iosApp`；旧 Android worktree 已删除。

| 目录 | 职责 |
| --- | --- |
| `shared/src/commonMain` | 浏览器状态、原生历史快照、标签页会话、候选资源解析、字幕解析与时间轴、偏好设置与收藏修改逻辑、广告设置状态模型、Cookie 服务接口 |
| `shared/src/androidMain` | Android CookieManager、URL/时钟/ID 平台实现 |
| `shared/src/iosMain` | WKHTTPCookieStore、NSUserDefaults 偏好持久化与旧 Swift 数据迁移、URL/时钟/ID 平台实现 |
| `composeApp/src/commonMain` | 共享浏览器地址栏、工具栏、标签页、候选列表、设置页、收藏夹、广告屏蔽设置 UI 及嗅探 JS 资源 |
| `composeApp/src/androidMain` | Android WebView 接入、生命周期与内容过滤接口 |
| `composeApp/src/iosMain` | WKWebView 接入、Compose UIViewController 与 Swift 回调桥接 |
| `app` | Android 应用入口与导航、Media3 播放器、偏好持久化、广告过滤、字幕下载与应用更新 |
| `iosApp` | iOS Xcode 工程、Compose 宿主与桥接、暂留的 AVPlayer 播放器及字幕搜索 UI、原生广告规则处理 |

历史模型保存 WebView / WKWebView 的实际前进后退列表快照。iOS 的每个标签页有独立会话和原生视图，标签页目前仅在进程内保存。Android 当前为单会话、单 WebView，继续恢复当前页面地址；多标签页仍在计划中。

浏览器、设置、收藏夹、广告屏蔽设置 UI 已由两端共用 Compose 实现；浏览器状态、媒体候选模型、字幕解析与时间轴、UA/主题色/收藏修改逻辑位于共享 Kotlin 层。Android 保留原有应用导航和更新入口，iOS 通过共享 `AppScreen` 导航并隐藏 Android 更新入口。

iOS 首次读取时，将原有 `SniffSubPlayer.userAgent`、`SniffSubPlayer.playerAccentColor` 和 `SniffSubPlayer.bookmarks` 导入 Kotlin 管理的偏好数据，保留收藏 ID 与创建时间。若解码失败，保留原始数据并显示错误，不用空收藏覆盖旧数据。主题色继续同步到原生播放器使用的旧存储键。

旧 Swift `BrowserStore`、浏览器/设置/收藏列表 UI、重复媒体分类及收藏模型已删除。`iosApp` 当前仍含尚未迁移的功能代码，不能把目录整理当作完成迁移：播放器控件和字幕搜索仍是两端实现；广告设置 UI 与状态模型共享，规则下载、缓存、转换和应用仍由 Android 规则引擎及 iOS `AdBlockManager` 分别负责。iOS 转换依赖 `SafariConverterLib`，规则编译和应用依赖 WebKit。Cookie 接口及两端实现已提供，但播放器请求尚未接入 Cookie，也没有跨设备 Cookie 同步。

迁移确认可用后，初期计划只保留 `main`；`dev` 留待以后需要时建立。旧 Android worktree 已在确认源码与 GitHub `Android` 分支一致后按维护者要求删除；现有本地和远端分支尚未删除，远端发布策略不变。目录清理不代表迁移已验证可用。


## iOS 本地构建

安装 Xcode 与 JDK，并确保 Xcode 的构建环境可以找到 Java。原构建记录使用 JDK 22；首次构建需要联网下载 Gradle、Kotlin/Native、Compose 和 Swift Package 依赖。

1. 用 Xcode 打开 `iosApp/SniffSubPlayer.xcodeproj`。
2. 如需安装到自己的设备，在 Signing & Capabilities 中选择 Team。
3. Xcode 构建阶段从仓库根目录执行 `:composeApp:embedAndSignAppleFrameworkForXcode`，生成 `ComposeApp` framework 并复制共享资源。

iOS 工程部署目标为 16.0；这一配置不代表已完成最低系统版本的运行验证。设备安装与应用启动按维护者指定流程进行。

## Android / iOS CI 与发布

在 Android Studio 中打开仓库根目录，选择 `app` 模块。需要 Android SDK 36；保持原有 application ID、偏好设置键和发布签名配置。Android 的所有编译和打包一律在 GitHub CI 进行，不在本地执行。

`.github/workflows/android-build.yml` 提供 `Build and Publish Android and iOS` 工作流：每次推送到 `main`，先解析统一的版本号、构建号和更新说明，再并行编译签名 Android Release APK 与未签名 iOS 真机 arm64 Release IPA；两端都成功后统一发布。也可在 `main` 手动触发。连续推送排队执行，不取消已在执行的构建；不执行测试。

安装包保存为保留 7 天的 Actions artifacts，并通过已有 GitHub App 上传到专用公开仓库 `snifferhub/SniffSubPlayer-Releases` 的同一个 GitHub Release：`SniffSubPlayer-Android.apk`、`SniffSubPlayer-iOS-unsigned.ipa` 和包含两者校验值的 `SHA256SUMS.txt`。先上传为草稿，所有文件上传完成后再公开并设为 Latest；任一平台构建失败时不发布。源码仓库仅新增对应版本标签，用于追踪后续更新说明的提交范围，不重复保存安装包。

构建号取工作流计数与已有公开 Release/源码标签最大构建号加一的较大值，避免工作流迁移、失败重试导致版本号回退或复用。发布标签继续采用 `android-v<versionName>-build.<build>`，APK `versionCode` 继续采用 `10000 + build`，与现有 Android 应用内更新逻辑兼容。iOS 的 `CFBundleShortVersionString` 与 Android 的 `versionName` 一致，`CFBundleVersion` 使用同一构建号。更新说明使用 git-cliff 从提交生成，包含两端和共享 Kotlin/Compose 改动；从旧 Android 分支切换到不同提交历史的 KMP main 时，首次说明基于当前分支历史生成。

iOS 使用标准 `macos-26` runner 和 JDK 21，通过 `.github/scripts/build_ios_ipa.sh` 构建 `iphoneos` 目标并打包 `Payload/SniffSubPlayer.app`。脚本校验真机平台、arm64 架构、版本号和共享嗅探资源。CI 不需要 Apple 开发者证书或账号；下载的 IPA 需要使用者通过侧载工具签名后安装，不能直接安装。模拟器产物不能作为侧载包使用。

沿用源码仓库的以下配置：

| 类型 | 名称 |
| --- | --- |
| Secrets | `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` |
| Secret | `ANDROID_UPDATES_APP_PRIVATE_KEY` |
| Variable | `ANDROID_UPDATES_APP_CLIENT_ID` |

GitHub App 需要安装在专用发布仓库并具有 Contents 写权限。构建后停止 Gradle 守护进程并删除临时签名文件；Kotlin 编译器在 Gradle 进程内执行，避免另留守护进程。


## 构建与验证记录

项目约定：不新增测试；原有测试文件保留，测试执行由维护者指定。Android 编译仅在 GitHub CI，iOS 可在本地或 CI 编译。CI 触发、Git 提交和推送须单独授权。

GitHub CI 中的 Android 编译入口（工作流注入签名环境变量和递增的构建号）：

```sh
./gradlew :app:assembleRelease -PreleaseBuildNumber="$RELEASE_BUILD_NUMBER" \
  -Pkotlin.compiler.execution.strategy=in-process --no-daemon
```

iOS 真机 Release IPA 打包（无需 Apple 开发者证书；版本号与构建号由 CI 注入）：

```sh
bash .github/scripts/build_ios_ipa.sh 1.1.0 33 /private/tmp/SniffSubPlayer-iOS-release
```

iOS 模拟器目标纯编译（Apple Silicon）：

```sh
xcodebuild -project iosApp/SniffSubPlayer.xcodeproj \
  -scheme SniffSubPlayer \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /private/tmp/SniffSubPlayerKmpDerivedData \
  build CODE_SIGNING_ALLOWED=NO ARCHS=arm64
```

工具链固定为 Kotlin 2.2.21、Compose Multiplatform 1.9.3、AGP 8.11.1、Gradle 8.14.3。原构建记录使用 Xcode 27.1，当前环境兼容性需以实际构建结果验证。编译成功不代表 WebView 手势、跨标签页媒体、Cookie 或真机播放已验证。

以下为原 README 保留的历史记录，本次文档调整未重新执行构建或运行验证。

2026-10-26：设置/收藏 UI 共享、iOS 偏好持久化迁移及重复 Swift 模型清理后，iOS arm64 模拟器目标本地构建通过，包含 Kotlin/Native framework、Swift 宿主及链接检查。共享嗅探脚本随应用资源打包。Android 最终改动待 GitHub CI 构建；未新增或运行测试，尚未启动应用验证，旧数据迁移与设置交互也尚未进行设备验证。Xcode 报告本机 CoreSimulator 服务版本落后于已安装 Xcode，且链接器对 ICU 数据对象的 17.2 构建版本与项目 iOS 16 部署目标给出警告；本轮没有修改系统组件或提高部署目标，iOS 16 运行兼容性仍待验证。


## 原生播放器实现备注

Android 使用 Media3，iOS 使用 AVPlayer。iOS 以自定义 `AVPlayerLayer` 承载画面，设置 `AVAudioSession.playback`，并在进入和退出播放器时管理横屏方向。播放器保留原生字幕叠层、字幕偏移与字号设置，以及控制条自动隐藏和点击唤出。

嗅探脚本从 `video/source/a`、`fetch`、`XMLHttpRequest` 和 `performance` 收集资源；候选分类包括 HLS、MP4、DASH、Segment、Blob 和 Other。资源识别与实际播放能力需要分别判断。

## 下一阶段

- TODO：重新设计 App 图标，并在设计完成后更新 README 首图。
- TODO：Android 多标签页浏览（标签栏、新建/切换/关闭、各页面独立历史与状态）；当前保持单会话、单 WebView。
- 将共享 Cookie 服务接入播放器请求，并验证媒体跨域、重定向和子资源行为。
- 将字幕搜索、播放器通用状态和广告规则更新流程逐步迁入共享层，原生播放与规则编译能力保留平台适配。
- 增加本地 `127.0.0.1` 代理，重写 m3u8 和分片 URL。
- 把原 userscript 的字幕样式预设、番号识别策略进一步搬进播放器。

