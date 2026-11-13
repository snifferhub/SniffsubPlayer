package com.sniffsub.sniff_sub_player.browser

import com.sniffsub.sniff_sub_player.data.AppPreferences
import com.sniffsub.sniff_sub_player.data.Bookmark

const val SNIFF_WINDOW_MS = 8_000L

data class BrowserUiState(
    val history: BrowserHistory = BrowserHistory(),
    val address: String = "",
    val title: String = "",
    val currentPageUrl: String = "",
    val loading: Boolean = false,
    val progress: Double = 0.0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val snifferEnabled: Boolean = false,
    val sniffRequestRevision: Long = 0L,
    val candidateSheetVisible: Boolean = false,
    val candidates: List<MediaCandidate> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val userAgent: String = "",
    val transientMessage: String? = null,
    val transientMessageRevision: Long = 0L,
    val webViewGeneration: Int = 0,
)

sealed interface BrowserEvent {
    data class HistoryChanged(val history: BrowserHistory) : BrowserEvent
    data class AddressSubmitted(val address: String) : BrowserEvent
    data class PageStarted(val url: String) : BrowserEvent
    data class PageUpdated(val url: String?, val title: String?) : BrowserEvent
    data class LoadingChanged(val loading: Boolean, val progress: Double? = null) : BrowserEvent
    data class ProgressChanged(val progress: Double) : BrowserEvent
    data class NavigationChanged(val canGoBack: Boolean, val canGoForward: Boolean) : BrowserEvent
    data class SnifferChanged(val enabled: Boolean) : BrowserEvent
    data class CandidateSheetChanged(val visible: Boolean) : BrowserEvent
    data class CandidatesChanged(val candidates: List<MediaCandidate>) : BrowserEvent
    data class PreferencesChanged(val preferences: AppPreferences) : BrowserEvent
    data class MessageChanged(val message: String?) : BrowserEvent
    data object RendererGone : BrowserEvent
}

fun reduceBrowserEvent(state: BrowserUiState, event: BrowserEvent): BrowserUiState = when (event) {
    is BrowserEvent.HistoryChanged -> state.copy(
        history = event.history,
        canGoBack = event.history.canGoBack,
        canGoForward = event.history.canGoForward,
    )
    is BrowserEvent.AddressSubmitted -> state.copy(
        address = event.address, snifferEnabled = false, candidates = emptyList(),
        candidateSheetVisible = false, sniffRequestRevision = state.sniffRequestRevision + 1,
    )
    is BrowserEvent.PageStarted -> {
        val url = event.url.trim()
        state.copy(
            address = url.ifEmpty { state.address },
            currentPageUrl = url.ifEmpty { state.currentPageUrl },
            loading = true,
            progress = 0.05,
            snifferEnabled = false,
            sniffRequestRevision = state.sniffRequestRevision + 1,
            candidateSheetVisible = false,
            candidates = emptyList(),
            transientMessage = null,
        )
    }
    is BrowserEvent.PageUpdated -> {
        val url = event.url?.trim().orEmpty()
        val changed = url.isNotEmpty() && url != state.currentPageUrl
        state.copy(
            address = url.ifEmpty { state.address },
            currentPageUrl = url.ifEmpty { state.currentPageUrl },
            title = event.title ?: state.title,
            snifferEnabled = if (changed) false else state.snifferEnabled,
            sniffRequestRevision = state.sniffRequestRevision + if (changed) 1 else 0,
            candidates = if (changed) emptyList() else state.candidates,
            candidateSheetVisible = if (changed) false else state.candidateSheetVisible,
        )
    }
    is BrowserEvent.LoadingChanged -> state.copy(
        loading = event.loading,
        progress = event.progress?.coerceIn(0.0, 1.0) ?: state.progress,
    )
    is BrowserEvent.ProgressChanged -> state.copy(progress = event.progress.coerceIn(0.0, 1.0))
    is BrowserEvent.NavigationChanged -> state.copy(
        canGoBack = event.canGoBack,
        canGoForward = event.canGoForward,
    )
    is BrowserEvent.SnifferChanged -> state.copy(
        snifferEnabled = event.enabled,
        sniffRequestRevision = state.sniffRequestRevision + if (event.enabled) 1L else 0L,
    )
    is BrowserEvent.CandidateSheetChanged -> state.copy(candidateSheetVisible = event.visible)
    is BrowserEvent.CandidatesChanged -> state.copy(candidates = event.candidates.toList())
    is BrowserEvent.PreferencesChanged -> state.copy(
        bookmarks = event.preferences.bookmarks.toList(),
        userAgent = event.preferences.userAgent,
    )
    is BrowserEvent.MessageChanged -> state.copy(
        transientMessage = event.message,
        transientMessageRevision = if (event.message == null) {
            state.transientMessageRevision
        } else {
            state.transientMessageRevision + 1L
        },
    )
    BrowserEvent.RendererGone -> state.copy(
        loading = false,
        progress = 0.0,
        transientMessage = "网页渲染进程已退出，请重新加载",
        transientMessageRevision = state.transientMessageRevision + 1L,
        webViewGeneration = state.webViewGeneration + 1,
        snifferEnabled = false,
        candidateSheetVisible = false,
        candidates = emptyList(),
    )
}

