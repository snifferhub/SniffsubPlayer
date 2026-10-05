package com.sniffsub.sniff_sub_player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sniffsub.sniff_sub_player.browser.BrowserActions
import com.sniffsub.sniff_sub_player.browser.BrowserScreen
import com.sniffsub.sniff_sub_player.browser.BrowserViewModel
import com.sniffsub.sniff_sub_player.browser.BrowserWebView
import com.sniffsub.sniff_sub_player.browser.MediaCandidate
import com.sniffsub.sniff_sub_player.browser.rememberBrowserWebViewController
import com.sniffsub.sniff_sub_player.data.AppPreferences
import com.sniffsub.sniff_sub_player.player.PlayerScreen
import com.sniffsub.sniff_sub_player.settings.AdBlockSettingsActions
import com.sniffsub.sniff_sub_player.settings.AdBlockSettingsScreen
import com.sniffsub.sniff_sub_player.settings.BookmarksActions
import com.sniffsub.sniff_sub_player.settings.BookmarksScreen
import com.sniffsub.sniff_sub_player.settings.SettingsActions
import com.sniffsub.sniff_sub_player.settings.SettingsScreen
import com.sniffsub.sniff_sub_player.ui.theme.SniffSubPlayerTheme

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
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: BROWSER_ROUTE
    val coroutineScope = rememberCoroutineScope()
    val browserState by browserViewModel.uiState.collectAsState()
    val preferences by container.preferencesRepository.values.collectAsState(
        initial = AppPreferences(),
    )
    val adBlockState by container.adBlockRepository.state.collectAsState()
    val webViewController = rememberBrowserWebViewController()
    val messageController = remember { UiMessageController() }
    val globalMessage by messageController.message.collectAsState()
    val actionExecutor = remember(coroutineScope, messageController) {
        SafeUiActionExecutor(coroutineScope, messageController::show)
    }
    var selectedCandidate by remember { mutableStateOf<MediaCandidate?>(null) }

    LaunchedEffect(container.adBlockRepository, actionExecutor) {
        actionExecutor.execute("广告拦截设置加载失败") {
            container.adBlockRepository.restore()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BrowserScreen(
            state = browserState,
            isActive = currentRoute == BROWSER_ROUTE,
            accentArgb = preferences.accentArgb,
            actions = BrowserActions(
                onToggleBookmark = browserViewModel::toggleBookmark,
                onOpenSettings = {
                    navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true }
                },
                onBack = webViewController::goBack,
                onForward = webViewController::goForward,
                onSubmitAddress = { input ->
                    browserViewModel.submitAddress(input)?.let(webViewController::loadUrl)
                },
                onEnableSniffer = { browserViewModel.enableSniffer() },
                onClearCandidates = browserViewModel::clearCandidates,
                onOpenCandidate = { candidate ->
                    selectedCandidate = candidate
                    navController.navigate(PLAYER_ROUTE)
                },
                onConsumeTransientMessage = browserViewModel::clearTransientMessage,
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
            // The empty browser destination must not cover the persistent WebView.
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (currentRoute == BROWSER_ROUTE) -1f else 1f),
        ) {
            composable(BROWSER_ROUTE) { }
            composable(SETTINGS_ROUTE) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        preferences = preferences,
                        actions = SettingsActions(
                            onBack = { navController.popBackStack() },
                            onUserAgentSelected = { value ->
                                actionExecutor.launch("User Agent 更新失败") {
                                    container.preferencesRepository.update { it.copy(userAgent = value) }
                                }
                            },
                            onAccentSelected = { value ->
                                actionExecutor.launch("进度条颜色更新失败") {
                                    container.preferencesRepository.update { it.copy(accentArgb = value) }
                                }
                            },
                            onResetAccent = {
                                actionExecutor.launch("进度条颜色重置失败") {
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
                            onBack = { navController.popBackStack() },
                            onOpenBookmark = { bookmark ->
                                browserViewModel.submitAddress(bookmark.url)
                                    ?.let(webViewController::loadUrl)
                                navController.popBackStack(BROWSER_ROUTE, inclusive = false)
                            },
                            onDeleteBookmark = { bookmark ->
                                actionExecutor.launch("删除收藏失败") {
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
                            onBack = { navController.popBackStack() },
                            onSetEnabled = { enabled ->
                                actionExecutor.launch("广告拦截开关更新失败") {
                                    container.adBlockRepository.setEnabled(enabled)
                                    val current = container.adBlockRepository.state.value
                                    if (
                                        enabled &&
                                        current.convertedRulesCount == 0 &&
                                        current.subscriptions.any { it.enabled }
                                    ) {
                                        container.adBlockRepository.refresh().getOrThrow()
                                    }
                                }
                            },
                            onSetAutomaticUpdatesEnabled = { enabled ->
                                actionExecutor.launch("自动更新开关更新失败") {
                                    container.adBlockRepository.setAutomaticUpdatesEnabled(enabled)
                                }
                            },
                            onSetSubscriptionEnabled = { id, enabled ->
                                actionExecutor.launch("订阅开关更新失败") {
                                    container.adBlockRepository.setSubscriptionEnabled(id, enabled)
                                }
                            },
                            onAddSubscription = { url ->
                                actionExecutor.execute(
                                    fallbackMessage = "添加订阅失败",
                                    publishError = false,
                                ) {
                                    container.adBlockRepository.addSubscription(url).getOrThrow()
                                }
                            },
                            onRemoveSubscription = { id ->
                                actionExecutor.launch("删除订阅失败") {
                                    container.adBlockRepository.removeSubscription(id)
                                }
                            },
                            onRefresh = {
                                actionExecutor.launch("广告规则更新失败") {
                                    container.adBlockRepository.refresh().getOrThrow()
                                }
                            },
                        ),
                    )
                }
            }
            composable(PLAYER_ROUTE) {
                val candidate = selectedCandidate
                if (candidate == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    PlayerScreen(
                        candidate = candidate,
                        subtitleRepository = container.subtitleRepository,
                        onBack = {
                            selectedCandidate = null
                            navController.popBackStack()
                        },
                    )
                }
            }
        }

        // PlayerScreen handles its own subtitle panel before leaving playback.
        BackHandler(enabled = currentRoute != BROWSER_ROUTE && currentRoute != PLAYER_ROUTE) {
            navController.popBackStack()
        }

        UiMessageHost(
            message = globalMessage,
            onConsumed = messageController::consume,
            modifier = Modifier
                .zIndex(2f)
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
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
private const val PLAYER_ROUTE = "player"
private const val DEFAULT_ACCENT_ARGB = 0xFFE8A7BFL
