# SniffSubPlayer

Android / iOS 嗅探字幕播放器，正在从原生双端迁移到 Kotlin Multiplatform（KMP）与 Compose Multiplatform。

## 工程结构

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

历史模型保存 WebView / WKWebView 的实际前进后退列表快照；每个标签页有独立会话和原生视图。标签页目前仅在进程内保存；Android 继续恢复当前页面地址。

浏览器、设置、收藏夹、广告屏蔽设置 UI 已由两端共用 Compose 实现；浏览器状态、媒体候选模型、字幕解析与时间轴、UA/主题色/收藏修改逻辑位于共享 Kotlin 层。Android 保留原有应用导航和更新入口，iOS 通过共享 `AppScreen` 导航并隐藏 Android 更新入口。

iOS 首次读取时，将原有 `SniffSubPlayer.userAgent`、`SniffSubPlayer.playerAccentColor` 和 `SniffSubPlayer.bookmarks` 导入 Kotlin 管理的偏好数据，保留收藏 ID 与创建时间。若解码失败，保留原始数据并显示错误，不用空收藏覆盖旧数据。主题色继续同步到原生播放器使用的旧存储键。

旧 Swift `BrowserStore`、浏览器/设置/收藏列表 UI、重复媒体分类及收藏模型已删除。`iosApp` 当前仍含尚未迁移的功能代码，不能把目录整理当作完成迁移：播放器控件和字幕搜索仍是两端实现；广告设置 UI 与状态模型共享，规则下载、缓存、转换和应用仍由 Android 规则引擎及 iOS `AdBlockManager` 分别负责。iOS 转换依赖 `SafariConverterLib`，规则编译和应用依赖 WebKit。Cookie 接口及两端实现已提供，但播放器请求尚未接入 Cookie，也没有跨设备 Cookie 同步。

迁移确认可用后，初期计划只保留 `main`；`dev` 留待以后需要时建立。旧 Android worktree 已在确认源码与 GitHub `Android` 分支一致后按维护者要求删除；现有本地和远端分支尚未删除，远端发布策略不变。目录清理不代表迁移已验证可用。

## 当前能力

- Android WebView / iOS WKWebView 内置浏览器，共享地址栏、工具栏和多标签页 UI。
- 支持原生 UA 与自定义 UA 选项。
- 浏览器菜单支持收藏当前网页。
- 设置页二级收藏夹支持打开收藏网页和删除收藏。
- 注入 JS 嗅探 `video/source/a`、`fetch`、`XMLHttpRequest`、`performance` 资源。
- 候选资源按 `HLS` / `MP4` / `DASH` / `Segment` / `Blob` 分类。
- 手动点击扫描媒体资源，查看候选；Android 用 Media3、iOS 用 AVPlayer 播放。
- 支持本地 SRT/VTT 文件和粘贴字幕文本。
- 支持第三方字幕接口在线搜索，点选结果后直接下载应用。
- 支持在播放器内弹出 Subtitlecat 搜索页，自行下载后再导入字幕文件。
- KMP 共用 SRT/VTT 解析与时间轴，播放器保留原生字幕 overlay 和偏移、字号等设置。
- 使用自定义 `AVPlayerLayer`，避免系统播放器控制条和字幕控制条重叠。
- 进入播放器时设置 `AVAudioSession.playback`，避免 iPhone 静音开关导致无声。
- 进入播放器时自动切到横屏，退出播放器后恢复浏览器页方向。
- 播放时控制条会自动隐藏，点击视频画面可重新呼出。
- 设置页支持添加 AdGuard/EasyList raw 规则订阅，在设备上自动下载、转换并编译为 WebKit 内容拦截规则。
- 广告屏蔽支持总开关、每天自动更新、多个订阅、失败回退和规则统计。

## 使用

### iOS

先安装 JDK（当前使用 JDK 22）；首次构建还需要联网下载 Gradle/Kotlin Native/Compose 依赖。Xcode 构建阶段自动执行 `:composeApp:embedAndSignAppleFrameworkForXcode`，生成 `ComposeApp` framework 并复制共享资源。

1. 用 Xcode 打开 `iosApp/SniffSubPlayer.xcodeproj`。
2. 在 Signing & Capabilities 里选择自己的 Team。
3. 跑到 iPhone。
4. 在浏览器里打开站点并播放视频。
5. 在浏览器菜单点击“扫描媒体资源”，选择 `.m3u8` 或 `.mp4` 候选进入播放器。
6. 在播放器里导入字幕文件、粘贴 SRT/VTT，或点“在线字幕”搜索。
7. 如需网页广告屏蔽，在“设置 → 广告屏蔽”中添加 HTTPS raw 规则链接并点击“立即更新规则”。

### Android

在 Android Studio 中打开仓库根目录，选择 `app` 模块。需要 Android SDK 36；保持原有 application ID、偏好设置键和发布签名配置。Android 的所有编译和打包一律在 GitHub CI 进行，不在本地执行。

`.github/workflows/android-build.yml` 提供手动触发的 `Build Android KMP` 工作流，只生成 Debug APK 并上传 Actions artifact，不执行测试或发布 Release。工作流需要先经维护者提交到 GitHub 才能使用，本轮未触发。

## 验证

项目约定：不新增测试；原有测试文件保留，测试执行由维护者指定。Android 编译在 GitHub CI，iOS 编译暂时在本地。CI 触发、Git 提交和推送须单独授权。

GitHub CI 中的 Android 编译入口：

```sh
./gradlew :app:compileDebugKotlin
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

工具链固定为 Kotlin 2.2.21、Compose Multiplatform 1.9.3、AGP 8.11.1、Gradle 8.14.3。本机使用 Xcode 27.1，需以实际构建结果验证兼容性。编译成功不代表 WebView 手势、跨标签页媒体、Cookie 或真机播放已验证。

2026-10-26：设置/收藏 UI 共享、iOS 偏好持久化迁移及重复 Swift 模型清理后，iOS arm64 模拟器目标本地构建通过，包含 Kotlin/Native framework、Swift 宿主及链接检查。共享嗅探脚本随应用资源打包。Android 最终改动待 GitHub CI 构建；未新增或运行测试，尚未启动应用验证，旧数据迁移与设置交互也尚未进行设备验证。Xcode 报告本机 CoreSimulator 服务版本落后于已安装 Xcode，且链接器对 ICU 数据对象的 17.2 构建版本与项目 iOS 16 部署目标给出警告；本轮没有修改系统组件或提高部署目标，iOS 16 运行兼容性仍待验证。

## MVP 限制

- `blob:` 不能直接播放，需要选择其背后的 `.m3u8` / `.mp4` 候选。
- 当前还没有本地代理，Referer/Cookie 复杂防盗链资源可能播放失败。
- HLS 分片、key、相对路径重写会在下一阶段通过本地代理补齐。
- Subtitlecat 当前以内置网页弹窗打开；站内文件下载完成后，需要通过“字幕文件”导入。

## 下一阶段

- 将共享 Cookie 服务接入播放器请求，并验证媒体跨域、重定向和子资源行为。
- 将字幕搜索、播放器通用状态和广告规则更新流程逐步迁入共享层，原生播放与规则编译能力保留平台适配。
- 增加本地 `127.0.0.1` 代理，重写 m3u8 和分片 URL。
- 把原 userscript 的字幕样式预设、番号识别策略进一步搬进播放器。

## 致谢

**Linux.do 时光Alex**

规则转换使用 GPLv3 许可的 [AdguardTeam/SafariConverterLib](https://github.com/AdguardTeam/SafariConverterLib)。分发修改后的应用前请确认并遵守相关许可证要求。
