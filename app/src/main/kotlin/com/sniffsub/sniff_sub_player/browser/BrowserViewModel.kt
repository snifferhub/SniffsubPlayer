package com.sniffsub.sniff_sub_player.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sniffsub.sniff_sub_player.data.AppPreferences
import com.sniffsub.sniff_sub_player.data.AppPreferencesRepository
import com.sniffsub.sniff_sub_player.data.Bookmark
import com.sniffsub.sniff_sub_player.data.reduceBookmarkToggle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class BrowserUiState(
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
    is BrowserEvent.AddressSubmitted -> state.copy(address = event.address)
    is BrowserEvent.PageStarted -> {
        val url = event.url.trim()
        state.copy(
            address = url.ifEmpty { state.address },
            currentPageUrl = url.ifEmpty { state.currentPageUrl },
            loading = true,
            progress = 0.05,
            snifferEnabled = false,
            candidateSheetVisible = false,
            candidates = emptyList(),
            transientMessage = null,
        )
    }
    is BrowserEvent.PageUpdated -> {
        val url = event.url?.trim().orEmpty()
        state.copy(
            address = url.ifEmpty { state.address },
            currentPageUrl = url.ifEmpty { state.currentPageUrl },
            title = event.title ?: state.title,
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

class BrowserViewModel(
    private val preferencesRepository: AppPreferencesRepository,
    private val candidateAccumulator: CandidateAccumulator = CandidateAccumulator(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.values.collectLatest { preferences ->
                dispatch(BrowserEvent.PreferencesChanged(preferences))
            }
        }
    }

    fun submitAddress(input: String): String? {
        val normalized = normalizeAddress(input) ?: return null
        candidateAccumulator.currentPageUrl = normalized
        dispatch(BrowserEvent.AddressSubmitted(normalized))
        return normalized
    }

    fun updatePage(
        url: String?,
        pageTitle: String? = null,
        started: Boolean = false,
    ) {
        val normalizedUrl = url?.trim().orEmpty()
        if (normalizedUrl.isNotEmpty()) candidateAccumulator.currentPageUrl = normalizedUrl
        if (started) {
            candidateAccumulator.clear()
            dispatch(BrowserEvent.PageStarted(normalizedUrl))
        } else {
            dispatch(BrowserEvent.PageUpdated(url = normalizedUrl, title = pageTitle))
        }
    }

    fun setLoading(loading: Boolean, progress: Double? = null) {
        dispatch(BrowserEvent.LoadingChanged(loading, progress))
    }

    fun setProgress(progress: Double) {
        dispatch(BrowserEvent.ProgressChanged(progress))
    }

    fun setNavigation(canGoBack: Boolean, canGoForward: Boolean) {
        dispatch(BrowserEvent.NavigationChanged(canGoBack, canGoForward))
    }

    fun enableSniffer(enabled: Boolean = true) {
        dispatch(BrowserEvent.SnifferChanged(enabled))
    }

    fun requestSniff() {
        val state = mutableUiState.value
        if (state.loading || state.currentPageUrl.ifBlank { state.address }.isBlank()) return
        enableSniffer()
        dispatch(BrowserEvent.CandidateSheetChanged(true))
    }

    fun dismissCandidateSheet() {
        dispatch(BrowserEvent.CandidateSheetChanged(false))
    }

    fun acceptSnifferPayload(json: String): Boolean {
        val state = mutableUiState.value
        if (!state.snifferEnabled) return false
        val payload = parseSnifferPayload(json) ?: return false
        if (payload.pageUrl.isNotBlank() && payload.pageUrl != state.currentPageUrl) return false
        val added = candidateAccumulator.add(
            url = payload.url,
            source = payload.source,
            pageUrl = payload.pageUrl,
            userAgent = payload.userAgent.ifEmpty { state.userAgent },
        )
        if (added) {
            dispatch(BrowserEvent.CandidatesChanged(candidateAccumulator.snapshot()))
        }
        return added
    }

    fun clearCandidates() {
        candidateAccumulator.clear()
        dispatch(BrowserEvent.CandidatesChanged(emptyList()))
    }

    fun toggleBookmark() {
        val state = mutableUiState.value
        val url = state.currentPageUrl.ifBlank { state.address }.trim()
        if (url.isEmpty()) return
        val wasBookmarked = state.bookmarks.any { it.url == url }
        viewModelScope.launch {
            try {
                preferencesRepository.update { preferences ->
                    preferences.copy(
                        bookmarks = reduceBookmarkToggle(
                            bookmarks = preferences.bookmarks,
                            url = url,
                            title = state.title,
                            now = clock(),
                        ),
                    )
                }
                dispatch(BrowserEvent.MessageChanged(if (wasBookmarked) "已取消收藏" else "已收藏"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                dispatch(BrowserEvent.MessageChanged(error.message ?: "收藏更新失败"))
            }
        }
    }

    fun clearTransientMessage() {
        dispatch(BrowserEvent.MessageChanged(null))
    }

    fun setTransientMessage(message: String?) {
        dispatch(BrowserEvent.MessageChanged(message))
    }

    fun handleRendererGone() {
        candidateAccumulator.clear()
        dispatch(BrowserEvent.RendererGone)
    }

    private fun dispatch(event: BrowserEvent) {
        mutableUiState.update { current -> reduceBrowserEvent(current, event) }
    }
}
