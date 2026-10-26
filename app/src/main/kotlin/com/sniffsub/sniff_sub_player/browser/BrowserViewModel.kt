package com.sniffsub.sniff_sub_player.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sniffsub.sniff_sub_player.data.AppPreferencesRepository
import kotlinx.coroutines.launch

class BrowserViewModel(
    preferencesRepository: AppPreferencesRepository,
    candidateAccumulator: CandidateAccumulator = CandidateAccumulator(),
    clock: () -> Long = System::currentTimeMillis,
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val initialSession = BrowserSession(
        preferencesRepository, candidateAccumulator, clock, viewModelScope,
        initialUrl = savedStateHandle.get<String>("pageUrl").orEmpty(),
    )
    val workspace = BrowserWorkspace(viewModelScope, preferencesRepository, initialSession)
    val session get() = workspace.activeSession
    val uiState get() = session.uiState
    init {
        viewModelScope.launch {
            workspace.uiState.collect { savedStateHandle["pageUrl"] = it.currentPageUrl.ifBlank { it.address } }
        }
    }
    override fun onCleared() {
        workspace.close()
    }
    fun submitAddress(input: String) = session.submitAddress(input)
    fun updatePage(url: String?, pageTitle: String? = null, started: Boolean = false) = session.updatePage(url, pageTitle, started)
    fun setLoading(loading: Boolean, progress: Double? = null) = session.setLoading(loading, progress)
    fun setProgress(progress: Double) = session.setProgress(progress)
    fun setNavigation(canGoBack: Boolean, canGoForward: Boolean) = session.setNavigation(canGoBack, canGoForward)
    fun enableSniffer(enabled: Boolean = true) = session.enableSniffer(enabled)
    fun stopSniff() = session.stopSniff()
    fun prepareReload() = session.prepareReload()
    fun requestSniff() = session.requestSniff()
    fun dismissCandidateSheet() = session.dismissCandidateSheet()
    fun acceptSnifferPayload(json: String) = session.acceptSnifferPayload(json)
    fun clearCandidates() = session.clearCandidates()
    fun toggleBookmark() = session.toggleBookmark()
    fun clearTransientMessage() = session.clearTransientMessage()
    fun setTransientMessage(message: String?) = session.setTransientMessage(message)
    fun handleRendererGone() = session.handleRendererGone()
}
