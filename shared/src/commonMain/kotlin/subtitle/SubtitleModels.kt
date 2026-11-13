package com.sniffsub.sniff_sub_player.subtitle

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class OnlineSubtitle(
    val name: String,
    val extraName: String?,
    val url: String,
) {
    val displayName: String
        get() = if (extraName.isNullOrEmpty()) name else "$name ($extraName)"
}
