package com.sniffsub.sniff_sub_player.browser

import com.sniffsub.sniff_sub_player.data.AppPreferencesRepository
import com.sniffsub.sniff_sub_player.platform.randomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BrowserTab(val id: String, val title: String = "", val url: String = "")
data class BrowserTabsState(val tabs: List<BrowserTab>, val selectedId: String)

/** One live session and native back/forward list per tab. Access on the UI thread. */
class BrowserWorkspace(
    private val scope: CoroutineScope,
    private val preferences: AppPreferencesRepository,
    initialSession: BrowserSession = BrowserSession(preferences, scope = scope),
) {
    private val initialId = randomId()
    private val sessions = linkedMapOf(initialId to initialSession)
    private val observations = mutableMapOf<String, Job>()
    private val mutableTabs = MutableStateFlow(
        BrowserTabsState(listOf(BrowserTab(initialId)), initialId),
    )
    private val mutableUiState = MutableStateFlow(initialSession.uiState.value)
    val tabs = mutableTabs.asStateFlow()
    val uiState = mutableUiState.asStateFlow()
    val activeSession: BrowserSession get() = sessions.getValue(mutableTabs.value.selectedId)

    init { observe(initialId, initialSession) }

    fun session(id: String): BrowserSession = sessions.getValue(id)

    fun addTab() {
        if (sessions.size >= MAX_TABS) {
            activeSession.setTransientMessage("最多同时打开 $MAX_TABS 个标签页")
            return
        }
        activeSession.stopSniff()
        activeSession.dismissCandidateSheet()
        val id = randomId()
        val session = BrowserSession(preferences, scope = scope)
        sessions[id] = session
        mutableTabs.value = BrowserTabsState(mutableTabs.value.tabs + BrowserTab(id), id)
        mutableUiState.value = session.uiState.value
        observe(id, session)
    }

    fun selectTab(id: String) {
        if (id !in sessions || id == mutableTabs.value.selectedId) return
        activeSession.stopSniff()
        activeSession.dismissCandidateSheet()
        mutableTabs.value = mutableTabs.value.copy(selectedId = id)
        mutableUiState.value = sessions.getValue(id).uiState.value
    }

    fun closeTab(id: String) {
        if (id !in sessions) return
        if (sessions.size == 1) addTab()
        if (mutableTabs.value.selectedId == id) {
            val index = mutableTabs.value.tabs.indexOfFirst { it.id == id }
            val remaining = mutableTabs.value.tabs.filterNot { it.id == id }
            selectTab(remaining[(index - 1).coerceIn(remaining.indices)].id)
        }
        observations.remove(id)?.cancel()
        sessions.remove(id)?.close()
        mutableTabs.value = mutableTabs.value.copy(tabs = mutableTabs.value.tabs.filterNot { it.id == id })
    }

    fun stopAllSniffing() = sessions.values.forEach { it.stopSniff() }

    fun close() {
        observations.values.forEach { it.cancel() }
        sessions.values.forEach { it.close() }
    }

    private fun observe(id: String, session: BrowserSession) {
        observations[id] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.uiState.collect { state ->
                mutableTabs.value = mutableTabs.value.copy(
                    tabs = mutableTabs.value.tabs.map {
                        if (it.id == id) it.copy(title = state.title, url = state.currentPageUrl.ifBlank { state.address }) else it
                    },
                )
                if (mutableTabs.value.selectedId == id) mutableUiState.value = state
            }
        }
    }

    companion object { const val MAX_TABS = 12 }
}
