package com.sniffsub.sniff_sub_player.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sniffsub.sniff_sub_player.adblock.AdBlockState
import com.sniffsub.sniff_sub_player.adblock.AdBlockSubscription
import com.sniffsub.sniff_sub_player.platform.parseWebUrl
import com.sniffsub.sniff_sub_player.platform.formatLocalDateTime
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
    val uri = parseWebUrl(rawUrl.trim())
    return if (uri == null || uri.scheme != "https") {
        "请输入 HTTPS 规则文件链接"
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
    val canUpdate = !state.isUpdating &&
        (state.configurationNeedsUpdate || state.subscriptions.any { it.enabled })

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
            SettingsSection {
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

            SettingsSection(title = "规则订阅") {
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

            SettingsSection(title = "规则状态") {
                SettingsRow(
                    label = when {
                        state.isUpdating -> "正在更新"
                        state.configurationNeedsUpdate -> "应用更改"
                        else -> "更新规则"
                    },
                    leading = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    trailing = if (state.isUpdating) {
                        { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                    labelColor = if (canUpdate) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = if (canUpdate) actions.onRefresh else null,
                    modifier = Modifier.testTag("refresh_rules_button"),
                )
                HorizontalDivider()
                SettingsRow(label = "状态", value = adBlockStatusText(state))
                state.lastUpdatedAtEpochMs?.let { updatedAt ->
                    HorizontalDivider()
                    SettingsRow(label = "上次生效", value = formatLocalDateTime(updatedAt))
                }
                HorizontalDivider()
                SettingsRow(label = "有效规则", value = state.convertedRulesCount.toString())
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
                        label = { Text("HTTPS 规则文件链接") },
                        isError = validationMessage != null,
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (subscription.builtIn) subscription.name else subscription.url,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Switch(
            checked = subscription.enabled,
            onCheckedChange = { actions.onSetSubscriptionEnabled(subscription.id, it) },
            modifier = Modifier.testTag("subscription_switch_${subscription.id}")
                .semantics { contentDescription = subscription.name },
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier.semantics { contentDescription = label },
        )
    }
}

private fun adBlockStatusText(state: AdBlockState): String = when {
    state.isUpdating -> "更新中"
    state.errorMessage != null -> "更新失败"
    state.configurationNeedsUpdate -> "待应用"
    state.convertedRulesCount == 0 -> "尚未生成规则"
    state.isEnabled -> "已启用"
    else -> "已关闭"
}

