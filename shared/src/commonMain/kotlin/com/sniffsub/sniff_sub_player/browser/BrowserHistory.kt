package com.sniffsub.sniff_sub_player.browser

/** A snapshot of the native back/forward list, not a second navigation stack. */
data class BrowserHistoryEntry(val url: String, val title: String = "")

data class BrowserHistory(
    val entries: List<BrowserHistoryEntry> = emptyList(),
    val currentIndex: Int = -1,
) {
    init {
        require((entries.isEmpty() && currentIndex == -1) || currentIndex in entries.indices)
    }

    val canGoBack: Boolean get() = currentIndex > 0
    val canGoForward: Boolean get() = currentIndex >= 0 && currentIndex < entries.lastIndex
}
