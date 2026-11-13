package com.sniffsub.sniff_sub_player.platform

expect fun epochMillis(): Long
expect fun randomId(): String
expect fun formatLocalDateTime(epochMs: Long): String

data class WebUrl(val scheme: String, val host: String, val port: Int, val path: String)

expect fun parseWebUrl(value: String): WebUrl?
