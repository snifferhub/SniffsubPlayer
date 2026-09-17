package com.sniffsub.sniff_sub_player.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Turns browser address-bar input into a URL suitable for loading. */
fun normalizeAddress(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return null

    if (value.contains("://")) return value
    if (value.contains('.') && !value.contains(' ')) return "https://$value"

    val query = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    return "https://www.google.com/search?q=$query"
}
