# SniffsubPlayer

此分支是 Flutter 多端版本；原生 Swift/SwiftUI iOS 工程保留在 `main` 分支。

SniffsubPlayer 是使用 Flutter 构建的跨平台网页媒体嗅探与字幕播放器。浏览器、播放器、字幕、收藏和广告过滤逻辑统一由 Dart 代码维护，Android 与 iOS 目录只保留必要的平台启动与权限配置。

## 功能

- 内置浏览器、地址输入、前进/后退、加载进度和收藏夹。
- 监听网页中的 `video`、`source`、`fetch`、XHR 与 Performance 资源，识别 HLS、MP4、DASH、分片和 Blob 候选。
- 横屏沉浸式播放器，支持双击快进/快退、滑动定位、亮度与音量手势。
- 导入或粘贴 SRT/VTT 字幕，支持字幕搜索、时间偏移、字号和位置调整。
- 内置 AdGuard 与 EasyList China GitHub 规则订阅，并允许添加自定义 HTTPS Raw 订阅。
- 收藏夹、User-Agent、播放器颜色及广告过滤设置本地保存。

## 目录

```text
.
├── .github/workflows/  GitHub Actions CI/CD
├── android/            Flutter Android 平台壳
├── assets/             应用图标等共享资源
├── ios/                Flutter iOS 平台壳
├── lib/
│   ├── models/         媒体、收藏和字幕模型
│   ├── screens/        浏览器、设置、播放器与字幕界面
│   ├── services/       嗅探、字幕与广告过滤服务
│   └── state/          浏览器状态与本地持久化
└── test/               核心逻辑与播放器界面测试
```

## 本地开发

需要 Flutter 3.47.4：

```sh
flutter pub get
flutter run
```

常用构建命令：

```sh
flutter build apk --release --split-per-abi --target-platform android-arm,android-arm64
flutter build ios --release --no-codesign
```

## 自动发布

每次推送到 `main` 后，[Build and Release](.github/workflows/release.yml) 会自动：

1. 安装锁定的 Flutter 依赖。
2. 分别构建 Android `armeabi-v7a`（ARMv7）、`arm64-v8a`（ARMv8）APK，并生成仅含 ARM 架构的 AAB；不支持 x86。
3. 在 macOS Runner 上构建未签名的 iOS App ZIP。
4. 创建 `v<版本>-build.<运行编号>` GitHub Release，并附带 SHA-256 校验文件。

流水线只负责编译和发布，不运行自动化测试；真机功能由维护者自行验证。

未配置 Android 正式签名时，流水线会回退到调试签名，适合内部安装测试。正式发布前，在 GitHub 仓库的 Actions Secrets 中配置：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`ANDROID_KEYSTORE_BASE64` 是 Release JKS 文件的 Base64 内容。其余三个值分别是仓库密码、Key Alias 和 Key 密码。签名文件和密码禁止提交到 Git。

iOS 产物默认未签名，不能直接安装到普通 iPhone。要生成可安装 IPA，需要在 CI 中另外配置 Apple Distribution 证书、Provisioning Profile 和相应 Secrets。

## 已知限制

- `blob:` URL 不能离开原网页直接播放，应优先选择对应的 M3U8 或 MP4 候选。
- 需要 Cookie、DRM 或站点专用签名的媒体可能无法直接播放。
- 广告过滤当前处理跨平台 WebView 可执行的网络规则；CSS 元素隐藏、脚本规则、例外规则和部分高级修饰符会跳过。
- Subtitlecat 下载的字幕文件仍需通过“字幕文件”导入。
