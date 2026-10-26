@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sniffsub.sniff_sub_player.browser

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.zIndex
import com.sniffsub.sniff_sub_player.adblock.AdBlockState
import com.sniffsub.sniff_sub_player.app.AppScreen
import com.sniffsub.sniff_sub_player.data.IosAppPreferencesRepository
import com.sniffsub.sniff_sub_player.data.PreferencesEditor
import com.sniffsub.sniff_sub_player.settings.AdBlockSettingsActions
import com.sniffsub.sniff_sub_player.settings.SettingsActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController
import platform.WebKit.WKWebView

interface IosBrowserCallbacks {
    fun openCandidate(candidate: MediaCandidate)
    fun configureWebView(view: WKWebView, onReady: () -> Unit)
    fun setAdBlockEnabled(enabled: Boolean)
    fun setAutomaticUpdatesEnabled(enabled: Boolean)
    fun setSubscriptionEnabled(id: String, enabled: Boolean)
    fun addSubscription(url: String): String?
    fun removeSubscription(id: String)
    fun refreshRules()
}

class IosBrowserHost {
    private val scope = MainScope()
    private val preferences = IosAppPreferencesRepository()
    private val editor = PreferencesEditor(preferences)
    val workspace = BrowserWorkspace(scope, preferences)
    val cookieService: CookieService = createCookieService()
    var callbacks: IosBrowserCallbacks? = null
    private val controllers = mutableMapOf<String, IosWebViewController>()
    private var active by mutableStateOf(true)
    private var adBlockState by mutableStateOf(AdBlockState())
    private var errorMessage by mutableStateOf(preferences.migrationFailure())

    fun setActive(value: Boolean) {
        active = value
        if (!value) workspace.stopAllSniffing()
    }

    fun updateAdBlockState(state: AdBlockState) { adBlockState = state }

    fun loadAddress(address: String) {
        workspace.activeSession.submitAddress(address)?.let {
            controllers[workspace.tabs.value.selectedId]?.loadUrl(it)
        }
    }

    fun close() {
        callbacks = null
        workspace.close()
        scope.cancel()
        controllers.clear()
    }

    fun createViewController(): UIViewController = ComposeUIViewController {
        val settings by preferences.values.collectAsState()
        val accentArgb = settings.accentArgb
        val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(accentArgb))
        else lightColorScheme(primary = Color(accentArgb))
        MaterialTheme(colorScheme = colors) {
            AppScreen(
                preferences = settings,
                adBlockState = adBlockState,
                settingsActions = SettingsActions(
                    onBack = {},
                    onUserAgentSelected = { value -> edit { editor.setUserAgent(value) } },
                    onAccentSelected = { value -> edit { editor.setAccent(value) } },
                    onResetAccent = { edit { editor.resetAccent() } },
                    onOpenBookmarks = {},
                    onOpenAdBlock = {},
                ),
                adBlockActions = AdBlockSettingsActions(
                    onBack = {},
                    onSetEnabled = { callbacks?.setAdBlockEnabled(it) },
                    onSetAutomaticUpdatesEnabled = { callbacks?.setAutomaticUpdatesEnabled(it) },
                    onSetSubscriptionEnabled = { id, enabled -> callbacks?.setSubscriptionEnabled(id, enabled) },
                    onAddSubscription = { url ->
                        val delegate = callbacks
                        if (delegate == null) Result.failure(IllegalStateException("规则服务尚未就绪"))
                        else delegate.addSubscription(url)?.let { Result.failure(IllegalArgumentException(it)) }
                            ?: Result.success(Unit)
                    },
                    onRemoveSubscription = { callbacks?.removeSubscription(it) },
                    onRefresh = { callbacks?.refreshRules() },
                ),
                onOpenBookmark = { loadAddress(it.url) },
                onDeleteBookmark = { bookmark -> edit { editor.removeBookmark(bookmark.id) } },
            ) { visible -> BrowserContent(active && visible, accentArgb) }
            errorMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = { errorMessage = null },
                    title = { Text("设置更新失败") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { errorMessage = null }) { Text("确定") }
                    },
                )
            }
        }
    }

    private fun edit(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { errorMessage = error.message ?: "设置更新失败" }
        }
    }

    @Composable
    private fun BrowserContent(browserActive: Boolean, accentArgb: Long) {
        val tabs by workspace.tabs.collectAsState()
        val state by workspace.uiState.collectAsState()
        tabs.tabs.forEach { tab -> controllers.getOrPut(tab.id) { IosWebViewController() } }
        controllers.keys.retainAll(tabs.tabs.map { it.id }.toSet())
        val controller = controllers.getValue(tabs.selectedId)
        val session = workspace.activeSession
        BrowserScreen(
            state = state,
            isActive = browserActive,
            accentArgb = accentArgb,
            actions = BrowserActions(
                onToggleBookmark = session::toggleBookmark,
                onBack = controller::goBack,
                onForward = controller::goForward,
                onReload = { session.prepareReload(); controller.reload() },
                onSubmitAddress = ::loadAddress,
                onRequestSniff = session::requestSniff,
                onDismissCandidates = session::dismissCandidateSheet,
                onClearCandidates = session::clearCandidates,
                onOpenCandidate = { session.stopSniff(); callbacks?.openCandidate(it) },
                onConsumeTransientMessage = session::clearTransientMessage,
            ),
            tabsContent = {
                BrowserTabBar(tabs, workspace::selectTab, workspace::closeTab, workspace::addTab)
            },
            webViewContent = {
                Box(Modifier.fillMaxSize()) {
                    tabs.tabs.forEach { tab ->
                        key(tab.id) {
                            val tabSession = workspace.session(tab.id)
                            val tabState by tabSession.uiState.collectAsState()
                            val selected = tab.id == tabs.selectedId
                            IosBrowserWebView(
                                state = tabState,
                                session = tabSession,
                                controller = controllers.getValue(tab.id),
                                isActive = browserActive && selected,
                                filterRevision = adBlockState.rulesRevision,
                                configure = { view, ready ->
                                    val delegate = callbacks
                                    if (delegate == null) ready() else delegate.configureWebView(view, ready)
                                },
                                modifier = Modifier.fillMaxSize().zIndex(if (selected) 1f else -1f),
                            )
                        }
                    }
                }
            },
        )
    }
}
