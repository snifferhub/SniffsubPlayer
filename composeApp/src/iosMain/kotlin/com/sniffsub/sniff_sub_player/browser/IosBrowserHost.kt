@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sniffsub.sniff_sub_player.browser

import com.sniffsub.composeapp.resources.Res
import com.sniffsub.sniff_sub_player.data.AppPreferences
import com.sniffsub.sniff_sub_player.data.IosAppPreferencesRepository
import com.sniffsub.sniff_sub_player.data.PreferencesEditor
import com.sniffsub.sniff_sub_player.settings.USER_AGENT_OPTIONS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.UIKit.UIColor
import platform.UIKit.UIViewController
import platform.UIKit.systemBackgroundColor
import platform.WebKit.WKWebView

/** SwiftUI owns presentation; Kotlin owns browser sessions, preferences and WebKit integration. */
interface IosBrowserCallbacks {
    fun stateChanged(state: BrowserUiState, tabs: BrowserTabsState, preferences: AppPreferences)
    fun reportError(message: String)
    fun configureWebView(view: WKWebView, onReady: () -> Unit)
}

class IosBrowserHost {
    private val scope = MainScope()
    private val preferences = IosAppPreferencesRepository()
    private val editor = PreferencesEditor(preferences)
    private val workspace = BrowserWorkspace(scope, preferences)
    private var callbacks: IosBrowserCallbacks? = null
    private val controllers = mutableMapOf<String, IosWebViewController>()
    private val observations = mutableMapOf<String, Job>()
    private val activity = MutableStateFlow(false)
    private val active get() = activity.value
    private var filterRevision = 0L
    private var script: String? = null
    private var started = false
    private var closed = false
    private var container: BrowserContainerController? = null

    fun currentState(): BrowserUiState = workspace.uiState.value
    fun currentTabs(): BrowserTabsState = workspace.tabs.value
    fun currentPreferences(): AppPreferences = preferences.values.value
    fun userAgentOptions() = USER_AGENT_OPTIONS

    fun start(callbacks: IosBrowserCallbacks) {
        check(!closed)
        this.callbacks = callbacks
        if (started) return
        started = true
        scope.launch {
            combine(workspace.uiState, workspace.tabs, preferences.values) { state, tabs, settings ->
                Triple(state, tabs, settings)
            }.collect { (state, tabs, settings) ->
                this@IosBrowserHost.callbacks?.stateChanged(state, tabs, settings)
            }
        }
        scope.launch { workspace.tabs.collect { syncWebViews() } }
        preferences.migrationFailure()?.let { callbacks.reportError(it) }
        scope.launch {
            try {
                script = Res.readBytes("files/sniffer.js").decodeToString()
                syncWebViews()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                this@IosBrowserHost.callbacks?.reportError(error.message ?: "无法加载嗅探脚本")
            }
        }
        scope.launch {
            activity.collectLatest { visible ->
                if (visible) {
                    while (true) {
                        controllers[workspace.tabs.value.selectedId]?.sampleNavigation()
                        delay(100)
                    }
                }
            }
        }
    }

    fun createViewController(): UIViewController = container ?: BrowserContainerController().also {
        container = it
        it.view.backgroundColor = UIColor.systemBackgroundColor
        syncWebViews()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        activity.value = value
        if (!value) workspace.stopAllSniffing()
        syncWebViews()
    }

    fun setFilterRevision(value: Long) {
        filterRevision = value
        syncWebViews()
    }

    fun loadAddress(address: String) {
        workspace.activeSession.submitAddress(address)?.let { url ->
            controllers[workspace.tabs.value.selectedId]?.loadUrl(url)
        }
    }

    fun goBack() { controllers[workspace.tabs.value.selectedId]?.goBack() }
    fun goForward() { controllers[workspace.tabs.value.selectedId]?.goForward() }
    fun reload() {
        workspace.activeSession.prepareReload()
        controllers[workspace.tabs.value.selectedId]?.reload()
    }
    fun stopLoading() { controllers[workspace.tabs.value.selectedId]?.stopLoading() }
    fun requestSniff() = workspace.activeSession.requestSniff()
    fun dismissCandidates() = workspace.activeSession.dismissCandidateSheet()
    fun clearCandidates() = workspace.activeSession.clearCandidates()
    fun stopSniffing() = workspace.stopAllSniffing()
    fun toggleBookmark() = workspace.activeSession.toggleBookmark()
    fun clearMessage() = workspace.activeSession.clearTransientMessage()
    fun addTab() = workspace.addTab()
    fun selectTab(id: String) = workspace.selectTab(id)
    fun closeTab(id: String) = workspace.closeTab(id)
    fun setUserAgent(value: String) = edit { editor.setUserAgent(value) }
    fun setPlayerAccent(value: Long) = edit { editor.setAccent(value) }
    fun resetPlayerAccent() = edit { editor.resetAccent() }
    fun removeBookmark(id: String) = edit { editor.removeBookmark(id) }

    fun close() {
        if (closed) return
        closed = true
        callbacks = null
        workspace.close()
        scope.cancel()
        observations.clear()
        controllers.values.forEach { it.release() }
        controllers.clear()
        container = null
    }

    private fun edit(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { callbacks?.reportError(error.message ?: "设置更新失败") }
        }
    }

    private fun syncWebViews() {
        if (closed) return
        val parent = container ?: return
        val tabs = workspace.tabs.value
        val removed = controllers.keys - tabs.tabs.map { it.id }.toSet()
        removed.forEach {
            observations.remove(it)?.cancel()
            controllers.remove(it)?.release()
        }
        tabs.tabs.forEach { tab ->
            val session = workspace.session(tab.id)
            val controller = controllers.getOrPut(tab.id) {
                IosWebViewController(session) { view, ready ->
                    val delegate = callbacks
                    if (delegate == null) ready() else delegate.configureWebView(view, ready)
                }
            }
            // Observe every session, including hidden tabs, so UA changes and renderer recovery
            // do not depend on the selected tab publishing another state update.
            observations.getOrPut(tab.id) {
                scope.launch {
                    session.uiState.collect { state ->
                        controller.update(
                            parent.view, state, active && tab.id == workspace.tabs.value.selectedId,
                            filterRevision, script,
                        )
                    }
                }
            }
            controller.update(
                parent.view, session.uiState.value, active && tab.id == tabs.selectedId,
                filterRevision, script,
            )
        }
    }
}

private class BrowserContainerController : UIViewController(nibName = null, bundle = null) {
    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        view.subviews.filterIsInstance<WKWebView>().forEach { it.setFrame(view.bounds) }
    }
}
