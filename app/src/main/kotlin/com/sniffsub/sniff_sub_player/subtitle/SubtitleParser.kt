package com.sniffsub.sniff_sub_player.subtitle

private val TIMESTAMP_PATTERN = Regex(
    pattern = """^(?:(\d+):)?(\d{1,2}):(\d{2})(?:[,.](\d{1,3}))?$""",
)
private val INLINE_MARKUP_PATTERN = Regex("""<[^>]+>""")

private val VIDEO_ID_PATTERNS = listOf(
    Regex("""([a-z]{2,6})[-_]?(\d{2,5})""", RegexOption.IGNORE_CASE),
    Regex("""/([a-z0-9]+)-([a-z0-9]+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE),
)

fun parseSubtitles(text: String, offsetMs: Long = 0): List<SubtitleCue> {
    val normalized = text
        .replace("\uFEFF", "")
        .replace("\r", "")

    return normalized
        .split(Regex("""\n\s*\n+"""))
        .mapNotNull { block -> parseCueBlock(block, offsetMs) }
        .sortedBy(SubtitleCue::startMs)
}

fun subtitleAt(cues: List<SubtitleCue>, positionMs: Long): String = cues
    .asSequence()
    .filter { positionMs >= it.startMs && positionMs < it.endMs }
    .sortedBy(SubtitleCue::startMs)
    .joinToString(separator = "\n", transform = SubtitleCue::text)

fun extractVideoId(values: Iterable<String>): String {
    for (value in values) {
        for (pattern in VIDEO_ID_PATTERNS) {
            val match = pattern.find(value) ?: continue
            val prefix = match.groupValues[1]
            val suffix = match.groupValues[2]
            return "${prefix.uppercase()}-$suffix"
        }
    }
    return ""
}

private fun parseCueBlock(block: String, offsetMs: Long): SubtitleCue? {
    val lines = block
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
    val timeIndex = lines.indexOfFirst { "-->" in it }
    if (timeIndex < 0) return null

    val timeLine = lines[timeIndex]
    val startMs = parseTimestamp(timeLine.substringBefore("-->").trim()) ?: return null
    val endToken = timeLine
        .substringAfter("-->", missingDelimiterValue = "")
        .trim()
        .takeWhile { !it.isWhitespace() }
    val endMs = parseTimestamp(endToken) ?: return null
    if (endMs <= startMs) return null

    val cueText = lines
        .drop(timeIndex + 1)
        .joinToString("\n")
        .replace(INLINE_MARKUP_PATTERN, "")
        .trim()
    if (cueText.isEmpty()) return null

    val shiftedStartMs = addOffsetAndClamp(startMs, offsetMs)
    val shiftedEndMs = addOffsetAndClamp(endMs, offsetMs)
    if (shiftedEndMs <= shiftedStartMs) return null

    return SubtitleCue(
        startMs = shiftedStartMs,
        endMs = shiftedEndMs,
        text = cueText,
    )
}

private fun parseTimestamp(value: String): Long? {
    val match = TIMESTAMP_PATTERN.matchEntire(value) ?: return null
    val hours = match.groupValues[1].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 0L
    val minutes = match.groupValues[2].toLongOrNull() ?: return null
    val seconds = match.groupValues[3].toLongOrNull() ?: return null
    if (minutes !in 0L..59L || seconds !in 0L..59L) return null
    val fraction = match.groupValues[4]
    val milliseconds = when (fraction.length) {
        0 -> 0L
        1 -> fraction.toLong() * 100L
        2 -> fraction.toLong() * 10L
        else -> fraction.toLong()
    }
    return try {
        Math.addExact(
            Math.addExact(Math.multiplyExact(hours, 3_600_000L), Math.multiplyExact(minutes, 60_000L)),
            Math.addExact(Math.multiplyExact(seconds, 1_000L), milliseconds),
        )
    } catch (_: ArithmeticException) {
        null
    }
}

private fun addOffsetAndClamp(value: Long, offsetMs: Long): Long {
    val shifted = try {
        Math.addExact(value, offsetMs)
    } catch (_: ArithmeticException) {
        if (offsetMs >= 0) Long.MAX_VALUE else Long.MIN_VALUE
    }
    return shifted.coerceAtLeast(0L)
}
