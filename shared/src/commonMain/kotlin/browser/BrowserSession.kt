package com.sniffsub.sniff_sub_player.browser

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class BrowserSession(
    private val preferencesRepository: AppPreferencesRepository,
    private val candidateAccumulator: CandidateAccumulator = CandidateAccumulator(),
    private val clock: () -> Long = { com.sniffsub.sniff_sub_player.platform.epochMillis() },
    private val scope: kotlinx.coroutines.CoroutineScope,
    initialUrl: String = "",
    private val saveUrl: (String) -> Unit = {},
) {
    private val mutableUiState = MutableStateFlow(BrowserUiState(
        address = initialUrl,
        currentPageUrl = initialUrl,
    ))
    private var sniffTimeout: Job? = null
    val uiState: StateFlow<BrowserUiState> = mutableUiState.asStateFlow()

    private val preferencesJob = scope.launch {
            preferencesRepository.values.collectLatest { preferences ->
                dispatch(BrowserEvent.PreferencesChanged(preferences))
            }
    }

    fun close() {
        stopSniff()
        preferencesJob.cancel()
    }

    fun submitAddress(input: String): String? {
        val normalized = normalizeAddress(input) ?: return null
        stopSniff()
        candidateAccumulator.clear()
        candidateAccumulator.currentPageUrl = normalized
        saveUrl(normalized)
        dispatch(BrowserEvent.AddressSubmitted(normalized))
        return normalized
    }

    fun updatePage(
        url: String?,
        pageTitle: String? = null,
        started: Boolean = false,
    ) {
        val normalizedUrl = url?.trim().orEmpty()
        if (started || (normalizedUrl.isNotEmpty() && normalizedUrl != mutableUiState.value.currentPageUrl)) {
            stopSniff()
            candidateAccumulator.clear()
        }
        if (normalizedUrl.isNotEmpty()) {
            candidateAccumulator.currentPageUrl = normalizedUrl
            saveUrl(normalizedUrl)
        }
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

    fun setHistory(history: BrowserHistory) {
        dispatch(BrowserEvent.HistoryChanged(history))
    }

    fun enableSniffer(enabled: Boolean = true) {
        sniffTimeout?.cancel()
        dispatch(BrowserEvent.SnifferChanged(enabled))
        if (enabled) {
            sniffTimeout = scope.launch {
                delay(SNIFF_WINDOW_MS)
                dispatch(BrowserEvent.SnifferChanged(false))
            }
        }
    }

    fun stopSniff() = enableSniffer(false)

    fun prepareReload() {
        updatePage(mutableUiState.value.currentPageUrl.ifBlank { mutableUiState.value.address }, started = true)
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
        if (payload.pageUrl != state.currentPageUrl || payload.sessionId != state.sniffRequestRevision) return false
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
        scope.launch {
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
        stopSniff()
        candidateAccumulator.clear()
        dispatch(BrowserEvent.RendererGone)
    }

    private fun dispatch(event: BrowserEvent) {
        mutableUiState.update { current -> reduceBrowserEvent(current, event) }
    }
}
