package com.sniffsub.sniff_sub_player.updates

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import com.sniffsub.sniff_sub_player.util.HttpFetcher

class AppReleaseTest {
    @Test
    fun source_selects_the_newest_android_build_without_relying_on_response_order() = runTest {
        val source = GitHubAppUpdateSource(object : HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>, timeoutMillis: Long, maximumBytes: Int): ByteArray {
                assertEquals("https://api.github.com/repos/snifferhub/SniffsubPlayer/releases?per_page=30", url)
                return "[${releaseJson(17)},${releaseJson(20)},${releaseJson(18)}]".encodeToByteArray()
            }
        })
        assertEquals(20, source.latest().build)
    }

    @Test
    fun versions_compare_numeric_components_and_builds() {
        assertTrue(compareReleaseVersions("1.1.0", 20, "1.1.0", 19) > 0)
        assertTrue(compareReleaseVersions("1.10.0", 1, "1.9.0", 99) > 0)
        assertTrue(compareReleaseVersions("2.0.0", 1, "1.99.99", 99) > 0)
        assertEquals(0, compareReleaseVersions("1.1.0", 18, "1.1.0", 18))
        assertTrue(compareReleaseVersions("1.1.0", 18, "1.1.0", 19) < 0)
    }

    @Test
    fun parser_accepts_only_stable_android_signed_assets_from_our_release_path() {
        val valid = releaseJson(18)
        assertEquals(18, parseAndroidReleases("[$valid]").single().build)
        val invalid = listOf(
            valid.replace("SniffSubPlayer-Android.apk", "SniffSubPlayer-Android-debug.apk"),
            valid.replace("\"draft\":false", "\"draft\":true"),
            valid.replace("\"prerelease\":false", "\"prerelease\":true"),
            valid.replace("android-v1.1.0-build.18", "ios-v1.1.0"),
            valid.replace("https://github.com", "https://evil.test"),
            valid.replace("sha256:", "md5:"),
            valid.replace("\"size\":100", "\"size\":0"),
            valid.replace("\"state\":\"uploaded\"", "\"state\":\"new\""),
        )
        invalid.forEach { assertTrue(it, parseAndroidReleases("[$it]").isEmpty()) }
    }

    @Test
    fun checksum_and_length_both_have_to_match() {
        val file = File.createTempFile("update-test", ".apk")
        try {
            file.writeText("signed APK fixture")
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val release = sampleRelease().copy(size = file.length(), sha256 = hash)
            verifyUpdateDigest(file, release)
            assertThrows(IllegalStateException::class.java) { verifyUpdateDigest(file, release.copy(size = 1)) }
            file.writeText("tampered APK bytes")
            assertThrows(IllegalStateException::class.java) { verifyUpdateDigest(file, release.copy(size = file.length())) }
        } finally { file.delete() }
    }
}

internal fun sampleRelease(build: Int = 19) = AppRelease(
    "1.1.0", build, "新版本说明",
    "https://github.com/snifferhub/SniffsubPlayer/releases/download/android-v1.1.0-build.$build/SniffSubPlayer-Android.apk",
    100, "a".repeat(64),
)

internal fun releaseJson(build: Int) = """{
    "tag_name":"android-v1.1.0-build.$build","draft":false,"prerelease":false,"body":"说明",
    "assets":[{"name":"SniffSubPlayer-Android.apk","state":"uploaded","size":100,
    "digest":"sha256:${"a".repeat(64)}",
    "browser_download_url":"https://github.com/snifferhub/SniffsubPlayer/releases/download/android-v1.1.0-build.$build/SniffSubPlayer-Android.apk"}]
}""".trimIndent()
