package com.sniffsub.sniff_sub_player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sniffsub.sniff_sub_player.browser.BrowserActions
import com.sniffsub.sniff_sub_player.browser.BrowserScreen
import com.sniffsub.sniff_sub_player.browser.BrowserViewModel
import com.sniffsub.sniff_sub_player.browser.BrowserWebView
import com.sniffsub.sniff_sub_player.browser.rememberBrowserWebViewController
import com.sniffsub.sniff_sub_player.data.AppPreferences
import com.sniffsub.sniff_sub_player.settings.AdBlockSettingsActions
import com.sniffsub.sniff_sub_player.settings.AdBlockSettingsScreen
import com.sniffsub.sniff_sub_player.settings.BookmarksActions
import com.sniffsub.sniff_sub_player.settings.BookmarksScreen
import com.sniffsub.sniff_sub_player.settings.SettingsActions
import com.sniffsub.sniff_sub_player.settings.SettingsScreen
import com.sniffsub.sniff_sub_player.ui.theme.SniffSubPlayerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val appContainer: AppContainer
        get() = (application as SniffSubPlayerApplication).container

    private val browserViewModel: BrowserViewModel by viewModels {
        BrowserViewModelFactory(appContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SniffSubPlayerTheme {
                SniffSubPlayerApp(
                    container = appContainer,
                    browserViewModel = browserViewModel,
                )
            }
        }
    }
}

@Composable
private fun SniffSubPlayerApp(
    container: AppContainer,
    browserViewModel: BrowserViewModel,
) {
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    val browserState by browserViewModel.uiState.collectAsState()
    val preferences by container.preferencesRepository.values.collectAsState(
        initial = AppPreferences(),
    )
    val adBlockState by container.adBlockRepository.state.collectAsState()
    val webViewController = rememberBrowserWebViewController()

    LaunchedEffect(container.adBlockRepository) {
        container.adBlockRepository.restore()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BrowserScreen(
            state = browserState,
            accentArgb = preferences.accentArgb,
            actions = BrowserActions(
                onToggleBookmark = browserViewModel::toggleBookmark,
                onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                onBack = webViewController::goBack,
                onForward = webViewController::goForward,
                onSubmitAddress = { input ->
                    browserViewModel.submitAddress(input)?.let(webViewController::loadUrl)
                },
                onEnableSniffer = { browserViewModel.enableSniffer() },
                onClearCandidates = browserViewModel::clearCandidates,
                onOpenCandidate = {
                    browserViewModel.setTransientMessage("播放器将在后续步骤中接入")
                },
            ),
            webViewContent = {
                BrowserWebView(
                    state = browserState,
                    viewModel = browserViewModel,
                    adBlockRepository = container.adBlockRepository,
                    controller = webViewController,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )

        NavHost(
            navController = navController,
            startDestination = BROWSER_ROUTE,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(BROWSER_ROUTE) { }
            composable(SETTINGS_ROUTE) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        preferences = preferences,
                        actions = SettingsActions(
                            onBack = navController::popBackStack,
                            onUserAgentSelected = { value ->
                                coroutineScope.launch {
                                    container.preferencesRepository.update { it.copy(userAgent = value) }
                                }
                            },
                            onAccentSelected = { value ->
                                coroutineScope.launch {
                                    container.preferencesRepository.update { it.copy(accentArgb = value) }
                                }
                            },
                            onResetAccent = {
                                coroutineScope.launch {
                                    container.preferencesRepository.update {
                                        it.copy(accentArgb = DEFAULT_ACCENT_ARGB)
                                    }
                                }
                            },
                            onOpenBookmarks = { navController.navigate(BOOKMARKS_ROUTE) },
                            onOpenAdBlock = { navController.navigate(AD_BLOCK_ROUTE) },
                        ),
                    )
                }
            }
            composable(BOOKMARKS_ROUTE) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BookmarksScreen(
                        bookmarks = preferences.bookmarks,
                        actions = BookmarksActions(
                            onBack = navController::popBackStack,
                            onOpenBookmark = { bookmark ->
                                browserViewModel.submitAddress(bookmark.url)
                                    ?.let(webViewController::loadUrl)
                                navController.popBackStack(BROWSER_ROUTE, inclusive = false)
                            },
                            onDeleteBookmark = { bookmark ->
                                coroutineScope.launch {
                                    container.preferencesRepository.update { current ->
                                        current.copy(
                                            bookmarks = current.bookmarks.filterNot { it.id == bookmark.id },
                                        )
                                    }
                                }
                            },
                        ),
                    )
                }
            }
            composable(AD_BLOCK_ROUTE) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AdBlockSettingsScreen(
                        state = adBlockState,
                        actions = AdBlockSettingsActions(
                            onBack = navController::popBackStack,
                            onSetEnabled = { enabled ->
                                coroutineScope.launch {
                                    container.adBlockRepository.setEnabled(enabled)
                                    val current = container.adBlockRepository.state.value
                                    if (
                                        enabled &&
                                        current.convertedRulesCount == 0 &&
                                        current.subscriptions.any { it.enabled }
                                    ) {
                                        container.adBlockRepository.refresh()
                                    }
                                }
                            },
                            onSetAutomaticUpdatesEnabled = { enabled ->
                                coroutineScope.launch {
                                    container.adBlockRepository.setAutomaticUpdatesEnabled(enabled)
                                }
                            },
                            onSetSubscriptionEnabled = { id, enabled ->
                                coroutineScope.launch {
                                    container.adBlockRepository.setSubscriptionEnabled(id, enabled)
                                }
                            },
                            onAddSubscription = { url ->
                                coroutineScope.launch {
                                    container.adBlockRepository.addSubscription(url).onFailure { error ->
                                        browserViewModel.setTransientMessage(error.message)
                                    }
                                }
                            },
                            onRemoveSubscription = { id ->
                                coroutineScope.launch {
                                    container.adBlockRepository.removeSubscription(id)
                                }
                            },
                            onRefresh = {
                                coroutineScope.launch { container.adBlockRepository.refresh() }
                            },
                        ),
                    )
                }
            }
        }
    }
}

private class BrowserViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(BrowserViewModel::class.java))
        return BrowserViewModel(container.preferencesRepository) as T
    }
}

private const val BROWSER_ROUTE = "browser"
private const val SETTINGS_ROUTE = "settings"
private const val BOOKMARKS_ROUTE = "bookmarks"
private const val AD_BLOCK_ROUTE = "ad-block"
private const val DEFAULT_ACCENT_ARGB = 0xFFE8A7BFL
