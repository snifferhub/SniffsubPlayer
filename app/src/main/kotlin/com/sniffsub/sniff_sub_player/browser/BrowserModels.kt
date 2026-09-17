package com.sniffsub.sniff_sub_player.browser

/** Keeps newly observed playable media URLs in newest-first order. */
class CandidateAccumulator(
    private val clock: () -> Long = { System.currentTimeMillis() },
    currentPageUrl: String = "",
) {
    private val candidates = mutableListOf<MediaCandidate>()

    var currentPageUrl: String = currentPageUrl

    fun add(
        url: String,
        source: String,
        pageUrl: String = "",
        userAgent: String = "",
    ): Boolean {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isEmpty()) return false

        val kind = classifyMedia(normalizedUrl)
        if (kind == MediaKind.OTHER) return false
        if (candidates.any { it.url == normalizedUrl }) return false

        val resolvedPageUrl = pageUrl.trim().ifEmpty { currentPageUrl.trim() }
        candidates.add(
            index = 0,
            element = MediaCandidate(
                url = normalizedUrl,
                kind = kind,
                source = source,
                pageUrl = resolvedPageUrl,
                referer = resolvedPageUrl,
                userAgent = userAgent,
                detectedAtEpochMs = clock(),
            ),
        )
        return true
    }

    fun clear() {
        candidates.clear()
    }

    fun snapshot(): List<MediaCandidate> = candidates.take(MAX_EXPOSED_CANDIDATES)

    private companion object {
        const val MAX_EXPOSED_CANDIDATES = 30
    }
}
