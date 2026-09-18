package com.sniffsub.sniff_sub_player.subtitle

import com.sniffsub.sniff_sub_player.util.HttpFetcher
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SubtitleRepository(
    private val httpFetcher: HttpFetcher,
) {
    suspend fun search(query: String): Result<List<OnlineSubtitle>> = resultOfSuspend {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val bytes = httpFetcher.get(
            url = "$XUNLEI_SEARCH_URL$encodedQuery",
            headers = SEARCH_HEADERS,
            timeoutMillis = NETWORK_TIMEOUT_MILLIS,
            maximumBytes = MAXIMUM_SEARCH_BYTES,
        )
        parseSearchResponse(bytes.toString(Charsets.UTF_8))
    }

    suspend fun download(url: String): Result<String> = resultOfSuspend {
        require(isHttpUrl(url)) { "Subtitle URL must use HTTP or HTTPS" }
        httpFetcher.get(
            url = url,
            headers = DOWNLOAD_HEADERS,
            timeoutMillis = NETWORK_TIMEOUT_MILLIS,
            maximumBytes = MAXIMUM_SUBTITLE_BYTES,
        ).toString(Charsets.UTF_8)
    }
}

private fun parseSearchResponse(body: String): List<OnlineSubtitle> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
    val data = root["data"] as? JsonArray ?: return emptyList()
    return data.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val url = item.stringValue("url")
        if (!isSubtitleUrl(url)) return@mapNotNull null
        OnlineSubtitle(
            name = item.stringValue("name"),
            extraName = item.nullableStringValue("extra_name"),
            url = url,
        )
    }
}

private fun JsonObject.stringValue(key: String): String = nullableStringValue(key).orEmpty()

private fun JsonObject.nullableStringValue(key: String): String? {
    val primitive = get(key) as? JsonPrimitive ?: return null
    return primitive.takeIf(JsonPrimitive::isString)?.content
}

private fun isSubtitleUrl(value: String): Boolean {
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        return false
    }
    if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) {
        return false
    }
    val path = uri.path?.lowercase().orEmpty()
    return path.endsWith(".srt") || path.endsWith(".vtt")
}

private fun isHttpUrl(value: String): Boolean {
    val scheme = try {
        URI(value).scheme
    } catch (_: Exception) {
        return false
    }
    return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
}

private suspend inline fun <T> resultOfSuspend(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    Result.failure(error)
}

private const val XUNLEI_SEARCH_URL =
    "https://api-shoulei-ssl.xunlei.com/oracle/subtitle?name="
private const val NETWORK_TIMEOUT_MILLIS = 45_000L
private const val MAXIMUM_SEARCH_BYTES = 2 * 1024 * 1024
private const val MAXIMUM_SUBTITLE_BYTES = 12 * 1024 * 1024
private val SEARCH_HEADERS = mapOf("Accept" to "application/json, text/plain, */*")
private val DOWNLOAD_HEADERS = mapOf("Accept" to "text/plain,*/*")
