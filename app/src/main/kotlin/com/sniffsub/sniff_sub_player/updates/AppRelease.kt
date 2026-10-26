package com.sniffsub.sniff_sub_player.updates

import com.sniffsub.sniff_sub_player.util.HttpFetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

private const val RELEASES_URL = "https://api.github.com/repos/snifferhub/SniffSubPlayer-Releases/releases?per_page=30"
private const val DOWNLOAD_ROOT = "https://github.com/snifferhub/SniffSubPlayer-Releases/releases/download/"
internal const val SIGNED_APK_NAME = "SniffSubPlayer-Android.apk"
internal const val RELEASE_VERSION_CODE_BASE = 10_000

@Serializable
data class AppRelease(
    val version: String,
    val build: Int,
    val notes: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String,
) {
    val tag: String get() = "android-v$version-build.$build"
    val displayVersion: String get() = "$version · build $build"
    val versionCode: Long get() = RELEASE_VERSION_CODE_BASE.toLong() + build
}

internal fun compareReleaseVersions(first: String, firstBuild: Int, second: String, secondBuild: Int): Int {
    val left = first.split('.').map(String::toInt)
    val right = second.split('.').map(String::toInt)
    for (index in 0..2) {
        val compared = left[index].compareTo(right[index])
        if (compared != 0) return compared
    }
    return firstBuild.compareTo(secondBuild)
}

internal fun parseAndroidReleases(body: String): List<AppRelease> {
    val root = Json.parseToJsonElement(body) as? JsonArray ?: error("更新服务器返回了无效数据")
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
    val tagPattern = Regex("^android-v(\\d+\\.\\d+\\.\\d+)-build\\.(\\d+)$")
    return root.mapNotNull { item ->
        val release = item as? JsonObject ?: return@mapNotNull null
        if ((release["draft"] as? JsonPrimitive)?.booleanOrNull != false ||
            (release["prerelease"] as? JsonPrimitive)?.booleanOrNull != false) return@mapNotNull null
        val match = tagPattern.matchEntire(release.text("tag_name")) ?: return@mapNotNull null
        val version = match.groupValues[1]
        if (version.split('.').any { it.toIntOrNull() == null }) return@mapNotNull null
        val build = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..(Int.MAX_VALUE - RELEASE_VERSION_CODE_BASE) }
            ?: return@mapNotNull null
        val asset = (release["assets"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull {
            it.text("name") == SIGNED_APK_NAME && it.text("state") == "uploaded"
        } ?: return@mapNotNull null
        val url = asset.text("browser_download_url")
        if (url != "$DOWNLOAD_ROOT${match.value}/$SIGNED_APK_NAME") return@mapNotNull null
        val size = (asset["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..(300L * 1024 * 1024) }
            ?: return@mapNotNull null
        val digest = asset.text("digest").removePrefix("sha256:").lowercase()
        if (!asset.text("digest").startsWith("sha256:") || !Regex("[0-9a-f]{64}").matches(digest)) {
            return@mapNotNull null
        }
        AppRelease(version, build, release.text("body").take(4_000), url, size, digest)
    }
}

fun interface AppUpdateSource {
    suspend fun latest(): AppRelease
}

class GitHubAppUpdateSource(private val fetcher: HttpFetcher) : AppUpdateSource {
    override suspend fun latest(): AppRelease {
        val body = fetcher.get(
            RELEASES_URL,
            headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "SniffSubPlayer-Android"),
            timeoutMillis = 30_000,
            maximumBytes = 2 * 1024 * 1024,
        ).decodeToString()
        return parseAndroidReleases(body).maxWithOrNull { first, second ->
            compareReleaseVersions(first.version, first.build, second.version, second.build)
        } ?: error("暂未找到带有校验信息的 Android 正式版本，请稍后重试")
    }
}
