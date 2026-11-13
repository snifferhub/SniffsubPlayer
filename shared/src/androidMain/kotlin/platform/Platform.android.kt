package com.sniffsub.sniff_sub_player.platform

import java.net.URI
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun epochMillis(): Long = System.currentTimeMillis()
actual fun randomId(): String = UUID.randomUUID().toString()
actual fun formatLocalDateTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

actual fun parseWebUrl(value: String): WebUrl? = runCatching {
    val uri = URI(value)
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (scheme !in setOf("http", "https") || host.isBlank()) return null
    WebUrl(scheme, host, uri.port, uri.rawPath.orEmpty().ifEmpty { "/" })
}.getOrNull()
