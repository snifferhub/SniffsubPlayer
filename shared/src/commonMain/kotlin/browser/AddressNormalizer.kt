package com.sniffsub.sniff_sub_player.browser

import com.sniffsub.sniff_sub_player.platform.parseWebUrl

/** Turns browser address-bar input into a URL suitable for loading. */
fun normalizeAddress(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return null

    val hasScheme = value.contains("://")
    val candidate = normalizeHostSeparators(when {
        hasScheme -> value
        value.startsWith("//") -> "https:$value"
        else -> "https://$value"
    })
    val parsed = parseWebUrl(candidate)?.takeIf { it.port in -1..65535 }
    if (hasScheme || value.startsWith("//")) return candidate.takeIf { parsed != null }
    if (parsed != null && (
        parsed.host.contains('.') || parsed.host == "localhost" || parsed.host.contains(':')
    )) {
        return candidate
    }

    val query = buildString {
        for (byte in value.encodeToByteArray()) {
            val number = byte.toInt() and 0xff
            val char = number.toChar()
            when {
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_.*" -> append(char)
                char == ' ' -> append('+')
                else -> append('%').append(number.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
    return "https://www.google.com/search?q=$query"
}

private fun normalizeHostSeparators(url: String): String {
    val authorityStart = url.indexOf("://") + 3
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .takeIf { it >= 0 } ?: url.length
    val authority = url.substring(authorityStart, authorityEnd)
        .replace('。', '.')
        .replace('．', '.')
        .replace('｡', '.')
    return url.substring(0, authorityStart) + authority + url.substring(authorityEnd)
}

fun addressDisplayText(url: String): String {
    val parsed = parseWebUrl(url) ?: return url
    val defaultPort = if (parsed.scheme == "https") 443 else 80
    return if (parsed.port != -1 && parsed.port != defaultPort) "${parsed.host}:${parsed.port}" else parsed.host
}
