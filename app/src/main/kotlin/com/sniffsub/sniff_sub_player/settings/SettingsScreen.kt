package com.sniffsub.sniff_sub_player.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sniffsub.sniff_sub_player.data.AppPreferences

data class UserAgentOption(val id: String, val name: String, val value: String)

const val IPAD_SAFARI_USER_AGENT =
    "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 " +
        "Mobile/15E148 Safari/604.1"
const val MACOS_SAFARI_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

val USER_AGENT_OPTIONS = listOf(
    UserAgentOption("device_default", "设备原生（推荐）", ""),
    UserAgentOption("ipad_safari", "iPad Safari", IPAD_SAFARI_USER_AGENT),
    UserAgentOption("macos_safari", "macOS Safari", MACOS_SAFARI_USER_AGENT),
)

val ACCENT_COLOR_PALETTE = listOf(
    0xFFE8A7BFL,
    0xFFFF2D55L,
    0xFFFF9500L,
    0xFFFFCC00L,
    0xFF34C759L,
    0xFF00C7BEL,
    0xFF007AFFL,
    0xFF5856D6L,
    0xFFAF52DEL,
    0xFFFFFFFFL,
    0xFF8E8E93L,
    0xFF1C1C1EL,
)

data class SettingsActions(
    val onBack: () -> Unit,
    val onUserAgentSelected: (String) -> Unit,
    val onAccentSelected: (Long) -> Unit,
    val onResetAccent: () -> Unit,
    val onOpenBookmarks: () -> Unit,
    val onOpenAdBlock: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    var showUserAgentDialog by remember { mutableStateOf(false) }
    var showAccentDialog by remember { mutableStateOf(false) }
    val selectedUserAgent = USER_AGENT_OPTIONS.firstOrNull { it.value == preferences.userAgent }
        ?: USER_AGENT_OPTIONS.first()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(title = "设置", onBack = actions.onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 36.dp),
        ) {
            SettingsSection(
                title = "浏览器",
                footer = "设备原生 UA 与当前系统 WebView 保持一致，兼容性最好。切换 UA 后刷新页面生效。",
            ) {
                SettingsRow(
                    label = "User Agent",
                    value = selectedUserAgent.name,
                    onClick = { showUserAgentDialog = true },
                    modifier = Modifier.testTag("user_agent_row"),
                )
            }
            SettingsSection(
                title = "播放器",
                footer = "颜色同时用于网页加载进度、播放进度和字幕调节滑杆。",
            ) {
                SettingsRow(
                    label = "进度条颜色",
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(preferences.accentArgb), CircleShape),
                        )
                    },
                    onClick = { showAccentDialog = true },
                    modifier = Modifier.testTag("accent_color_row"),
                )
                HorizontalDivider()
                SettingsRow(
                    label = "恢复默认",
                    labelColor = MaterialTheme.colorScheme.primary,
                    onClick = actions.onResetAccent,
                    modifier = Modifier.testTag("reset_accent_row"),
                )
            }
            SettingsSection(title = "收藏夹") {
                SettingsRow(
                    label = "收藏夹",
                    value = preferences.bookmarks.size.toString(),
                    leading = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
                    onClick = actions.onOpenBookmarks,
                    modifier = Modifier.testTag("bookmarks_row"),
                )
            }
            SettingsSection(title = "网页内容") {
                SettingsRow(
                    label = "广告屏蔽",
                    value = if (preferences.adBlockEnabled) "已开启" else "已关闭",
                    leading = { Icon(Icons.Default.Security, contentDescription = null) },
                    onClick = actions.onOpenAdBlock,
                    modifier = Modifier.testTag("ad_block_row"),
                )
            }
        }
    }

    if (showUserAgentDialog) {
        AlertDialog(
            onDismissRequest = { showUserAgentDialog = false },
            title = { Text("User Agent") },
            text = {
                Column {
                    Text("切换后返回浏览器刷新页面生效")
                    USER_AGENT_OPTIONS.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option.value == preferences.userAgent,
                                    role = Role.RadioButton,
                                    onClick = {
                                        actions.onUserAgentSelected(option.value)
                                        showUserAgentDialog = false
                                    },
                                )
                                .testTag("ua_${option.id}")
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option.value == preferences.userAgent,
                                onClick = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(option.name)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showUserAgentDialog = false }) { Text("取消") }
            },
        )
    }

    if (showAccentDialog) {
        AlertDialog(
            onDismissRequest = { showAccentDialog = false },
            title = { Text("进度条颜色") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ACCENT_COLOR_PALETTE.chunked(4).forEach { colors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            colors.forEach { color ->
                                val hex = color.toString(16).uppercase().padStart(8, '0')
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Color(color), CircleShape)
                                        .clickable {
                                            actions.onAccentSelected(color)
                                            showAccentDialog = false
                                        }
                                        .testTag("accent_color_$hex"),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAccentDialog = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
    )
}

@Composable
internal fun SettingsSection(
    title: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 22.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 7.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 7.dp),
            )
        }
    }
}

@Composable
internal fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Text(text = label, color = labelColor, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) trailing()
        if (onClick != null) {
            Spacer(Modifier.width(7.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
