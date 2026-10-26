package com.sniffsub.sniff_sub_player.browser

import com.sniffsub.sniff_sub_player.platform.parseWebUrl

/** Turns browser address-bar input into a URL suitable for loading. */
fun normalizeAddress(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return null

    if (value.contains("://")) return value.takeIf { parseWebUrl(it) != null }
    if (value.contains('.') && value.none(Char::isWhitespace)) {
        return "https://$value".takeIf { parseWebUrl(it) != null }
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

fun addressDisplayText(url: String): String {
    val parsed = parseWebUrl(url) ?: return url
    val defaultPort = if (parsed.scheme == "https") 443 else 80
    return if (parsed.port != -1 && parsed.port != defaultPort) "${parsed.host}:${parsed.port}" else parsed.host
}
