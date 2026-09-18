# SniffSubPlayer Android

这是 SniffSubPlayer 的原生 Android 分支，使用 Kotlin、Jetpack Compose、WebView 与 Media3。后续 Android 功能以这里为主，不再把 Flutter 目录作为 Android 功能实现来源。

## 功能

- 内置浏览器、地址输入、前进/后退、加载进度和收藏夹。
- 监听网页中的 `video`、`source`、`fetch`、XHR 与 Performance 资源，识别 HLS、MP4、DASH、分片和 Blob 候选。
- 横屏沉浸式播放器，支持双击快进/快退、滑动定位、亮度与音量手势。
- 导入或粘贴 SRT/VTT 字幕，支持字幕搜索、时间偏移、字号和位置调整。
- 内置 AdGuard 与 EasyList China GitHub 规则订阅，并允许添加自定义 HTTPS Raw 订阅。
- 收藏夹、User-Agent、播放器颜色及广告过滤设置本地保存。

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
./gradlew assembleDebug
```

常用构建命令：

```sh
./gradlew assembleRelease
```

## 自动发布

每次推送 Android 发布分支后，[Build and Release](.github/workflows/release.yml) 可自动：

1. 使用 Gradle 构建原生 Compose Android APK。
2. 生成 `arm64-v8a` APK；当前应用配置不包含 x86。
3. 创建 `v<版本>-build.<运行编号>` GitHub Release，并附带 SHA-256 校验文件。

流水线只负责编译和发布，不运行自动化测试；真机功能由维护者自行验证。

未配置 Android 正式签名时，流水线会回退到调试签名，适合内部安装测试。正式发布前，在 GitHub 仓库的 Actions Secrets 中配置：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`ANDROID_KEYSTORE_BASE64` 是 Release JKS 文件的 Base64 内容。其余三个值分别是仓库密码、Key Alias 和 Key 密码。签名文件和密码禁止提交到 Git。

## 已知限制

- `blob:` URL 不能离开原网页直接播放，应优先选择对应的 M3U8 或 MP4 候选。
- 需要 Cookie、DRM 或站点专用签名的媒体可能无法直接播放。
- 广告过滤当前处理跨平台 WebView 可执行的网络规则；CSS 元素隐藏、脚本规则、例外规则和部分高级修饰符会跳过。
- Subtitlecat 下载的字幕文件仍需通过“字幕文件”导入。
