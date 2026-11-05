<!-- <p align="center">
  <img src="iosApp/SniffSubPlayer/Assets.xcassets/AppIcon.appiconset/AppIcon.png" width="112" height="112" alt="SniffSubPlayer golden dog app icon">
</p> -->

<h1 align="center">SniffSubPlayer</h1>

<p align="center">
  <strong>Find web videos. Bring your own subtitles.</strong><br>
  An Android / iOS player with a built-in browser, media detection, and external subtitles.
</p>

<p align="center">
  <strong>English</strong> · <a href="README.zh-CN.md">简体中文</a> · <a href="LICENSE">GPL-3.0-only</a>
</p>

<p align="center">
  <a href="https://github.com/snifferhub/SniffSubPlayer-Releases/releases/latest"><strong>Download for Android / iOS</strong></a>
  &nbsp; · &nbsp;
  <a href="#getting-started">Getting started</a>
  &nbsp; · &nbsp;
  <a href="docs/DEVELOPMENT.md">Development guide (Chinese)</a>
</p>

<p align="center">
  Android 9+ · arm64 &nbsp; / &nbsp; iOS 16+ · unsigned IPA &nbsp; / &nbsp; KMP migration in progress
</p>

---

Open a video page in the built-in browser, scan for media resources, and play a selected resource in the native player. Import SRT / VTT subtitle files, paste subtitle text, or search online and apply a result to the current video.

| Browse | Find media | Add subtitles |
| :--- | :--- | :--- |
| Open sites, bookmark pages, and change the user agent or ad-blocking settings as needed. | Scan for media candidates and select a resource such as `.m3u8` or `.mp4` to play. | Import a file, paste text, or search online; iOS also offers subtitle timing and font size controls. |

> **Current status:** Each release is configured to provide an Android APK and an unsigned iOS IPA for signing with a sideloading tool. Migration to Kotlin Multiplatform and Compose Multiplatform is ongoing; feature availability and runtime verification differ between platforms.

## Getting started

1. **Open a video page.** Enter a URL in the app, then start playback on the page so it loads its media resources.
2. **Scan for media.** On Android, tap the round media detection button at the bottom right. On iOS, choose “扫描媒体资源” (Scan media resources) from the browser menu.
3. **Choose a resource.** Try an `.m3u8` or `.mp4` candidate first to open it in the native player.
4. **Add subtitles.** Choose a subtitle file, paste SRT / VTT text, or open “在线字幕” (Online subtitles) to search and apply a result.

To find subtitles on Subtitlecat, open its website from Online subtitles, download a file, then import it using “字幕文件” (Subtitle file).

## Installation

### Android

Open the **[latest release](https://github.com/snifferhub/SniffSubPlayer-Releases/releases/latest)** and download `SniffSubPlayer-Android.apk` from Assets, then install it.

- **Requirements:** Android 9 (API 28) or later, ARM64.
- **Checksums:** Each release includes `SHA256SUMS.txt`.
- **Release notes:** Each release lists the changes included in that version.

### iOS

Open the **[latest release](https://github.com/snifferhub/SniffSubPlayer-Releases/releases/latest)** and download `SniffSubPlayer-iOS-unsigned.ipa` from Assets. Sign and install it with a sideloading tool such as [AltStore Classic](https://faq.altstore.io/altstore-classic/your-altstore). The IPA has no developer signature and cannot be installed directly. Apps signed with a free Apple account need periodic refreshing. `SHA256SUMS.txt` contains checksums for both the APK and IPA.

You can also open `iosApp/SniffSubPlayer.xcodeproj`, select your own signing team, and build from source. See **[Building iOS locally](docs/DEVELOPMENT.md#ios-本地构建)** in the Chinese development guide for dependencies and build instructions.

## Features

| Feature | Usage and current scope |
| :--- | :--- |
| Web browsing | Android WebView / iOS WKWebView, with platform-default and custom user agents. Android currently uses a single page; iOS supports tabs. |
| Media detection | Finds candidates including HLS, MP4, DASH, segments, and Blob URLs. Detection does not guarantee that the player can play a resource. |
| Native playback | Media3 on Android and AVPlayer on iOS, with playback controls that hide automatically. |
| External subtitles | Import or paste SRT / VTT and search online. iOS also supports subtitle timing and font size adjustments. |
| Bookmarks | Bookmark the current page from the browser menu; open or remove bookmarks in Settings → Bookmarks. |
| Ad blocking | Manage AdGuard / EasyList rule subscriptions in Settings → Ad blocking, with a master switch, daily automatic updates, and rule statistics. |

## Known limitations

- **Blob URLs and segments are not complete videos.** A `blob:` URL cannot be passed directly to the native player. Look for the underlying `.m3u8`, `.mp4`, or another complete media resource.
- **Some sites may not play.** Player requests do not yet use the browser Cookie service, and there is no local media proxy. Complex hotlink protection, cross-origin requests, and redirects still need work.
- **Android tabs are not implemented yet.** Android currently uses a single session and WebView. iOS tabs are not restored across app processes.
- **Online subtitles depend on third-party services.** Subtitlecat opens as a website; downloaded subtitle files must be imported manually.
- **Migration is ongoing.** The iOS deployment target is 16.0; compatibility with that minimum version and migration of existing user data still need device verification. See the historical [build and verification records](docs/DEVELOPMENT.md#构建与验证记录) in the Chinese development guide.

## Contributing

Both platforms live in this repository: `shared` owns shared business logic and platform services, `composeApp` owns shared UI, and `app` / `iosApp` host Android and iOS respectively.

The Chinese **[development and maintenance guide](docs/DEVELOPMENT.md)** covers the project structure, migration boundaries, build commands, signing and release configuration, and planned work. Read the **[repository rules](AGENTS.md)** before making changes: Android compilation runs only in GitHub CI; iOS can be built locally or in CI; tests and device verification follow the maintainer's requested procedure.

## Acknowledgments and license

Thanks to **时光Alex on Linux.do**.

iOS ad-blocking rule conversion uses [AdguardTeam/SafariConverterLib](https://github.com/AdguardTeam/SafariConverterLib). See its [upstream license](https://github.com/AdguardTeam/SafariConverterLib/blob/master/LICENSE).

This project is licensed under the [GNU General Public License v3.0](LICENSE) (`GPL-3.0-only`). Third-party dependencies retain their own licenses.
