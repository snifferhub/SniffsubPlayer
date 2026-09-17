package com.sniffsub.sniff_sub_player.browser

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class SnifferPayload(
    val url: String,
    val source: String,
    val pageUrl: String,
    val userAgent: String,
)

fun parseSnifferPayload(json: String): SnifferPayload? {
    if (json.length > MAXIMUM_BRIDGE_PAYLOAD_LENGTH) return null

    val body = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    val url = body.string("url").trim()
    if (!isAllowedCandidateUrl(url)) return null

    return SnifferPayload(
        url = url,
        source = body.string("source").trim().ifEmpty { "unknown" },
        pageUrl = sanitizePageUrl(body.string("pageURL").ifEmpty { body.string("pageUrl") }),
        userAgent = sanitizeHeaderValue(body.string("userAgent")),
    )
}

private fun sanitizePageUrl(value: String): String {
    val normalized = value.trim()
    if (normalized.isEmpty() || normalized.length > MAXIMUM_CANDIDATE_URL_LENGTH) return ""
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return ""
    return normalized.takeIf {
        (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }.orEmpty()
}

private fun sanitizeHeaderValue(value: String): String {
    val normalized = value.trim()
    return normalized.takeIf {
        it.length <= MAXIMUM_USER_AGENT_LENGTH && it.none(Char::isISOControl)
    }.orEmpty()
}

private fun JsonObject.string(name: String): String =
    (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun isAllowedCandidateUrl(value: String): Boolean {
    if (value.isEmpty() || value.length > MAXIMUM_CANDIDATE_URL_LENGTH) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return when (uri.scheme?.lowercase()) {
        "http", "https" -> !uri.host.isNullOrBlank()
        "blob" -> uri.schemeSpecificPart.isNotBlank()
        else -> false
    }
}

private const val MAXIMUM_BRIDGE_PAYLOAD_LENGTH = 20_000
private const val MAXIMUM_CANDIDATE_URL_LENGTH = 16_000
private const val MAXIMUM_USER_AGENT_LENGTH = 1_000
