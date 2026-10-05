package com.sniffsub.sniff_sub_player.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sniffsub.sniff_sub_player.adblock.AdBlockState
import com.sniffsub.sniff_sub_player.adblock.AdBlockSubscription
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

data class AdBlockSettingsActions(
    val onBack: () -> Unit,
    val onSetEnabled: (Boolean) -> Unit,
    val onSetAutomaticUpdatesEnabled: (Boolean) -> Unit,
    val onSetSubscriptionEnabled: (String, Boolean) -> Unit,
    val onAddSubscription: suspend (String) -> Result<Unit>,
    val onRemoveSubscription: (String) -> Unit,
    val onRefresh: () -> Unit,
)

internal fun subscriptionValidationMessage(rawUrl: String): String? {
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
    return if (
        uri == null || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()
    ) {
        "请输入有效的 HTTPS raw 规则链接"
    } else {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdBlockSettingsScreen(
    state: AdBlockState,
    actions: AdBlockSettingsActions,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var subscriptionUrl by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(title = "广告屏蔽", onBack = actions.onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 36.dp),
        ) {
            SettingsSection(footer = "规则只应用于 App 内的网页。开关变化后返回网页刷新生效。") {
                SwitchRow(
                    label = "广告屏蔽",
                    checked = state.isEnabled,
                    onCheckedChange = actions.onSetEnabled,
                    modifier = Modifier.testTag("ad_block_enabled_switch"),
                )
                HorizontalDivider()
                SwitchRow(
                    label = "每天自动更新",
                    checked = state.automaticUpdatesEnabled,
                    onCheckedChange = actions.onSetAutomaticUpdatesEnabled,
                    modifier = Modifier.testTag("automatic_updates_switch"),
                )
            }

            SettingsSection(
                title = "规则订阅",
                footer = "已内置 GitHub 上的 AdGuard 与 EasyList China 规则。内置规则可以关闭，手动添加的订阅可删除。",
            ) {
                SettingsRow(
                    label = "添加订阅",
                    leading = { Icon(Icons.Default.Add, contentDescription = null) },
                    labelColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        subscriptionUrl = ""
                        validationMessage = null
                        showAddDialog = true
                    },
                    modifier = Modifier.testTag("add_subscription_button"),
                )
                state.subscriptions.forEach { subscription ->
                    HorizontalDivider()
                    SubscriptionRow(subscription = subscription, actions = actions)
                }
            }

            SettingsSection(
                title = "规则状态",
                footer = "订阅分别更新，下载失败时保留该订阅已缓存的规则。",
            ) {
                SettingsRow(
                    label = "立即更新规则",
                    leading = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    trailing = if (state.isUpdating) {
                        { CircularProgressIndicator() }
                    } else {
                        null
                    },
                    labelColor = MaterialTheme.colorScheme.primary,
                    onClick = if (state.isUpdating || state.subscriptions.none { it.enabled }) {
                        null
                    } else {
                        actions.onRefresh
                    },
                    modifier = Modifier.testTag("refresh_rules_button"),
                )
                HorizontalDivider()
                SettingsRow(label = "状态", value = adBlockStatusText(state))
                state.lastUpdatedAtEpochMs?.let { updatedAt ->
                    HorizontalDivider()
                    SettingsRow(label = "上次生效", value = formatDate(updatedAt))
                }
                HorizontalDivider()
                SettingsRow(label = "源规则", value = state.sourceRulesCount.toString())
                HorizontalDivider()
                SettingsRow(label = "有效规则", value = state.convertedRulesCount.toString())
                HorizontalDivider()
                SettingsRow(label = "不支持或重复", value = state.discardedRulesCount.toString())
                if (state.configurationNeedsUpdate) {
                    HorizontalDivider()
                    Text(
                        "订阅配置已变化，请更新规则后生效",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                state.errorMessage?.let { message ->
                    HorizontalDivider()
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(12.dp)
                            .testTag("ad_block_error"),
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加规则订阅") },
            text = {
                Column {
                    OutlinedTextField(
                        value = subscriptionUrl,
                        onValueChange = {
                            subscriptionUrl = it
                            validationMessage = null
                        },
                        placeholder = { Text("https://…/filter.txt") },
                        modifier = Modifier.testTag("subscription_url_field"),
                        minLines = 1,
                        maxLines = 3,
                    )
                    validationMessage?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .testTag("subscription_validation_error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val error = subscriptionValidationMessage(subscriptionUrl)
                        if (error == null) {
                            coroutineScope.launch {
                                actions.onAddSubscription(subscriptionUrl.trim())
                                    .onSuccess { showAddDialog = false }
                                    .onFailure { failure ->
                                        validationMessage = failure.message ?: "添加订阅失败"
                                    }
                            }
                        } else {
                            validationMessage = error
                        }
                    },
                    modifier = Modifier.testTag("confirm_add_subscription"),
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SubscriptionRow(
    subscription: AdBlockSubscription,
    actions: AdBlockSettingsActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subscription.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subscription.builtIn) {
                    Text(
                        "内置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            }
            Text(
                subscription.url,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = subscription.enabled,
            onCheckedChange = { actions.onSetSubscriptionEnabled(subscription.id, it) },
            modifier = Modifier.testTag("subscription_switch_${subscription.id}"),
        )
        if (!subscription.builtIn) {
            IconButton(
                onClick = { actions.onRemoveSubscription(subscription.id) },
                modifier = Modifier.testTag("delete_subscription_${subscription.id}"),
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除订阅")
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier)
    }
}

private fun adBlockStatusText(state: AdBlockState): String = when {
    state.isUpdating -> "正在下载并转换规则…"
    state.errorMessage != null -> state.errorMessage
    state.convertedRulesCount == 0 -> "尚未生成规则"
    state.isEnabled -> "已应用 ${state.convertedRulesCount} 条跨平台规则"
    else -> "规则已就绪（当前关闭）"
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
