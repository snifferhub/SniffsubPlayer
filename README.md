# SniffSubPlayer Android

这是 SniffSubPlayer 的原生 Android 分支，使用 Kotlin、Jetpack Compose、WebView 与 Media3。后续 Android 功能以这里为主，不再把 Flutter 目录作为 Android 功能实现来源。

## 功能

- 内置浏览器、地址输入、前进/后退、加载进度和收藏夹。
- 底部 Liquid Glass 导航切换浏览器与设置，右侧蓝色雷达按钮主动嗅探，资源列表以可拖动的底部面板展开。
- 用户启用嗅探后监听网页中的 `video`、`source`、`fetch`、XHR 与 Performance 资源，识别 HLS、MP4、DASH、分片和 Blob 候选。
- 横屏沉浸式播放器，支持双击快进/快退、滑动定位、亮度与音量手势。
- 导入或粘贴 SRT/VTT 字幕，支持字幕搜索、时间偏移、字号和位置调整。
- 内置 AdGuard 与 EasyList China GitHub 规则订阅，并允许添加自定义 HTTPS Raw 订阅。
- 收藏夹、User-Agent、播放器颜色及广告过滤设置本地保存。
- 设置内手动检查 GitHub Android 正式更新，应用内下载、校验并交由系统安装，支持下载进度、取消、重试和恢复。

## 浏览器交互

加载网页后点击蓝色雷达按钮，扫描当前页面的媒体元素和已加载资源，并开始监听后续媒体请求。资源面板支持重新扫描、清空和点击播放；关闭面板后仍收集当前页资源，切换网页后自动停止嗅探并清空旧候选。进入设置、播放器或应用后台时隐藏/暂停浏览器并停止嗅探轮询，返回后恢复同一个 WebView，保留网页与历史。输入网址时隐藏底部导航，播放器保持全屏。

玻璃效果使用 `io.github.kyant0:backdrop:1.0.0`（Apache-2.0）；Android 13+ 使用折射与模糊，Android 12 使用模糊，Android 9–11 使用高不透明度染色与高光。浏览器页使用主题渐变作为玻璃背景，不再实时录制原生 WebView；设置页仍采样 Compose 内容。网页视口为底部导航预留空间，避免原生网页与导航栏争抢触摸。实际外观和 Android 16 切页行为仍需由维护者在真机上验证。

## 应用内更新

设置 → 检查更新 → 下载更新 → 安装更新。首次安装可能需要允许 SniffSubPlayer 安装未知应用，再由 Android 系统确认覆盖安装。只选择本仓库 Android 正式 Release 中的 `SniffSubPlayer-Android.apk`；下载后检查文件大小、SHA-256、包名、递增版本号与当前应用签名。Debug 包签名不同，不能通过此流程替换成正式包。

下载使用系统 DownloadManager，文件存放在应用专属目录，不需要存储权限；关闭弹窗仍继续下载，应用重新打开后恢复任务并重新校验。安装被取消或文件被清理时，可以再次安装或重新下载。此功能加入前的旧包需要手动安装一次新包，此后可在设置中检查更新。

CI 将运行编号写入 `BuildConfig.RELEASE_BUILD_NUMBER`，发布包 `versionCode = 10000 + 运行编号`，以区分同一 `versionName` 的不同构建。更新检查按版本号及构建编号比较；今后须维持该工作流运行编号递增，避免重置后产生版本号回退。

## 原生目录

```text
.
├── .github/workflows/  GitHub Actions CI/CD
├── app/src/main/kotlin/  Compose 界面、WebView、Media3、字幕和广告过滤
├── app/src/test/         JVM 单元测试与 Compose 合约测试
├── app/src/main/assets/  WebView 嗅探脚本
└── gradle/               Gradle Wrapper
```

## 本地开发

需要 JDK 17、Android SDK 36：

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
node --test app/src/test/js/cosmetic-filtering.test.cjs
```

APK 编译、签名和发布通过 GitHub Actions 完成，不在本地组装 APK。

## 自动发布

每次推送 Android 发布分支后，[Build and Release](.github/workflows/release.yml) 可自动：

1. 使用 Gradle 构建原生 Compose Android APK。
2. 生成 `arm64-v8a` APK；当前应用配置不包含 x86。
3. 创建 `android-v<版本>-build.<运行编号>` GitHub Release，并附带 SHA-256 校验文件。

流水线只负责编译和发布，不运行自动化测试；真机功能由维护者自行验证。

流水线要求配置正式签名。请在 GitHub 仓库的 Actions Secrets 中配置：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`ANDROID_KEYSTORE_BASE64` 是 Release JKS 文件的 Base64 内容。其余三个值分别是仓库密码、Key Alias 和 Key 密码。签名文件和密码禁止提交到 Git。

## 已知限制

- `blob:` URL 不能离开原网页直接播放，应优先选择对应的 M3U8 或 MP4 候选。
- 需要 Cookie、DRM 或站点专用签名的媒体可能无法直接播放。
- 广告过滤支持 URL/域名/路径规则、通配符与锚定、例外、资源类型、`domain`、`third-party`、`match-case`、`important`，以及普通 CSS 元素隐藏及其例外。脚本规则、重定向规则与扩展 CSS 等未支持的语法仍会跳过，避免错误扩大拦截范围。
- WebView 不提供完整的请求类型信息：优先使用请求头，再使用扩展名推断；无法判断的请求作为 `other` 处理，不套用特定类型规则。CSS 隐藏应用于主文档，动态新增的匹配元素也会隐藏，但不覆盖独立跨域 iframe 内部。
- 规则按订阅分别缓存；部分下载失败不会丢弃其他来源的新规则，失败来源保留缓存。开启内置订阅且没有缓存时提供少量离线广告域名规则。升级后会重新下载以迁移旧的正则快照；自动更新关闭时，请手动“立即更新规则”。
- Subtitlecat 下载的字幕文件仍需通过“字幕文件”导入。
