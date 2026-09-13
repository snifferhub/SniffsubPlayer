# SniffSubPlayer

自用 iOS 嗅探字幕播放器 MVP。

## 当前能力

- WKWebView 内置浏览器。
- 默认 iPhone Safari UA，设置页支持切换 iPad Safari / macOS Safari。
- 主页面左上角支持一键收藏当前网页，并带振动和灰色 HUD 提示。
- 设置页二级收藏夹支持打开收藏网页和删除收藏。
- 注入 JS 嗅探 `video/source/a`、`fetch`、`XMLHttpRequest`、`performance` 资源。
- 候选资源按 `HLS` / `MP4` / `DASH` / `Segment` / `Blob` 分类。
- 选中候选后用 `AVPlayer` 播放。
- 支持本地 SRT/VTT 文件和粘贴字幕文本。
- 支持第三方字幕接口在线搜索，点选结果后直接下载应用。
- 支持在播放器内弹出 Subtitlecat 搜索页，自行下载后再导入字幕文件。
- Swift 自绘字幕 overlay，支持偏移、字号和底部距离微调。
- 使用自定义 `AVPlayerLayer`，避免系统播放器控制条和字幕控制条重叠。
- 进入播放器时设置 `AVAudioSession.playback`，避免 iPhone 静音开关导致无声。
- 进入播放器时自动切到横屏，退出播放器后恢复浏览器页方向。
- 播放时控制条会自动隐藏，点击视频画面可重新呼出。
- 设置页支持添加 AdGuard/EasyList raw 规则订阅，在设备上自动下载、转换并编译为 WebKit 内容拦截规则。
- 广告屏蔽支持总开关、每天自动更新、多个订阅、失败回退和规则统计。

## 使用

1. 用 Xcode 打开 `SniffSubPlayer.xcodeproj`。
2. 在 Signing & Capabilities 里选择自己的 Team。
3. 跑到 iPhone。
4. 在浏览器里打开站点并播放视频。
5. 底部候选列表出现 `.m3u8` 或 `.mp4` 后点击进入播放器。
6. 在播放器里导入字幕文件、粘贴 SRT/VTT，或点“在线字幕”搜索。
7. 如需网页广告屏蔽，在“设置 → 广告屏蔽”中添加 HTTPS raw 规则链接并点击“立即更新规则”。

## 验证

已用下面命令做过模拟器 Debug 编译：

```sh
xcodebuild -project SniffSubPlayer.xcodeproj \
  -scheme SniffSubPlayer \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /private/tmp/SniffSubPlayerDerivedData \
  build CODE_SIGNING_ALLOWED=NO
```

## MVP 限制

- `blob:` 不能直接播放，需要选择其背后的 `.m3u8` / `.mp4` 候选。
- 当前还没有本地代理，Referer/Cookie 复杂防盗链资源可能播放失败。
- HLS 分片、key、相对路径重写会在下一阶段通过本地代理补齐。
- Subtitlecat 当前以内置网页弹窗打开；站内文件下载完成后，需要通过“字幕文件”导入。

## 下一阶段

- 读取 `WKHTTPCookieStore`，给播放候选补 Cookie。
- 增加本地 `127.0.0.1` 代理，重写 m3u8 和分片 URL。
- 把原 userscript 的字幕样式预设、番号识别策略进一步搬进播放器。

## 致谢

**Linux.do 时光Alex**

规则转换使用 GPLv3 许可的 [AdguardTeam/SafariConverterLib](https://github.com/AdguardTeam/SafariConverterLib)。分发修改后的应用前请确认并遵守相关许可证要求。
