# Native Android Compose Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Flutter v1.0.1 with a native Kotlin, Jetpack Compose, Material 3, and Media3 Android 9+ application that preserves the current feature flows and produces one signed arm64-v8a APK.

**Architecture:** A Compose browser activity hosts Android WebView and navigates to settings screens, while a dedicated landscape player activity owns Media3 and player gestures. Pure Kotlin domain services handle URL normalization, media classification, subtitle parsing, ad-rule compilation, and player calculations; repositories isolate DataStore and network access so behavior can be tested on the JVM.

**Tech Stack:** Kotlin 2.2.20, Java 17, Android Gradle Plugin 8.11.1, Gradle 8.14.3, compile/target SDK 36, min SDK 28, Compose BOM 2025.08.01, Material 3, Media3 1.11.1, DataStore 1.1.7, Kotlin coroutines 1.10.2, Kotlin serialization 1.9.0, JUnit 4, Robolectric 4.16.1.

**Spec:** `docs/superpowers/specs/2026-09-17-native-android-compose-migration-design.md`

## Global Constraints

- Work only on branch `android`; do not modify `main`, `flutter`, or tag `v1.0.1`.
- Keep application ID `com.sniffsub.sniff_sub_player`.
- Set `versionName = "1.1.0"` and `versionCode = 3`.
- Support Android 9/API 28 and newer; compile and target API 36.
- Preserve Flutter v1.0.1 page structure, control placement, labels, and workflows while using Material 3 visuals.
- Produce one `arm64-v8a` Release APK; do not produce ARMv7, x86, x86_64, or AAB artifacts.
- Do not implement Flutter SharedPreferences migration.
- Do not run emulator, physical-device, real-site, or real-media tests.
- Do not add DRM bypasses or site-specific authentication/signature workarounds.
- Write behavior tests first and observe the expected failure before adding production behavior.
- Use a fixed CI release keystore; never publish an APK signed by an ephemeral debug key.

## Planned File Structure

```text
.
├── .github/workflows/release.yml
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/sniffer.js
│       │   ├── kotlin/com/sniffsub/sniff_sub_player/
│       │   │   ├── MainActivity.kt
│       │   │   ├── SniffSubPlayerApplication.kt
│       │   │   ├── AppContainer.kt
│       │   │   ├── adblock/{AdBlockModels,AdRuleCompiler,AdBlockRepository}.kt
│       │   │   ├── browser/{AddressNormalizer,MediaCandidate,BrowserModels,BrowserViewModel,BrowserWebView,BrowserScreen}.kt
│       │   │   ├── data/AppPreferencesRepository.kt
│       │   │   ├── player/{PlayerActivity,PlayerModels,PlayerViewModel,PlayerScreen}.kt
│       │   │   ├── settings/{SettingsScreen,BookmarksScreen,AdBlockSettingsScreen}.kt
│       │   │   ├── subtitle/{SubtitleModels,SubtitleParser,SubtitleRepository,SubtitleScreens}.kt
│       │   │   ├── ui/theme/{Color,Theme,Type}.kt
│       │   │   └── util/HttpFetcher.kt
│       │   └── res/{drawable,mipmap-*,values,xml}/...
│       └── test/kotlin/com/sniffsub/sniff_sub_player/...
├── build.gradle.kts
├── gradle/libs.versions.toml
├── gradle/wrapper/...
├── gradle.properties
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
└── scripts/verify-release-apk.sh
```

---

### Task 1: Native Android Build and Application Shell

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/BuildIdentity.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/MainActivity.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/ui/theme/Color.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/ui/theme/Theme.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/ui/theme/Type.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/BuildIdentityTest.kt`

**Interfaces:**
- Produces: `object BuildIdentity { const val VERSION_NAME: String; const val VERSION_CODE: Int; const val MIN_SDK: Int }`
- Produces: a native `:app` Gradle module that can run JVM tests and assemble a debug APK.

- [ ] **Step 1: Replace the Flutter Gradle loader with the native Gradle scaffold**

Use version-catalog aliases and these exact build constants:

```toml
[versions]
agp = "8.11.1"
kotlin = "2.2.20"
composeBom = "2025.08.01"
coreKtx = "1.16.0"
activityCompose = "1.10.1"
lifecycle = "2.9.2"
navigation = "2.9.3"
datastore = "1.1.7"
coroutines = "1.10.2"
serialization = "1.9.0"
media3 = "1.11.1"
webkit = "1.14.0"
junit = "4.13.2"
robolectric = "4.16.1"
androidxTestCore = "1.6.1"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-webkit = { module = "androidx.webkit:webkit", version.ref = "webkit" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-exoplayer-hls = { module = "androidx.media3:media3-exoplayer-hls", version.ref = "media3" }
media3-exoplayer-dash = { module = "androidx.media3:media3-exoplayer-dash", version.ref = "media3" }
media3-ui = { module = "androidx.media3:media3-ui", version.ref = "media3" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core-ktx = { module = "androidx.test:core-ktx", version.ref = "androidxTestCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

Configure `app/build.gradle.kts` with namespace/application ID `com.sniffsub.sniff_sub_player`, SDK 36/28, version 1.1.0/3, Java/Kotlin 17, `compose = true`, `testOptions.unitTests.isIncludeAndroidResources = true`, and `ndk.abiFilters += "arm64-v8a"`. Add Compose Material 3, activity-compose, lifecycle ViewModel Compose, navigation-compose, DataStore Preferences, coroutines Android, serialization JSON, WebKit, Media3 ExoPlayer/HLS/DASH/UI, and the listed test dependencies.

- [ ] **Step 2: Write the failing build identity test**

```kotlin
class BuildIdentityTest {
    @Test fun release_identity_is_1_1_0_for_android_9() {
        assertEquals("1.1.0", BuildIdentity.VERSION_NAME)
        assertEquals(3, BuildIdentity.VERSION_CODE)
        assertEquals(28, BuildIdentity.MIN_SDK)
    }
}
```

- [ ] **Step 3: Run the test and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.BuildIdentityTest'`

Expected: compilation fails because `BuildIdentity` does not exist.

- [ ] **Step 4: Add the minimal identity and Compose launch shell**

```kotlin
object BuildIdentity {
    const val VERSION_NAME = "1.1.0"
    const val VERSION_CODE = 3
    const val MIN_SDK = 28
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SniffSubPlayerTheme { Text("SniffSubPlayer") } }
    }
}
```

Declare `INTERNET` and `WAKE_LOCK`, enable hardware acceleration and cleartext compatibility to match the Flutter baseline, and register `MainActivity` as the launcher.

- [ ] **Step 5: Verify GREEN and native assembly**

Run: `./gradlew testDebugUnitTest --tests '*.BuildIdentityTest' assembleDebug`

Expected: the test passes and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle gradle.properties gradlew gradlew.bat app
git commit -m "build: scaffold native Android Compose app"
```

### Task 2: Browser Domain Models and Pure Logic

**Files:**
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser/AddressNormalizer.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser/MediaCandidate.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser/BrowserModels.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/browser/AddressNormalizerTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/browser/MediaCandidateTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/browser/CandidateAccumulatorTest.kt`

**Interfaces:**
- Produces: `fun normalizeAddress(input: String): String?`
- Produces: `enum class MediaKind { HLS, MP4, DASH, SEGMENT, BLOB, OTHER }`
- Produces: `data class MediaCandidate(val url: String, val kind: MediaKind, val source: String, val pageUrl: String, val referer: String, val userAgent: String, val detectedAtEpochMs: Long)`
- Produces: `fun classifyMedia(url: String): MediaKind`
- Produces: `class CandidateAccumulator { fun add(...): Boolean; fun clear(); fun snapshot(): List<MediaCandidate> }`

- [ ] **Step 1: Write failing normalization and classification tests**

Test fully qualified URLs, domain-like input, Chinese search text, blank input, uppercase extensions, query strings, `blob:`, and false positives such as `t.tsx` and `www.ts.example`.

```kotlin
@Test fun words_become_encoded_google_search() {
    assertEquals(
        "https://www.google.com/search?q=%E6%B5%8B%E8%AF%95+%E8%A7%86%E9%A2%91",
        normalizeAddress("测试 视频"),
    )
}

@Test fun media_extension_must_end_at_a_url_boundary() {
    assertEquals(MediaKind.OTHER, classifyMedia("https://cdn.test/t.tsx"))
    assertEquals(MediaKind.HLS, classifyMedia("https://cdn.test/VIDEO.M3U8?token=x"))
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.browser.*Test'`

Expected: compilation fails because the browser domain functions and classes do not exist.

- [ ] **Step 3: Implement normalization and boundary-aware classification**

Use a case-insensitive pattern equivalent to:

```kotlin
private val MEDIA_PATTERN = Regex(
    """\\.(m3u8|mp4|m4v|mov|mpd|m4s|ts|aac)(?=([?#/&]|$))""",
    RegexOption.IGNORE_CASE,
)
```

Map extensions to the exact `MediaKind` values and check `blob:` before the extension pattern.

- [ ] **Step 4: Write the failing accumulator tests**

```kotlin
@Test fun newest_unique_candidate_is_first_and_uses_page_as_referer() {
    val store = CandidateAccumulator(clock = { 123L })
    assertTrue(store.add(" https://cdn.test/a.m3u8 ", "fetch", "https://page.test", "UA"))
    assertFalse(store.add("https://cdn.test/a.m3u8", "xhr", "https://page.test", "UA"))
    assertEquals("https://page.test", store.snapshot().single().referer)
    assertEquals(123L, store.snapshot().single().detectedAtEpochMs)
}
```

- [ ] **Step 5: Run the accumulator test and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.CandidateAccumulatorTest'`

Expected: compilation fails because `CandidateAccumulator` does not exist.

- [ ] **Step 6: Implement the accumulator and verify GREEN**

Reject blank and `OTHER` URLs, deduplicate by normalized URL, insert at index zero, use the current page when the bridge omits `pageUrl`, and cap the exposed candidate list at 30.

Run: `./gradlew testDebugUnitTest --tests '*.browser.*Test'`

Expected: all browser domain tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser app/src/test/kotlin/com/sniffsub/sniff_sub_player/browser
git commit -m "feat: add browser domain logic"
```

### Task 3: Native Preferences, Bookmarks, and Application Container

**Files:**
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/data/AppPreferencesRepository.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/SniffSubPlayerApplication.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/AppContainer.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/data/AppPreferencesCodecTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/data/BookmarkReducerTest.kt`

**Interfaces:**
- Produces: `data class Bookmark(val id: String, val title: String, val url: String, val createdAtEpochMs: Long)`
- Produces: `data class StoredSubscription(val id: String, val name: String, val url: String, val enabled: Boolean, val builtIn: Boolean)`
- Produces: `data class AppPreferences(val userAgent: String = "", val accentArgb: Long = 0xFFE8A7BFL, val bookmarks: List<Bookmark> = emptyList(), val adBlockEnabled: Boolean = false, val automaticUpdatesEnabled: Boolean = true, val subscriptions: List<StoredSubscription> = emptyList(), val compiledFilters: List<String> = emptyList(), val sourceRulesCount: Int = 0, val convertedRulesCount: Int = 0, val discardedRulesCount: Int = 0, val lastUpdatedAtEpochMs: Long? = null)`
- Produces: `interface AppPreferencesRepository { val values: Flow<AppPreferences>; suspend fun update(transform: (AppPreferences) -> AppPreferences) }`
- Produces: `class AppContainer(applicationContext: Context)` and `SniffSubPlayerApplication.container`.

- [ ] **Step 1: Write failing codec and bookmark behavior tests**

```kotlin
@Test fun corrupt_json_falls_back_to_defaults() {
    assertEquals(AppPreferences(), AppPreferencesCodec.decode("not-json"))
}

@Test fun toggling_a_new_bookmark_puts_it_first_then_removes_it() {
    val added = reduceBookmarkToggle(emptyList(), "https://a.test", "A", now = 7L)
    assertEquals("https://a.test", added.single().url)
    assertTrue(reduceBookmarkToggle(added, "https://a.test", "A", now = 8L).isEmpty())
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.data.*Test'`

Expected: compilation fails because the preference types and codec do not exist.

- [ ] **Step 3: Implement serializable preferences and bookmark reducer**

Use Kotlin serialization for the single JSON value stored under Preferences DataStore key `app.preferences.v1`. `AppPreferencesCodec.decode` catches serialization failures and returns `AppPreferences()`.

`reduceBookmarkToggle` trims the URL, ignores blank URLs, removes an existing exact URL, otherwise inserts a new UUID bookmark at index zero and uses the URL as title when the supplied title is blank.

- [ ] **Step 4: Implement DataStore repository and application container**

Create the DataStore once at application scope. Implement updates through `dataStore.edit` so bookmark and settings mutations are atomic. Register `SniffSubPlayerApplication` in the manifest. `AppContainer` owns one preferences repository and later receives the HTTP, ad-block, and subtitle repositories.

- [ ] **Step 5: Verify GREEN**

Run: `./gradlew testDebugUnitTest --tests '*.data.*Test'`

Expected: codec and reducer tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/kotlin/com/sniffsub/sniff_sub_player app/src/test/kotlin/com/sniffsub/sniff_sub_player/data
git commit -m "feat: add native settings persistence"
```

### Task 4: Ad-Block Rule Compiler and Resilient Repository

**Files:**
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/adblock/AdBlockModels.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/adblock/AdRuleCompiler.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/adblock/AdBlockRepository.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/util/HttpFetcher.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/adblock/AdRuleCompilerTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/adblock/AdBlockRepositoryTest.kt`

**Interfaces:**
- Consumes: `StoredSubscription` and `AppPreferencesRepository`; map storage records to immutable `AdBlockSubscription` domain records at the repository boundary.
- Produces: `data class AdBlockSubscription(val id: String, val name: String, val url: String, val enabled: Boolean = true, val builtIn: Boolean = false)`
- Produces: `data class CompiledRuleSet(val patterns: List<String>, val sourceCount: Int, val convertedCount: Int, val discardedCount: Int) { fun blocks(url: String): Boolean }`
- Produces: `class AdRuleCompiler(private val maximumRules: Int = 15_000) { fun compile(lines: Sequence<String>): CompiledRuleSet }`
- Produces: `interface HttpFetcher { suspend fun get(url: String, headers: Map<String, String>, timeoutMillis: Long, maximumBytes: Int): ByteArray }`
- Produces: `class AdBlockRepository { val state: StateFlow<AdBlockState>; fun blocks(url: String): Boolean; suspend fun refresh(): Result<Unit> }`

- [ ] **Step 1: Write failing compiler tests**

Cover comments, headers, blank lines, `@@` exceptions, cosmetic syntax, modifiers, regular expressions, `||domain^`, wildcards, boundaries, duplicates, 500-character limit, and 15,000-rule limit.

```kotlin
@Test fun domain_rule_honors_host_boundary() {
    val rules = AdRuleCompiler().compile(sequenceOf("||ads.example^"))
    assertTrue(rules.blocks("https://sub.ads.example/banner"))
    assertFalse(rules.blocks("https://badads.example/banner"))
    assertFalse(rules.blocks("https://good.example/?next=ads.example"))
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.AdRuleCompilerTest'`

Expected: compilation fails because `AdRuleCompiler` does not exist.

- [ ] **Step 3: Implement the supported Flutter rule subset**

Skip comments, section headers, exception rules, cosmetic selectors, rules containing `$`, and invalid regular expressions. Convert `||domain^` to a host-boundary regex, strip one leading `|`, escape literals, translate `*` to `.*`, translate `^` to `[^a-zA-Z0-9_.%-]`, deduplicate pattern strings, and count discarded source rules.

- [ ] **Step 4: Write failing repository tests**

Use a fake `HttpFetcher` and fake preferences repository.

```kotlin
@Test fun failed_refresh_keeps_last_valid_rules() = runTest {
    val saved = CompiledRuleSet(listOf("ads\\.example"), 1, 1, 0)
    val repository = repositoryWith(saved, fetchFailure = IOException("offline"))
    assertTrue(repository.refresh().isFailure)
    assertTrue(repository.blocks("https://ads.example/a"))
    assertEquals("规则更新失败: offline", repository.state.value.errorMessage)
}
```

Also test HTTPS-only custom subscriptions, duplicate rejection, non-removable built-ins, 45-second timeout argument, 12 MiB response limit, three built-in subscription URLs, automatic refresh after restore when enabled, and persistence only after a complete successful refresh.

- [ ] **Step 5: Run repository tests and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.AdBlockRepositoryTest'`

Expected: compilation fails because `AdBlockRepository` does not exist.

- [ ] **Step 6: Implement repository, fetcher, and atomic rule snapshot**

`UrlConnectionHttpFetcher` performs work on `Dispatchers.IO`, rejects non-2xx responses, reads at most `maximumBytes + 1`, and throws a size error when the bound is exceeded. `AdBlockRepository` exposes an immutable state flow and keeps the last valid `CompiledRuleSet` in an `AtomicReference` for synchronous WebView request checks.

- [ ] **Step 7: Verify GREEN and commit**

Run: `./gradlew testDebugUnitTest --tests '*.adblock.*Test'`

Expected: all ad-block tests pass.

```bash
git add app/src/main/kotlin/com/sniffsub/sniff_sub_player/adblock app/src/main/kotlin/com/sniffsub/sniff_sub_player/util app/src/test/kotlin/com/sniffsub/sniff_sub_player/adblock
git commit -m "feat: add native ad blocking"
```

### Task 5: WebView Controller and Media Sniffer Bridge

**Files:**
- Create: `app/src/main/assets/sniffer.js`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser/SnifferPayloadParser.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser/BrowserViewModel.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser/BrowserWebView.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/browser/SnifferPayloadParserTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/browser/SnifferAssetContractTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/browser/BrowserViewModelTest.kt`

**Interfaces:**
- Consumes: `normalizeAddress`, `CandidateAccumulator`, `AdBlockRepository`, `AppPreferencesRepository`.
- Produces: `data class SnifferPayload(val url: String, val source: String, val pageUrl: String, val userAgent: String)`
- Produces: `fun parseSnifferPayload(json: String): SnifferPayload?`
- Produces: `data class BrowserUiState(...)` with address, title, loading, progress, navigation flags, sniffer state, candidates, bookmarks, User-Agent, and transient message.
- Produces: `sealed interface BrowserEvent` and `fun reduceBrowserEvent(state: BrowserUiState, event: BrowserEvent): BrowserUiState`.
- Produces: `BrowserViewModel` actions `submitAddress`, `updatePage`, `setLoading`, `setNavigation`, `enableSniffer`, `acceptSnifferPayload`, `clearCandidates`, and `toggleBookmark`.

- [ ] **Step 1: Write failing payload and state tests**

```kotlin
@Test fun bridge_rejects_missing_or_non_http_media_url() {
    assertNull(parseSnifferPayload("{}"))
    assertNull(parseSnifferPayload("""{"url":"javascript:alert(1)","source":"a"}"""))
}

@Test fun navigation_resets_sniffer_but_keeps_previous_candidates_until_clear() {
    val state = reduceBrowserEvent(stateWithSnifferAndCandidate(), BrowserEvent.PageStarted("https://next.test"))
    assertFalse(state.snifferEnabled)
    assertEquals(1, state.candidates.size)
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.SnifferPayloadParserTest' --tests '*.BrowserViewModelTest'`

Expected: compilation fails because the parser and browser state classes do not exist.

- [ ] **Step 3: Implement parser, reducer, and ViewModel**

Accept `http`, `https`, and `blob` candidate URLs only. Clamp progress to 0.0–1.0. Keep browser behavior deterministic in a pure reducer and let the ViewModel perform repository calls.

- [ ] **Step 4: Port the JavaScript sniffer asset**

First add `SnifferAssetContractTest` and run it while the asset is absent. It reads `src/main/assets/sniffer.js` and asserts the script contains the install guard, Fetch wrapper, XHR wrapper, Performance scan, four video events, `window.SniffBridge.report`, `navigator.userAgent`, and the 1200 ms interval. Expected RED: the asset is missing.

Then create the asset. It must guard installation with `window.__sniffSubPlayerInstalled`, maintain a `Set`, resolve relative URLs against `location.href`, listen to video/source/anchor elements, Fetch, Fetch responses, XHR, Performance entries, and `loadstart`, `loadedmetadata`, `play`, and `canplay`. It reports JSON through `window.SniffBridge.report(...)`, includes `navigator.userAgent`, scans immediately, and scans every 1200 ms.

- [ ] **Step 5: Add the WebView host**

`BrowserWebView` uses `AndroidView`, enables JavaScript and DOM storage, disables file access not needed by the app, applies the selected User-Agent, enables inline media playback behavior, installs a narrowly scoped `@JavascriptInterface report(json: String)`, updates title/progress/navigation state, and injects `sniffer.js` only when requested.

Its `WebViewClient.shouldInterceptRequest` consults `AdBlockRepository.blocks(url)` and returns an empty response for blocked HTTP(S) requests. It does not attempt to intercept `blob`, `data`, or local content URIs.

- [ ] **Step 6: Verify GREEN, compile, and commit**

Run: `./gradlew testDebugUnitTest --tests '*.browser.*Test' compileDebugKotlin`

Expected: tests pass and WebView integration compiles.

```bash
git add app/src/main/assets app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser app/src/test/kotlin/com/sniffsub/sniff_sub_player/browser
git commit -m "feat: add WebView media sniffing"
```

### Task 6: Material 3 Browser and Settings UI

**Files:**
- Modify: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/MainActivity.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser/BrowserScreen.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/settings/SettingsScreen.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/settings/BookmarksScreen.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/settings/AdBlockSettingsScreen.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/browser/BrowserScreenTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/settings/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `BrowserUiState`, `BrowserViewModel`, `AppPreferencesRepository`, `AdBlockRepository`.
- Produces: `data class BrowserActions` containing callbacks for bookmark, settings, back, forward, address submission, sniffer enablement, candidate clearing, and candidate opening.
- Produces: stable semantics tags `bookmark_button`, `settings_button`, `back_button`, `forward_button`, `address_field`, `browser_webview`, `candidate_panel`, `enable_sniffer_button`, and `clear_candidates_button`.

- [ ] **Step 1: Write failing Robolectric Compose tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
class BrowserScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chrome_keeps_expected_control_order() {
        compose.setContent { BrowserScreen(state = sampleState(), actions = noOpActions()) }
        compose.onNodeWithTag("bookmark_button").assertExists()
        compose.onNodeWithTag("settings_button").assertExists()
        compose.onNodeWithTag("back_button").assertIsNotEnabled()
        compose.onNodeWithTag("address_field").assertExists()
        compose.onNodeWithTag("candidate_panel").assertExists()
    }
}
```

Add settings tests that select a User-Agent, select an accent color, open bookmarks, toggle ad blocking, disable a subscription, reject an `http://` subscription, and prevent deleting built-in subscriptions.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.BrowserScreenTest' --tests '*.SettingsScreenTest'`

Expected: compilation fails because the Compose screens do not exist.

- [ ] **Step 3: Implement the browser screen**

Use a `Scaffold` with a Material 3 top app bar, fixed address row, 2 dp progress indicator, weighted WebView area, and candidate panel capped at 250 dp. Preserve the current Chinese labels and candidate limit of 30. Candidate rows show type badge, source, and two-line URL. Disable sniffer while loading or already enabled and disable clear when empty.

- [ ] **Step 4: Implement settings, bookmarks, and ad-block screens**

Use Material 3 list rows, dialogs, switches, radio choices, and color swatches while keeping the Flutter information architecture. Bookmark selection returns its URL to the browser; deletion is explicit. Ad-block status shows updating, last success/error, source/converted/discarded counts, auto-update, built-in subscriptions, custom subscriptions, and add/remove actions.

Provide these exact User-Agent options: device default with an empty value, iPad Safari with `Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1`, and macOS Safari with `Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15`. Provide the exact accent palette `FFE8A7BF`, `FFFF2D55`, `FFFF9500`, `FFFFCC00`, `FF34C759`, `FF00C7BE`, `FF007AFF`, `FF5856D6`, `FFAF52DE`, `FFFFFFFF`, `FF8E8E93`, and `FF1C1C1E`.

- [ ] **Step 5: Wire navigation and verify GREEN**

Use Navigation Compose for browser, settings, bookmarks, and ad-block settings. Keep the WebView alive when settings is opened by placing browser state in `BrowserViewModel` and avoiding recreation of the WebView on routine state changes.

Run: `./gradlew testDebugUnitTest --tests '*.BrowserScreenTest' --tests '*.SettingsScreenTest' compileDebugKotlin`

Expected: UI tests pass and the application compiles.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/sniffsub/sniff_sub_player/MainActivity.kt app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser/BrowserScreen.kt app/src/main/kotlin/com/sniffsub/sniff_sub_player/settings app/src/test/kotlin/com/sniffsub/sniff_sub_player/browser/BrowserScreenTest.kt app/src/test/kotlin/com/sniffsub/sniff_sub_player/settings
git commit -m "feat: build Material 3 browser and settings"
```

### Task 7: Subtitle Parsing, Search, Import, and Screens

**Files:**
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/subtitle/SubtitleModels.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/subtitle/SubtitleParser.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/subtitle/SubtitleRepository.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/subtitle/SubtitleScreens.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/subtitle/SubtitleParserTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/subtitle/VideoIdExtractorTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/subtitle/SubtitleRepositoryTest.kt`

**Interfaces:**
- Produces: `data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)`
- Produces: `data class OnlineSubtitle(val name: String, val extraName: String?, val url: String) { val displayName: String }`
- Produces: `fun parseSubtitles(text: String, offsetMs: Long = 0): List<SubtitleCue>`
- Produces: `fun subtitleAt(cues: List<SubtitleCue>, positionMs: Long): String`
- Produces: `fun extractVideoId(values: Iterable<String>): String`
- Produces: `class SubtitleRepository { suspend fun search(query: String): Result<List<OnlineSubtitle>>; suspend fun download(url: String): Result<String> }`

- [ ] **Step 1: Write failing parser and identifier tests**

Cover BOM, CRLF, SRT commas, VTT header/settings, short timestamps, cue identifiers, HTML tag removal, multiline text, invalid ranges, sorting, negative/positive offsets, overlapping cues, and end-exclusive cue lookup.

```kotlin
@Test fun overlapping_cues_join_in_start_order() {
    val cues = parseSubtitles(
        "00:00:02.000 --> 00:00:04.000\nB\n\n00:00:01.000 --> 00:00:03.000\nA",
    )
    assertEquals("A\nB", subtitleAt(cues, 2_500))
}

@Test fun extracts_search_identifier() {
    assertEquals("ABC-123", extractVideoId(listOf("https://example.com/watch/abc-123")))
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.subtitle.*Test'`

Expected: compilation fails because the subtitle functions do not exist.

- [ ] **Step 3: Implement parser and identifier extraction**

Clamp shifted times at zero, discard cues whose shifted end is not after shifted start, sort by `startMs`, and join all active overlapping cues with newline. Use the two Flutter regular expressions for video identifiers and uppercase the prefix.

- [ ] **Step 4: Write failing repository tests**

Use a fake `HttpFetcher` to assert the Xunlei URL `https://api-shoulei-ssl.xunlei.com/oracle/subtitle?name=<query>`, Accept headers, rejection of non-2xx responses, decoding of UTF-8 with replacement for malformed bytes, and filtering results to `.srt` or `.vtt` URLs.

- [ ] **Step 5: Run repository tests and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.SubtitleRepositoryTest'`

Expected: compilation fails because `SubtitleRepository` does not exist.

- [ ] **Step 6: Implement repository and Material 3 subtitle screens**

Implement search/result state, download selection, paste editor, and a Subtitlecat WebView at `https://subtitlecat.com/index.php?search=<query>`. Use `ActivityResultContracts.OpenDocument` with MIME types `application/x-subrip`, `text/vtt`, and `text/plain`; read the returned content URI without requesting storage permission.

- [ ] **Step 7: Verify GREEN and commit**

Run: `./gradlew testDebugUnitTest --tests '*.subtitle.*Test' compileDebugKotlin`

Expected: subtitle tests pass and screens compile.

```bash
git add app/src/main/kotlin/com/sniffsub/sniff_sub_player/subtitle app/src/test/kotlin/com/sniffsub/sniff_sub_player/subtitle
git commit -m "feat: add native subtitle workflows"
```

### Task 8: Player State, Gesture Calculations, and Control Layout

**Files:**
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/player/PlayerModels.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/player/PlayerViewModel.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/player/PlayerScreen.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/player/PlayerMathTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/player/PlayerViewModelTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/player/PlayerScreenTest.kt`

**Interfaces:**
- Produces: `interface PlayerGateway` with state flow and `prepare`, `play`, `pause`, `seekTo`, and `release`.
- Produces: `data class PlayerUiState` containing playback, control visibility, scrubbing, feedback, subtitle, and error state.
- Produces: `fun clampSeek(positionMs: Long, deltaMs: Long, durationMs: Long): Long`
- Produces: `fun dragSeek(startMs: Long, deltaPx: Float, widthPx: Float, durationMs: Long): Long`
- Produces: `fun verticalLevel(start: Float, deltaPx: Float, heightPx: Float): Float`
- Produces: semantics tags `player_close`, `player_subtitle`, `player_rewind`, `player_play_pause`, `player_forward`, and `player_progress`.

- [ ] **Step 1: Write failing player calculation tests**

```kotlin
@Test fun seek_and_levels_are_clamped() {
    assertEquals(0L, clampSeek(3_000, -10_000, 60_000))
    assertEquals(60_000L, clampSeek(59_000, 10_000, 60_000))
    assertEquals(1f, verticalLevel(.5f, -100f, 100f))
    assertEquals(0f, verticalLevel(.5f, 100f, 100f))
}
```

Verify horizontal drag uses a range clamped between 60 seconds and 600 seconds and otherwise equals 10% of duration.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.PlayerMathTest'`

Expected: compilation fails because player calculations do not exist.

- [ ] **Step 3: Implement calculations and write failing ViewModel tests**

Use a fake `PlayerGateway` to verify automatic play after preparation, pause keeps controls visible, play schedules a four-second hide, subtitle settings prevent auto-hide, seek feedback text, Blob rejection, error propagation, and release.

- [ ] **Step 4: Run ViewModel tests and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.PlayerViewModelTest'`

Expected: compilation fails because `PlayerViewModel` does not exist.

- [ ] **Step 5: Implement PlayerViewModel and subtitle overlay state**

Inject a coroutine dispatcher and clock so the four-second timeout is deterministic in tests. Reparse the original subtitle text when offset changes. Select active subtitle text through `subtitleAt`. Keep default subtitle size 30 sp and bottom offset 38 dp with ranges 18–56 and 8–180.

- [ ] **Step 6: Write failing Compose control-layout test**

```kotlin
@Test fun landscape_controls_keep_expected_buttons() {
    compose.setContent { PlayerScreen(state = samplePlayingState(), actions = noOpPlayerActions()) }
    compose.onNodeWithTag("player_close").assertExists()
    compose.onNodeWithTag("player_subtitle").assertExists()
    compose.onNodeWithTag("player_rewind").assertExists()
    compose.onNodeWithTag("player_play_pause").assertExists()
    compose.onNodeWithTag("player_forward").assertExists()
    compose.onNodeWithTag("player_progress").assertExists()
}
```

- [ ] **Step 7: Implement the Material 3 player overlay and gestures**

Use a full-screen black `Box`, video surface layer, pointer-input layer, subtitle overlay, gradient controls, and subtitle settings dialog. Fix the subtitle action at 44 x 38 dp, 22 dp from the right and 14 dp from the top. Keep center button order rewind/play/forward and bottom row elapsed/progress/duration. Preserve single tap, side-aware double tap, horizontal seek, left brightness, and right volume gestures.

- [ ] **Step 8: Verify GREEN and commit**

Run: `./gradlew testDebugUnitTest --tests '*.player.*Test' compileDebugKotlin`

Expected: calculation, ViewModel, and control-layout tests pass.

```bash
git add app/src/main/kotlin/com/sniffsub/sniff_sub_player/player app/src/test/kotlin/com/sniffsub/sniff_sub_player/player
git commit -m "feat: add native player controls"
```

### Task 9: Media3 Player Activity and Browser-to-Player Flow

**Files:**
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/player/PlayerActivity.kt`
- Create: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/player/Media3PlayerGateway.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser/BrowserScreen.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/player/PlayerIntentCodecTest.kt`
- Create: `app/src/test/kotlin/com/sniffsub/sniff_sub_player/player/MediaRequestFactoryTest.kt`

**Interfaces:**
- Consumes: `MediaCandidate`, `PlayerGateway`, `PlayerScreen`, `SubtitleRepository`.
- Produces: `object PlayerIntentCodec { fun put(intent: Intent, candidate: MediaCandidate); fun read(intent: Intent): MediaCandidate? }`
- Produces: `data class MediaRequest(val uri: String, val mimeType: String?, val headers: Map<String, String>)`.
- Produces: `fun buildMediaRequest(candidate: MediaCandidate): MediaRequest` with URI, inferred MIME type, User-Agent, and Referer headers.
- Produces: `class Media3PlayerGateway(context: Context, request: MediaRequest) : PlayerGateway`.

- [ ] **Step 1: Write failing intent and media-request tests**

```kotlin
@Test fun hls_request_keeps_headers_and_mime_type() {
    val request = buildMediaRequest(candidate("https://cdn.test/master?id=1", MediaKind.HLS))
    assertEquals(MimeTypes.APPLICATION_M3U8, request.mimeType)
    assertEquals("UA", request.headers["User-Agent"])
    assertEquals("https://page.test", request.headers["Referer"])
}
```

Also verify complete candidate round-trip, malformed intent rejection, DASH MIME type, progressive content, and Blob rejection before gateway creation.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.PlayerIntentCodecTest' --tests '*.MediaRequestFactoryTest'`

Expected: compilation fails because the intent codec and request factory do not exist.

- [ ] **Step 3: Implement intent codec and Media3 gateway**

Use explicit primitive extras rather than Java serialization. Build `DefaultHttpDataSource.Factory` with `setDefaultRequestProperties(headers)`, select HLS/DASH MIME types when classification supplies them, attach ExoPlayer to `PlayerView`, map Media3 callbacks into `PlayerGateway` state, and always release on activity teardown.

- [ ] **Step 4: Implement PlayerActivity system integration**

Register the activity with `screenOrientation="sensorLandscape"`, use immersive sticky system bars, add `FLAG_KEEP_SCREEN_ON` while active, store and restore application brightness, use `AudioManager` for media volume, and restore system UI on finish. Route document-picker and subtitle screen results into `PlayerViewModel`.

- [ ] **Step 5: Connect candidate taps to PlayerActivity**

The browser candidate row calls `PlayerIntentCodec.put(Intent(context, PlayerActivity::class.java), candidate)` and starts the explicit activity. Blob candidates still enter the player error state without preparing Media3, matching the existing user-facing explanation.

- [ ] **Step 6: Verify GREEN and commit**

Run: `./gradlew testDebugUnitTest --tests '*.player.*Test' compileDebugKotlin`

Expected: all player tests pass and both activities compile.

```bash
git add app/src/main/AndroidManifest.xml app/src/main/kotlin/com/sniffsub/sniff_sub_player/browser/BrowserScreen.kt app/src/main/kotlin/com/sniffsub/sniff_sub_player/player app/src/test/kotlin/com/sniffsub/sniff_sub_player/player
git commit -m "feat: integrate Media3 playback"
```

### Task 10: Remove Flutter, Add Release CI, and Verify the Deliverable

**Files:**
- Remove: `.dart_tool/` and Flutter-only generated state if present locally
- Remove: `.metadata`
- Remove: `analysis_options.yaml`
- Remove: `pubspec.yaml`
- Remove: `pubspec.lock`
- Remove: `lib/`
- Remove: `test/`
- Remove: the nested Flutter `android/` project after all required icons and wrapper files are copied to the root native project
- Modify: `.gitignore`
- Modify: `README.md`
- Modify: `.github/workflows/release.yml`
- Create: `scripts/verify-release-apk.sh`

**Interfaces:**
- Produces: `scripts/verify-release-apk.sh <apk>` that exits nonzero unless package, version, signature, Flutter absence, and ABI checks pass.
- Produces: a GitHub Actions workflow triggered by `android` and `workflow_dispatch` that publishes one signed APK plus `SHA256SUMS.txt`.

- [ ] **Step 1: Add the release verification script before changing CI**

The script must:

```bash
set -euo pipefail
apk_path="$1"
test -f "$apk_path"
apkanalyzer manifest application-id "$apk_path" | grep -Fx 'com.sniffsub.sniff_sub_player'
apkanalyzer manifest version-name "$apk_path" | grep -Fx '1.1.0'
apkanalyzer manifest version-code "$apk_path" | grep -Fx '3'
apksigner verify --verbose --print-certs "$apk_path"
if unzip -l "$apk_path" | grep -E 'lib/(armeabi-v7a|x86|x86_64)/'; then exit 1; fi
if unzip -l "$apk_path" | grep -Ei 'libflutter|flutter_assets|kernel_blob|isolate_snapshot'; then exit 1; fi
```

If the APK contains native libraries, additionally require every `lib/<abi>/` entry to use `arm64-v8a`. Print package, version, certificate SHA-256, file byte size, and APK SHA-256 on success.

- [ ] **Step 2: Build an unsigned Release and verify the script fails at signing**

Run: `./gradlew clean testDebugUnitTest lintRelease assembleRelease`

Then run: `scripts/verify-release-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk`

Expected: unit tests, Lint, and assembly pass; verification stops at `apksigner verify` because the local artifact is unsigned unless a local fixed keystore was explicitly configured.

- [ ] **Step 3: Replace the CI workflow**

Configure checkout, Temurin Java 17, Gradle cache, Android SDK tools, and signing-secret validation. Decode `ANDROID_KEYSTORE_BASE64` to `app/release-keystore.jks`, write `keystore.properties` from the four required secrets, run `./gradlew testDebugUnitTest lintRelease assembleRelease`, run the verification script, copy exactly one APK to `dist/SniffSubPlayer-1.1.0-arm64-v8a.apk`, and generate `dist/SHA256SUMS.txt`.

The release job targets the pushed `android` commit and never invokes Flutter, `bundleRelease`, or a debug signing fallback.

- [ ] **Step 4: Update documentation and ignore rules**

Document Android 9+, Kotlin/Compose/Media3, local commands, arm64-only output, user-owned real-device testing, mandatory signing secrets, and the roles of `main`, `flutter`, and `android`. Ignore `.gradle/`, `build/`, `local.properties`, `keystore.properties`, `*.jks`, `*.keystore`, and IDE user state.

- [ ] **Step 5: Remove the Flutter implementation from `android` only**

Delete Dart sources, Flutter tests and goldens, Flutter metadata, Flutter package manifests, Flutter-generated caches, and the nested Flutter Android shell. Retain the app icon by copying it into native adaptive/legacy Android resources before removing `assets/app_icon.png`.

- [ ] **Step 6: Run the full local verification suite**

Run:

```bash
./gradlew clean testDebugUnitTest lintRelease assembleRelease
git grep -n -i -E 'flutter|dart' -- ':!README.md' ':!docs/superpowers/**'
git status --short
```

Expected:

- all JVM and Robolectric Compose tests pass;
- Lint reports no release-blocking errors;
- Release assembly succeeds;
- code and build configuration contain no Flutter or Dart dependency;
- remaining Flutter mentions only explain branch history in README/spec/plan;
- the worktree contains only intentional migration changes.

- [ ] **Step 7: Inspect the final diff and branch boundaries**

Run:

```bash
git diff --check
git diff --stat v1.0.1...HEAD
git rev-parse main flutter 'v1.0.1^{}' android
git status --short --branch
```

Expected: no whitespace errors; `main`, `flutter`, and dereferenced `v1.0.1` still point to their pre-migration commits; only `android` advances.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "release: complete native Android 1.1.0 migration"
```

## Version References

- [Android Gradle Plugin 8.11 compatibility](https://developer.android.com/build/releases/agp-8-11-0-release-notes): API 36 maximum, Java 17, and Gradle 8.13 minimum. This plan keeps the already proven Gradle 8.14.3 wrapper.
- [Compose compiler Gradle plugin setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler): apply `org.jetbrains.kotlin.plugin.compose` with the same Kotlin version.
- [Compose BOM guidance](https://developer.android.com/develop/ui/compose/bom): import one stable BOM for aligned Compose artifacts. The plan pins the pre-API-37 BOM already compatible with the selected AGP/API ceiling.
- [Media3 ExoPlayer setup](https://developer.android.com/media/media3/exoplayer/hello-world): keep all Media3 modules on the same 1.11.1 version and release the player when finished.
- [Robolectric testing strategy](https://developer.android.com/training/testing/local-tests/robolectric): use JVM-hosted Compose behavior tests and do not claim WebView/system-UI fidelity without device tests.

## Final Review Checklist

- [ ] Every requirement in the approved design maps to a task above.
- [ ] Every behavior-bearing component was introduced after a focused failing test.
- [ ] Tests, Lint, Release assembly, diff checks, and APK structural checks have fresh output.
- [ ] Any missing CI signing secrets are reported as an external release blocker, not replaced with debug signing.
- [ ] No device, emulator, real-site, or real-media claim appears in the completion report.
- [ ] Final report includes commit IDs, APK path if signed locally, size, ABI result, certificate SHA-256, and APK SHA-256.
