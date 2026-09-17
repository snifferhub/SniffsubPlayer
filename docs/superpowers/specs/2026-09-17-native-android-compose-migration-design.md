# SniffSubPlayer Native Android Compose Migration Design

**Date:** 2026-09-17  
**Target release:** 1.1.0 (`versionCode` 3)  
**Target branch:** `android`  
**Baseline:** Flutter v1.0.1 (`v1.0.1`)

## Purpose

Replace the Flutter Android implementation with a native Kotlin and Jetpack Compose application while preserving the current feature set, page structure, player control placement, and interaction model. The native application will become the primary Android implementation and provide a clean foundation for future Android-specific features.

The migration is not intended to reproduce Cupertino styling pixel for pixel. It will use Material 3 components and motion while keeping the existing information architecture, button positions, and user workflows recognizable.

## Repository and Branch Boundaries

- `main` remains the native Swift and SwiftUI iOS application.
- `flutter` remains the released Flutter Android v1.0.1 implementation and rollback reference.
- `android` starts from tag `v1.0.1` and becomes the native Kotlin and Compose implementation.
- The migration must not rewrite or delete `main`, `flutter`, or tag `v1.0.1`.
- The old Java and Media3 commit `5781176` may be consulted for known Android behavior, but its monolithic activities will not become the new architecture.

## Platform and Packaging Constraints

- Kotlin and Jetpack Compose with Material 3.
- Minimum supported version: Android 9, API 28.
- Application ID: `com.sniffsub.sniff_sub_player`.
- Version name: `1.1.0`.
- Version code: `3`.
- Java toolchain: Java 17.
- Release output: one `arm64-v8a` APK only.
- No ARMv7, x86, x86_64, AAB, Flutter Engine, Dart runtime, or Flutter plugin artifacts.
- Existing Flutter SharedPreferences data will not be migrated. The native application starts with empty native preferences.

## Architecture

The application will use a small, layered Android architecture rather than placing behavior in activities.

### Application shell

- A Compose-based browser activity owns the top-level browser and settings navigation.
- A separate player activity owns landscape orientation, immersive mode, Media3 lifecycle, subtitle overlay, and playback gestures.
- Navigation arguments contain a serializable media candidate with URL, type, source, page URL, Referer, and User-Agent.

### Presentation layer

- Compose screens render browser chrome, media candidates, settings, bookmarks, ad-block controls, player controls, subtitle controls, dialogs, and status messages.
- Screen state is exposed through immutable UI state objects.
- ViewModels coordinate repositories and platform controllers without parsing subtitles, matching ad rules, or classifying URLs themselves.
- The WebView and Media3 player remain Android platform views hosted by Compose where necessary.

### Domain and data layer

- Pure Kotlin models represent media candidates, bookmarks, subtitle cues, ad-block subscriptions, and user settings.
- Pure Kotlin services normalize addresses, classify media URLs, extract video identifiers, parse SRT/VTT, and parse supported ad-block rules.
- DataStore persists bookmarks, selected User-Agent, player accent color, ad-block enabled state, subscription configuration, and the last successfully compiled rule set.
- Network clients download subtitle results and ad-block subscriptions with explicit timeouts and recoverable errors.

## Browser Screen

The browser screen retains the Flutter v1.0.1 layout:

1. A top app bar with bookmark action, one-line page title, and settings action.
2. An address row with back, forward, URL/search entry, and submit behavior.
3. A thin page-loading progress indicator.
4. The WebView filling the primary content area.
5. The media candidate panel at the bottom.

Address input behavior remains unchanged:

- Fully qualified URLs load directly.
- Domain-like values receive `https://`.
- Other text opens a Google search query.

WebView state tracks page title, current URL, progress, loading status, back/forward availability, current User-Agent, and whether the media sniffer is enabled. Browser and renderer errors appear as recoverable UI status rather than crashing the application.

## Media Sniffing

The native WebView injects a guarded JavaScript sniffer only after the user enables it. The script retains the current detection sources:

- `video`, `source`, and relevant anchor elements;
- Fetch requests and responses;
- XMLHttpRequest URLs;
- Performance resource entries;
- video load and playback events.

The JavaScript bridge accepts only the expected structured payload and passes it to native code. Native code normalizes, validates, classifies, and deduplicates candidates by URL. Candidate types remain HLS, MP4, DASH, segment, Blob, and other.

The bridge does not execute commands supplied by pages. Invalid or empty payloads are ignored. Navigating to a new page resets the enabled-sniffer state consistently with Flutter v1.0.1.

## Player

Media3 ExoPlayer is the playback engine. Media requests propagate the detected User-Agent and page Referer. HLS, progressive MP4-compatible media, and DASH use Media3 data sources. Blob candidates display the existing unsupported-link explanation and do not attempt playback outside the originating page.

The player starts in landscape immersive mode, keeps the screen awake while active, and restores orientation, system UI, brightness, and other transient state when closed.

The existing control placement is preserved:

- close button at the upper left;
- status text beside the close action;
- subtitle action at the upper right;
- elapsed time, progress control, and duration along the bottom;
- rewind 10 seconds, play/pause, and forward 10 seconds centered above the progress row.

The existing interactions are preserved:

- single tap toggles controls;
- controls auto-hide during playback;
- double tap on the left or right seeks backward or forward 10 seconds;
- horizontal drag previews and commits a seek;
- vertical drag on the left adjusts application brightness;
- vertical drag on the right adjusts media volume;
- interactions provide concise visual feedback and haptics where available.

Playback preparation, buffering, failure, and retry states are represented explicitly. Invalid URLs and unsupported Blob links do not initialize Media3.

## Subtitles

Subtitles remain a custom overlay so offset, size, and vertical position behave like the Flutter baseline independently of Media3 track styling.

Supported flows:

- import one SRT or VTT file through Android's document picker;
- paste SRT or VTT text and apply it;
- derive an initial search term from the page or media URL;
- search the existing online subtitle service;
- open Subtitlecat in a browser flow when needed;
- adjust subtitle timing by 0.5-second steps;
- adjust font size and vertical position.

Subtitle parsing is pure Kotlin. It handles byte-order marks, CRLF input, SRT commas, VTT decimal timestamps, cue identifiers, inline markup removal, multiline text, ordering, and invalid blocks. File access uses content URIs and does not require broad storage permissions.

## Settings and Persistence

The Material 3 settings UI retains the existing sections and actions:

- User-Agent selection;
- player accent color;
- bookmarks with open and delete actions;
- ad-block enabled state;
- built-in and custom subscription management.

DataStore is the source of truth. Downloads or parsing failures never overwrite the last valid ad-block rules. Removing a bookmark or subscription updates persisted state atomically.

## Ad Blocking

Ad blocking operates through WebView request interception. The native rule engine supports the same practical subset used by Flutter v1.0.1 and deliberately skips unsupported cosmetic, script, exception, and advanced modifier syntax rather than interpreting it incorrectly.

Built-in subscriptions and user-added HTTPS raw subscriptions are refreshed off the main thread. Only HTTPS custom URLs are accepted. Responses must be successful and bounded in size before parsing. The last valid compiled rules remain active if a refresh fails.

## Error Handling

- Browser errors update loading state and present a retryable message.
- Malformed JavaScript bridge payloads are ignored and recorded only in debug logging.
- Network timeouts and non-success responses produce concise user-facing status.
- Failed ad-rule refreshes retain the previous rules.
- Invalid subtitle blocks are skipped; a file with no valid cues reports a useful error.
- Player errors remain visible while controls are shown and permit closing or retrying.
- Activity teardown always releases Media3 and restores temporary system settings.

## Testing Strategy

Implementation follows red-green-refactor for behavior-bearing Kotlin code.

JVM unit tests cover:

- address normalization;
- media type classification and candidate deduplication;
- video identifier extraction;
- SRT and VTT parsing, offsets, invalid blocks, and markup removal;
- supported ad-rule parsing and matching;
- repository state transitions and preservation of last valid rules;
- player seek clamping and subtitle cue selection;
- settings serialization where custom serializers are used.

Compose tests that can run in the build environment cover the presence, order, enabled state, and state transitions of important controls. Code is structured so tests do not require a real website, physical device, emulator session, or real media stream.

Required verification before completion:

1. JVM unit tests pass.
2. Build-environment-compatible Compose tests pass.
3. Android Lint passes with no release-blocking findings.
4. The Release APK builds successfully.
5. APK inspection confirms application ID, version, arm64-only native contents, and absence of Flutter artifacts.
6. Signing verification reports the expected release certificate.
7. A SHA-256 checksum is generated for the APK.

Physical-device, emulator, real-site, and real-media playback testing are explicitly owned by the user and are not part of automated completion claims.

## CI and Release

GitHub Actions listens to pushes on `android` and manual dispatches. It installs Java and the Android/Gradle toolchain, runs unit tests and Lint, builds one signed arm64-v8a Release APK, verifies the package, and publishes the APK with its SHA-256 file.

Release signing uses the existing secret names:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

All four signing values are mandatory for a published Release. The workflow fails clearly when they are missing and never falls back to an ephemeral debug certificate. Signing files and credentials remain excluded from Git.

## Completion Criteria

The migration is complete when:

- the native project contains no Flutter runtime or Dart production dependency;
- every Flutter v1.0.1 feature has a native entry point with the same page and button layout;
- Material 3 styling is coherent across browser, settings, candidates, player, and subtitle UI;
- the required automated checks pass;
- a signed, arm64-only 1.1.0 APK is produced and verified;
- the APK path, byte size, ABI result, signing certificate, and SHA-256 are reported;
- `main`, `flutter`, and `v1.0.1` remain unchanged;
- no claim is made that real-site or real-device playback was tested.

## Out of Scope

- iOS changes.
- Flutter data migration.
- Play Store AAB publishing.
- ARMv7 or x86 builds.
- DRM playback or bypassing protected media systems.
- Site-specific authentication or signing workarounds.
- Pixel-perfect reproduction of Cupertino visuals.
- Physical-device, emulator, real-site, or real-media validation by Codex.
