package com.sniffsub.sniff_sub_player.browser

enum class MediaKind {
    HLS,
    MP4,
    DASH,
    SEGMENT,
    BLOB,
    OTHER,
}

private val MEDIA_PATTERN = Regex(
    """\.(m3u8|mp4|m4v|mov|mpd|m4s|ts|aac)(?=([?#/&]|$))""",
    RegexOption.IGNORE_CASE,
)

fun classifyMedia(url: String): MediaKind {
    if (url.startsWith("blob:", ignoreCase = true)) return MediaKind.BLOB

    val extension = MEDIA_PATTERN.find(url)?.groupValues?.get(1)?.lowercase()
    return when (extension) {
        "m3u8" -> MediaKind.HLS
        "mp4", "m4v", "mov" -> MediaKind.MP4
        "mpd" -> MediaKind.DASH
        "m4s", "ts", "aac" -> MediaKind.SEGMENT
        else -> MediaKind.OTHER
    }
}

data class MediaCandidate(
    val url: String,
    val kind: MediaKind,
    val source: String,
    val pageUrl: String,
    val referer: String,
    val userAgent: String,
    val detectedAtEpochMs: Long,
)
