package com.sniffsub.sniff_sub_player.browser

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class BrowserActions(
    val onToggleBookmark: () -> Unit,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onSubmitAddress: (String) -> Unit,
    val onRequestSniff: () -> Unit,
    val onDismissCandidates: () -> Unit,
    val onClearCandidates: () -> Unit,
    val onOpenCandidate: (MediaCandidate) -> Unit,
    val onConsumeTransientMessage: () -> Unit,
    val onReload: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    state: BrowserUiState,
    actions: BrowserActions,
    modifier: Modifier = Modifier,
    accentArgb: Long = 0xFFE8A7BFL,
    isActive: Boolean = true,
    webViewContent: @Composable () -> Unit = {},
) {
    var addressText by remember { mutableStateOf(TextFieldValue(state.address)) }
    var menuExpanded by remember { mutableStateOf(false) }
    var addressFocused by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val browserFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val currentUrl = state.currentPageUrl.ifBlank { state.address }.trim()
    val isBookmarked = state.bookmarks.any { it.url == currentUrl }

    // Keep page updates separate from the draft so redirects cannot overwrite an edit.
    LaunchedEffect(addressFocused) {
        if (addressFocused) {
            addressText = TextFieldValue(currentUrl, TextRange(0, currentUrl.length))
        }
    }
    LaunchedEffect(state.transientMessageRevision) {
        state.transientMessage?.let { message ->
            actions.onConsumeTransientMessage()
            snackbarScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    fun submitAddress() {
        if (addressText.text.isBlank()) return
        actions.onSubmitAddress(addressText.text)
        browserFocusRequester.requestFocus()
        keyboardController?.hide()
    }

    BackHandler(enabled = isActive && (addressFocused || state.canGoBack)) {
        if (addressFocused) {
            browserFocusRequester.requestFocus()
            keyboardController?.hide()
        } else {
            actions.onBack()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                val fieldColor by animateColorAsState(
                    targetValue = if (addressFocused) MaterialTheme.colorScheme.surfaceContainerLow
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    animationSpec = tween(180),
                    label = "address_background",
                )
                val borderColor by animateColorAsState(
                    targetValue = if (addressFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                    animationSpec = tween(180),
                    label = "address_border",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .testTag("address_row")
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedVisibility(
                        visible = !addressFocused,
                        enter = fadeIn(tween(180)) + expandHorizontally(tween(180), expandFrom = Alignment.Start),
                        exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180), shrinkTowards = Alignment.Start),
                    ) {
                        IconButton(
                            onClick = actions.onBack,
                            enabled = state.canGoBack,
                            modifier = Modifier.size(48.dp).testTag("back_button"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
                        }
                    }
                    BasicTextField(
                        value = if (addressFocused) addressText else TextFieldValue(addressDisplayText(currentUrl)),
                        onValueChange = { addressText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(fieldColor)
                            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(24.dp))
                            .onFocusChanged { addressFocused = it.isFocused }
                            .testTag("address_field"),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submitAddress() }),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier.fillMaxSize().padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    if ((if (addressFocused) addressText.text else currentUrl).isEmpty()) {
                                        Text(
                                            "输入网址或搜索",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    innerTextField()
                                }
                                if (addressFocused) {
                                    IconButton(
                                        onClick = { addressText = TextFieldValue() },
                                        enabled = addressText.text.isNotEmpty(),
                                        modifier = Modifier.size(48.dp).testTag("clear_address_button"),
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "清空地址", modifier = Modifier.size(20.dp))
                                    }
                                } else {
                                    IconButton(
                                        onClick = actions.onReload,
                                        enabled = currentUrl.isNotEmpty(),
                                        modifier = Modifier.size(48.dp).testTag("reload_button"),
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "刷新网页", modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        },
                    )
                    Box {
                        if (addressFocused) {
                            TextButton(
                                onClick = {
                                    browserFocusRequester.requestFocus()
                                    keyboardController?.hide()
                                },
                                modifier = Modifier.height(48.dp).testTag("cancel_address_button"),
                            ) {
                                Text("取消")
                            }
                        } else {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.size(48.dp).testTag("browser_menu_button"),
                            ) {
                                Icon(Icons.Default.MoreHoriz, contentDescription = "更多浏览操作")
                            }
                        }
                        DropdownMenu(
                            expanded = menuExpanded && !addressFocused,
                            onDismissRequest = { menuExpanded = false },
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text("前进") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                                enabled = state.canGoForward,
                                onClick = {
                                    menuExpanded = false
                                    actions.onForward()
                                },
                                modifier = Modifier.testTag("forward_button"),
                            )
                            DropdownMenuItem(
                                text = { Text(if (isBookmarked) "取消收藏" else "收藏网页") },
                                leadingIcon = {
                                    Icon(
                                        if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                        contentDescription = null,
                                    )
                                },
                                enabled = currentUrl.isNotEmpty(),
                                onClick = {
                                    menuExpanded = false
                                    actions.onToggleBookmark()
                                },
                                modifier = Modifier.testTag("bookmark_button"),
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.loading) {
                LinearProgressIndicator(
                    progress = { state.progress.toFloat().coerceIn(0f, 1f) },
                    color = Color(accentArgb),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(2.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(browserFocusRequester)
                    .focusable()
                    .testTag("browser_webview"),
            ) {
                webViewContent()
            }

        }
    }
    if (isActive && state.candidateSheetVisible) {
        CandidateSheet(state = state, actions = actions)
    }
}

/** Only collapse web URLs; keep custom schemes and local addresses explicit. */
internal fun addressDisplayText(url: String): String {
    val uri = Uri.parse(url)
    if (!uri.scheme.equals("https", ignoreCase = true) && !uri.scheme.equals("http", ignoreCase = true)) return url
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return url
    val port = uri.port
    val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
    return if (port != -1 && port != defaultPort) "$host:$port" else host
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateSheet(
    state: BrowserUiState,
    actions: BrowserActions,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    fun close(afterClose: () -> Unit = {}) {
        if (closing) return
        closing = true
        scope.launch {
            sheetState.hide()
            actions.onDismissCandidates()
            afterClose()
        }
    }
    ModalBottomSheet(
        onDismissRequest = actions.onDismissCandidates,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("candidate_panel"),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("媒体资源", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.snifferEnabled) "正在扫描当前网页 · 已发现 ${state.candidates.size} 个资源"
                        else "扫描已结束 · 发现 ${state.candidates.size} 个资源",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { close() }, modifier = Modifier.testTag("close_candidates_button")) {
                    Icon(Icons.Default.Close, contentDescription = "关闭资源列表")
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = actions.onRequestSniff,
                    enabled = !state.loading,
                    modifier = Modifier.testTag("rescan_button"),
                ) {
                    Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新扫描")
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = actions.onClearCandidates,
                    enabled = state.candidates.isNotEmpty(),
                    modifier = Modifier.testTag("clear_candidates_button"),
                ) {
                    Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清空")
                }
            }
            if (state.candidates.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Waves, contentDescription = null, tint = Color(0xFF0088FF), modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("暂未发现媒体资源", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "返回网页播放视频，再点击蓝色按钮。每次只扫描当前网页 8 秒，换页后自动清空。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("candidate_list")) {
                    itemsIndexed(state.candidates.take(MAXIMUM_VISIBLE_CANDIDATES), key = { _, candidate -> candidate.url }) { index, candidate ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { close { actions.onOpenCandidate(candidate) } }
                                .testTag("candidate_$index")
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CandidateRow(candidate = candidate, modifier = Modifier.weight(1f).testTag("candidate_row"))
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Default.PlayCircleOutline, contentDescription = null, tint = Color(0xFF0088FF))
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: MediaCandidate, modifier: Modifier = Modifier) {
    val badgeColor = when (candidate.kind) {
        MediaKind.HLS -> Color(0xFF34C759)
        MediaKind.MP4 -> Color(0xFF007AFF)
        MediaKind.DASH -> Color(0xFFAF52DE)
        MediaKind.SEGMENT -> Color(0xFFFF9500)
        MediaKind.BLOB -> Color(0xFFFF2D55)
        MediaKind.OTHER -> Color(0xFF8E8E93)
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = candidate.kind.name,
                color = badgeColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(badgeColor.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = candidate.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = candidate.url,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val MAXIMUM_VISIBLE_CANDIDATES = 30
