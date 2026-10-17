# SniffSubPlayer Android

这是 SniffSubPlayer 的原生 Android 分支，使用 Kotlin、Jetpack Compose、WebView 与 Media3。后续只维护原生 Android 版本，应用代码统一位于 `app/`，构建入口为根目录的 Gradle 工程。

## 功能

- 内置浏览器、地址输入、前进/后退、刷新、加载进度和收藏夹。
- 底部 Liquid Glass 导航切换浏览器与设置，右侧蓝色雷达按钮主动嗅探，资源列表以可拖动的底部面板展开。
- 用户启用嗅探后监听网页中的 `video`、`source`、`fetch`、XHR 与 Performance 资源，识别 HLS、MP4、DASH、分片和 Blob 候选。
- 横屏沉浸式播放器，支持双击快进/快退、左右滑动定位及长按临时 2 倍速播放。
- 导入或粘贴 SRT/VTT 字幕，支持字幕搜索、时间偏移、字号和位置调整。
- 内置 AdGuard 与 EasyList China GitHub 规则订阅，并允许添加自定义 HTTPS Raw 订阅。
- 收藏夹、User-Agent、播放器颜色及广告过滤设置本地保存。
- 设置内手动检查 GitHub Android 正式更新，应用内下载、校验并交由系统安装，支持下载进度、取消、重试和恢复。

## 浏览器交互

加载网页后点击蓝色雷达按钮，每次最多扫描当前页面 8 秒，收集媒体元素、已加载资源及这段时间内的媒体请求；不会自动开启或无限轮询。资源面板支持重新扫描、清空和点击播放。地址提交、刷新、网页跳转、单页网站的 URL 变化都会停止嗅探并清空旧候选；每次请求带页面地址与扫描编号，拒绝旧页面或旧扫描的延迟回调。单页网站只扫描本次路由切换之后的 Performance 资源。进入设置、播放器或应用后台时停止嗅探，返回后需要再次主动点击。

返回浏览器时保留同一个 WebView，重新请求布局和 Chromium 可绘制帧；浏览器不再使用整页透明度图层隐藏。播放器使用在视图层级内合成的 TextureView，关闭前先解绑视频输出，销毁时释放播放器，避免独立 Surface 与 Compose 转场交叠。播放器路由取消淡出，切换时不反复改变窗口的 decorFitsSystemWindows。进程重建后恢复页面地址，播放器候选也可恢复；不会自动恢复嗅探。输入网址时隐藏底部导航，播放器保持全屏。

玻璃效果沿用 `io.github.kyant0:backdrop:1.0.0`（Apache-2.0），导航浮在网页上，外部背景透明，不再预留底部白块。浏览器仅采样导航后方最多 128dp 高、512 像素宽的网页条带，通过现有玻璃库模糊、折射并低透明度染色；按绘制和滚动事件触发、跟随屏幕帧调度，正常目标约每秒 30 次；采样较慢时自动降频，静止且无绘制事件时不轮询，相同画面复用临时位图且不重复发布。导航隐藏、资源面板打开或浏览器不活跃时停止采样。设置页仍采样 Compose 内容。Android 9–11 采用透明染色与高光降级；软件网页截图不包含单独的视频 Surface。真实外观、功耗和 Android 16 白屏复现仍由维护者在真机上验证。

## 播放器手势

单击画面显示或隐藏控制栏，双击左半边后退 10 秒、右半边前进 10 秒。左右滑动时预览目标时间及进度条，松手后执行跳转；划过整个画面的距离对应最多 2 分钟，短视频按实际时长计算。时长未知或不可跳转的直播不启用滑动定位。

正在播放时长按视频画面临时切换为 2 倍速，松手、手势取消、切到后台、打开字幕页或退出播放器时恢复原速。暂停时长按不自动开始播放。播放按钮、字幕按钮及底部进度条仍各自响应操作。

## 应用内更新

设置 → 检查更新 → 下载更新 → 安装更新。首次安装可能需要允许 SniffSubPlayer 安装未知应用，再由 Android 系统确认覆盖安装。只选择公开更新仓库 `snifferhub/SniffSubPlayer-Android-Releases` 的 Android 正式 Release 中的 `SniffSubPlayer-Android.apk`；下载后检查文件大小、SHA-256、包名、递增版本号与当前应用签名。Debug 包签名不同，不能通过此流程替换成正式包。

下载使用系统 DownloadManager，文件存放在应用专属目录，不需要存储权限；关闭弹窗仍继续下载，应用重新打开后恢复任务并重新校验。安装被取消或文件被清理时，可以再次安装或重新下载。此功能加入前的旧包需要手动安装一次新包，此后可在设置中检查更新。

更新包固定保存在 `getExternalFilesDir(null)/updates/update.apk`，主用户通常对应 `/storage/emulated/0/Android/data/com.sniffsub.sniff_sub_player/files/updates/update.apk`，不在公共 Download 目录。每次下载前删除旧包，不按版本累积；取消、下载失败或校验失败时清理文件与下载记录。升级成功后，新版首次启动会清理已经安装的更新包；取消系统安装时保留一份，以便再次安装。

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

源码仓库保持私有；公开更新仓库只保存签名 APK、版本信息、校验文件与发布工作流。私有 CI 使用组织所属 GitHub App 的短期安装 Token 推送安装包快照，提交的作者、提交者及推送认证均使用 bot 身份；公开仓库自己的工作流使用其 `GITHUB_TOKEN` 创建 Release。APK 不包含访问私有仓库的凭据。

发布 bot 仅安装到 `SniffSubPlayer-Android-Releases`，授予 Contents 读写和自动附带的 Metadata 只读权限，不授权私有源码仓库。源码仓库的 Actions Variable `ANDROID_UPDATES_APP_CLIENT_ID` 保存 App Client ID，Actions Secret `ANDROID_UPDATES_APP_PRIVATE_KEY` 保存私钥；工作流生成只针对公开更新仓库、有效期一小时的 Token，任务结束后撤销。请完成 App 安装和变量/Secret 配置后再启用此流程，不再使用个人 PAT 推送公开快照。


每次推送 Android 发布分支后，[Build and Release](.github/workflows/release.yml) 可自动：

1. 使用 Gradle 构建原生 Compose Android APK。
2. 生成 `arm64-v8a` APK；当前应用配置不包含 x86。
3. 创建 `android-v<版本>-build.<运行编号>` GitHub Release，并附带 SHA-256 校验文件。

流水线只负责编译和发布，不运行自动化测试；真机功能由维护者自行验证。

CI 使用 [git-cliff](https://git-cliff.org/) 自动整理上次公开 APK 发布以来的 commit 信息，生成更新说明，无需维护 `release-notes/android.md`，也不调用 AI。完整历史与公开 Release 决定范围，因此 `[skip ci]` 的提交、被取消的构建及未完成公开发布的改动会在下一次发布中一起收录。公开发布的标签必须存在于源码历史并属于当前构建的祖先；不满足时停止发布，避免生成错误范围的说明。首次发布收录 Android 代码历史。

分类和格式由 `.github/cliff.toml` 控制，只收录 Android 生产代码、资源及构建依赖相关提交；文档、测试和 CI 等维护提交默认省略，只有维护调整的发布使用简短说明。CI Summary 展示生成结果，私有 Release 与公开仓库的 `distribution/release-notes.md` 使用同一份内容，App 继续读取公开 Release 正文。

更新日志直接使用 commit 描述，不自动翻译或分析代码行为。建议使用清楚的中文 Conventional Commit 标题，例如 `fix(player): 修复拖动进度后重复出现快进提示`、`feat(player): 支持分区滑动调节亮度和音量`。生成内容不带作者、邮箱、私有源码链接及 `[skip ci]` 标记。只需纠正已发布版本的说明时，可以编辑公开 Release 正文，无需重新构建 APK。

发布说明的本地回归检查：`python3 -m unittest discover -s .github/scripts -p 'test_*.py'`；提供 git-cliff 2.13.1 时还会验证实际生成结果。旧的 `release-notes/android.md` 仅保留为历史资料，不再参与发布。

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
