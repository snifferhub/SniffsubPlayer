package com.sniffsub.sniff_sub_player.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class BrowserActions(
    val onToggleBookmark: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onSubmitAddress: (String) -> Unit,
    val onEnableSniffer: () -> Unit,
    val onClearCandidates: () -> Unit,
    val onOpenCandidate: (MediaCandidate) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    state: BrowserUiState,
    actions: BrowserActions,
    modifier: Modifier = Modifier,
    accentArgb: Long = 0xFFE8A7BFL,
    webViewContent: @Composable () -> Unit = {},
) {
    var addressText by remember { mutableStateOf(state.address) }
    val snackbarHostState = remember { SnackbarHostState() }
    val currentUrl = state.currentPageUrl.ifBlank { state.address }.trim()
    val isBookmarked = state.bookmarks.any { it.url == currentUrl }

    LaunchedEffect(state.address) {
        if (state.address != addressText) addressText = state.address
    }
    LaunchedEffect(state.transientMessage) {
        state.transientMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = state.title.ifBlank { "SniffSubPlayer" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onToggleBookmark,
                        enabled = currentUrl.isNotEmpty(),
                        modifier = Modifier.testTag("bookmark_button"),
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (isBookmarked) "取消收藏" else "收藏",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = actions.onOpenSettings,
                        modifier = Modifier.testTag("settings_button"),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("address_row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = actions.onBack,
                    enabled = state.canGoBack,
                    modifier = Modifier.testTag("back_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
                }
                IconButton(
                    onClick = actions.onForward,
                    enabled = state.canGoForward,
                    modifier = Modifier.testTag("forward_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
                }
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("address_field"),
                    singleLine = true,
                    placeholder = { Text("输入网址或搜索") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = { actions.onSubmitAddress(addressText) },
                    ),
                )
                TextButton(onClick = { actions.onSubmitAddress(addressText) }) {
                    Text("前往")
                }
            }

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
                    .testTag("browser_webview"),
            ) {
                webViewContent()
            }

            CandidatePanel(state = state, actions = actions)
        }
    }
}

@Composable
private fun CandidatePanel(
    state: BrowserUiState,
    actions: BrowserActions,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 250.dp)
            .testTag("candidate_panel"),
        tonalElevation = 3.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "嗅探到 ${state.candidates.size} 个媒体资源",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = actions.onEnableSniffer,
                    enabled = !state.loading && !state.snifferEnabled,
                    modifier = Modifier.testTag("enable_sniffer_button"),
                ) {
                    Icon(Icons.Default.Waves, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.snifferEnabled) "已启用" else "启用嗅探")
                }
                IconButton(
                    onClick = actions.onClearCandidates,
                    enabled = state.candidates.isNotEmpty(),
                    modifier = Modifier.testTag("clear_candidates_button"),
                ) {
                    Icon(Icons.Default.ClearAll, contentDescription = "清空资源")
                }
            }

            if (state.candidates.isEmpty()) {
                Text(
                    text = if (state.snifferEnabled) {
                        "嗅探已启用，播放视频后会收集 m3u8 / mp4 / blob / 分片候选"
                    } else {
                        "页面加载完成后，点击“启用嗅探”再播放视频"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 190.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.candidates.take(MAXIMUM_VISIBLE_CANDIDATES).forEachIndexed { index, candidate ->
                        Box(modifier = Modifier.testTag("candidate_row")) {
                            CandidateRow(
                                candidate = candidate,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { actions.onOpenCandidate(candidate) }
                                    .testTag("candidate_$index")
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
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
