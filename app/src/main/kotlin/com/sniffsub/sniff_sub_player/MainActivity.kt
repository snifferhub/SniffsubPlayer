package com.sniffsub.sniff_sub_player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import com.sniffsub.sniff_sub_player.ui.components.GlassNavigationBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sniffsub.sniff_sub_player.browser.BrowserActions
import com.sniffsub.sniff_sub_player.browser.BrowserScreen
import com.sniffsub.sniff_sub_player.browser.BrowserViewModel
import com.sniffsub.sniff_sub_player.browser.BrowserWebView
import com.sniffsub.sniff_sub_player.browser.BrowserTabBar
import com.sniffsub.sniff_sub_player.browser.BrowserWebViewController
import com.sniffsub.sniff_sub_player.browser.AndroidWebContentFilter
import com.sniffsub.sniff_sub_player.browser.classifyWebResource
import com.sniffsub.sniff_sub_player.adblock.AdRequest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.sniffsub.sniff_sub_player.browser.MediaCandidate
import com.sniffsub.sniff_sub_player.browser.MediaKind
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
import com.sniffsub.sniff_sub_player.updates.AndroidAppUpdateDialog
import com.sniffsub.sniff_sub_player.updates.UpdateStage

class MainActivity : ComponentActivity() {
    private val appContainer: AppContainer
        get() = (application as SniffSubPlayerApplication).container

    private val browserViewModel: BrowserViewModel by viewModels {
        BrowserViewModelFactory(appContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    val updateState by container.appUpdateManager.state.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    val tabsState by browserViewModel.workspace.tabs.collectAsState()
    val controllers = remember { mutableMapOf<String, BrowserWebViewController>() }
    tabsState.tabs.forEach { tab ->
        key(tab.id) {
            controllers[tab.id] = rememberBrowserWebViewController()
        }
    }
    controllers.keys.retainAll(tabsState.tabs.map { it.id }.toSet())
    val webViewController = controllers.getValue(tabsState.selectedId)
    val contentFilter = remember(container.adBlockRepository, coroutineScope) {
        object : AndroidWebContentFilter {
            override val rulesRevision = container.adBlockRepository.state.map { it.rulesRevision }
                .stateIn(coroutineScope, SharingStarted.Eagerly, 0L)
            override fun selectors(pageUrl: String) = container.adBlockRepository.selectors(pageUrl)
            override fun blocks(request: android.webkit.WebResourceRequest, pageUrl: String): Boolean =
                container.adBlockRepository.blocks(
                    AdRequest(request.url.toString(), pageUrl, classifyWebResource(request)),
                )
        }
    }
    val messageController = remember { UiMessageController() }
    val globalMessage by messageController.message.collectAsState()
    val actionExecutor = remember(coroutineScope, messageController) {
        SafeUiActionExecutor(coroutineScope, messageController::show)
    }
    val focusManager = LocalFocusManager.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var selectedCandidate by rememberSaveable(stateSaver = listSaver<MediaCandidate?, Any>(
        save = { candidate -> candidate?.let {
            listOf(it.url, it.kind.name, it.source, it.pageUrl, it.referer, it.userAgent, it.detectedAtEpochMs)
        } ?: emptyList() },
        restore = { if (it.isEmpty()) null else MediaCandidate(
            it[0] as String, MediaKind.valueOf(it[1] as String), it[2] as String,
            it[3] as String, it[4] as String, it[5] as String, it[6] as Long,
        ) },
    )) { mutableStateOf<MediaCandidate?>(null) }

    LaunchedEffect(currentRoute) {
        if (currentRoute != SETTINGS_ROUTE) showUpdateDialog = false
        if (currentRoute != BROWSER_ROUTE) browserViewModel.workspace.stopAllSniffing()
    }

    LaunchedEffect(container.adBlockRepository, actionExecutor) {
        actionExecutor.execute("广告拦截设置加载失败") {
            container.adBlockRepository.restore()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            BrowserScreen(
                modifier = Modifier
                    .then(if (currentRoute == BROWSER_ROUTE) Modifier else Modifier.clearAndSetSemantics {}),
                state = browserState,
                isActive = currentRoute == BROWSER_ROUTE,
                accentArgb = preferences.accentArgb,
                actions = BrowserActions(
                    onToggleBookmark = browserViewModel::toggleBookmark,
                    onBack = webViewController::goBack,
                    onForward = webViewController::goForward,
                    onReload = {
                        browserViewModel.prepareReload()
                        webViewController.reload()
                    },
                    onSubmitAddress = { input ->
                        browserViewModel.submitAddress(input)?.let(webViewController::loadUrl)
                    },
                    onRequestSniff = browserViewModel::requestSniff,
                    onDismissCandidates = browserViewModel::dismissCandidateSheet,
                    onClearCandidates = browserViewModel::clearCandidates,
                    onOpenCandidate = { candidate ->
                        selectedCandidate = candidate
                        navController.navigate(PLAYER_ROUTE)
                    },
                    onConsumeTransientMessage = browserViewModel::clearTransientMessage,
                ),
                tabsContent = {
                    BrowserTabBar(
                        tabsState,
                        browserViewModel.workspace::selectTab,
                        browserViewModel.workspace::closeTab,
                        browserViewModel.workspace::addTab,
                    )
                },
                webViewContent = {
                    Box(Modifier.fillMaxSize()) {
                        tabsState.tabs.forEach { tab ->
                            key(tab.id) {
                                val session = browserViewModel.workspace.session(tab.id)
                                val tabState by session.uiState.collectAsState()
                                val selected = tab.id == tabsState.selectedId
                                BrowserWebView(
                                    state = tabState,
                                    viewModel = session,
                                    contentFilter = contentFilter,
                                    controller = controllers.getValue(tab.id),
                                    isActive = selected && currentRoute == BROWSER_ROUTE,
                                    modifier = Modifier.fillMaxSize().zIndex(if (selected) 1f else -1f)
                                        .then(if (selected) Modifier else Modifier.clearAndSetSemantics {}),
                                )
                            }
                        }
                    }
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
                            showBack = false,
                            bottomContentPadding = 96.dp,
                            updateSummary = when (updateState.stage) {
                                UpdateStage.CHECKING -> "检查中…"
                                UpdateStage.DOWNLOADING -> "正在下载"
                                UpdateStage.VERIFYING -> "正在校验"
                                UpdateStage.READY -> "更新已就绪"
                                else -> BuildIdentity.displayVersion
                            },
                            actions = SettingsActions(
                                onBack = { navController.popBackStack() },
                                onUserAgentSelected = { value ->
                                    actionExecutor.launch("User Agent 更新失败") {
                                        container.preferencesEditor.setUserAgent(value)
                                    }
                                },
                                onAccentSelected = { value ->
                                    actionExecutor.launch("进度条颜色更新失败") {
                                        container.preferencesEditor.setAccent(value)
                                    }
                                },
                                onResetAccent = {
                                    actionExecutor.launch("进度条颜色重置失败") {
                                        container.preferencesEditor.resetAccent()
                                    }
                                },
                                onOpenBookmarks = { navController.navigate(BOOKMARKS_ROUTE) },
                                onOpenAdBlock = { navController.navigate(AD_BLOCK_ROUTE) },
                                onCheckForUpdates = {
                                    showUpdateDialog = true
                                    container.appUpdateManager.check()
                                },
                            ),
                        )
                    }
                }
                composable(BOOKMARKS_ROUTE) {
                    Surface(modifier = Modifier.fillMaxSize().padding(bottom = 96.dp)) {
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
                                        container.preferencesEditor.removeBookmark(bookmark.id)
                                    }
                                },
                            ),
                        )
                    }
                }
                composable(AD_BLOCK_ROUTE) {
                    Surface(modifier = Modifier.fillMaxSize().padding(bottom = 96.dp)) {
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
                                            (current.convertedRulesCount == 0 || current.lastUpdatedAtEpochMs == null || current.configurationNeedsUpdate) &&
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
                composable(
                    PLAYER_ROUTE,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    val candidate = selectedCandidate
                    if (candidate == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        PlayerScreen(
                            candidate = candidate,
                            subtitleRepository = container.subtitleRepository,
                            onBack = {
                                navController.popBackStack()
                            },
                        )
                    }
                }
            }

        }

        AnimatedVisibility(
            visible = currentRoute != PLAYER_ROUTE && !keyboardVisible,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f),
        ) {
            GlassNavigationBar(
                browserSelected = currentRoute == BROWSER_ROUTE,
                sniffEnabled = currentRoute == BROWSER_ROUTE && !browserState.loading &&
                    browserState.currentPageUrl.ifBlank { browserState.address }.isNotBlank(),
                onBrowser = {
                    focusManager.clearFocus()
                    browserViewModel.dismissCandidateSheet()
                    navController.popBackStack(BROWSER_ROUTE, inclusive = false)
                },
                onSettings = {
                    focusManager.clearFocus()
                    browserViewModel.dismissCandidateSheet()
                    if (currentRoute != SETTINGS_ROUTE) {
                        navController.navigate(SETTINGS_ROUTE) {
                            popUpTo(BROWSER_ROUTE)
                            launchSingleTop = true
                        }
                    }
                },
                onSniff = {
                    focusManager.clearFocus()
                    browserViewModel.requestSniff()
                },
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(max = 520.dp).fillMaxWidth(),
            )
        }

        // PlayerScreen handles its own subtitle panel before leaving playback.
        BackHandler(enabled = currentRoute != BROWSER_ROUTE && currentRoute != PLAYER_ROUTE) {
            navController.popBackStack()
        }

        UiMessageHost(
            message = globalMessage,
            onConsumed = messageController::consume,
            modifier = Modifier
                .zIndex(3f)
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = if (currentRoute == PLAYER_ROUTE) 16.dp else 96.dp),
        )
        if (showUpdateDialog && currentRoute == SETTINGS_ROUTE) {
            AndroidAppUpdateDialog(
                state = updateState,
                currentVersion = BuildIdentity.displayVersion,
                manager = container.appUpdateManager,
                verifier = container.appUpdateVerifier,
                onDismiss = { showUpdateDialog = false },
            )
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

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass.isAssignableFrom(BrowserViewModel::class.java))
        return BrowserViewModel(container.preferencesRepository, savedStateHandle = extras.createSavedStateHandle()) as T
    }
}

private const val BROWSER_ROUTE = "browser"
private const val SETTINGS_ROUTE = "settings"
private const val BOOKMARKS_ROUTE = "bookmarks"
private const val AD_BLOCK_ROUTE = "ad-block"
private const val PLAYER_ROUTE = "player"
