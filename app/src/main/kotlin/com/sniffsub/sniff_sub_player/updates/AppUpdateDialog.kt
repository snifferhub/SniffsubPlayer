package com.sniffsub.sniff_sub_player.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    currentVersion: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    installMessage: String? = null,
    installing: Boolean = false,
) {
    val title = when (state.stage) {
        UpdateStage.CHECKING -> "检查更新"
        UpdateStage.AVAILABLE -> "发现新版本"
        UpdateStage.CURRENT -> "已是最新版本"
        UpdateStage.DOWNLOADING -> "正在下载更新"
        UpdateStage.VERIFYING -> "正在校验更新包"
        UpdateStage.READY -> "更新已就绪"
        UpdateStage.ERROR -> "更新失败"
        UpdateStage.IDLE -> "应用更新"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("app_update_dialog"),
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("当前版本：$currentVersion")
                state.release?.takeIf { state.stage != UpdateStage.CURRENT }?.let { release ->
                    Text("新版本：${release.displayVersion}")
                    Text("下载大小：${String.format(Locale.ROOT, "%.1f", release.size / 1048576.0)} MB")
                    if (state.stage == UpdateStage.AVAILABLE && release.notes.isNotBlank()) Text(release.notes)
                }
                if (state.stage in setOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)) {
                    if (state.progress != null && state.stage == UpdateStage.DOWNLOADING) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(state.progress * 100).toInt()}%")
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.stage == UpdateStage.READY) Text("点击安装后由系统确认，设置与收藏夹会保留。")
                installMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when (state.stage) {
                UpdateStage.AVAILABLE -> TextButton(onClick = onDownload, modifier = Modifier.testTag("download_update_button")) { Text("下载更新") }
                UpdateStage.READY -> TextButton(onClick = onInstall, enabled = !installing, modifier = Modifier.testTag("install_update_button")) {
                    Text(if (installing) "准备安装…" else "安装更新")
                }
                UpdateStage.ERROR -> TextButton(onClick = if (state.retryDownload) onDownload else onCheck, modifier = Modifier.testTag("retry_update_button")) { Text("重试") }
                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (state.stage == UpdateStage.DOWNLOADING) {
                TextButton(onClick = onCancelDownload, modifier = Modifier.testTag("cancel_update_button")) { Text("取消下载") }
            } else if (state.stage == UpdateStage.READY) {
                TextButton(onClick = onDownload) { Text("重新下载") }
            } else if (state.stage in setOf(UpdateStage.AVAILABLE, UpdateStage.ERROR)) {
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}

@Composable
fun AndroidAppUpdateDialog(
    state: AppUpdateState,
    currentVersion: String,
    manager: AppUpdateManager,
    verifier: AppUpdateVerifier,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var requestingPermission by remember { mutableStateOf(false) }
    fun install() {
        val release = state.release ?: return
        if (state.stage != UpdateStage.READY || installing) return
        installing = true
        message = null
        scope.launch {
            try {
                // Recheck immediately before sharing the APK with the system installer.
                verifier.verify(release)
                context.startActivity(updateInstallIntent(context))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = "无法打开安装界面：${error.message ?: "请重试"}"
            } finally {
                installing = false
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        requestingPermission = false
        if (context.packageManager.canRequestPackageInstalls()) install()
        else message = "尚未允许安装更新。请允许本应用安装未知应用后重试。"
    }
    AppUpdateDialog(
        state, currentVersion,
        onCheck = manager::check,
        onDownload = { message = null; manager.download() },
        onCancelDownload = manager::cancelDownload,
        onInstall = {
            if (context.packageManager.canRequestPackageInstalls()) install()
            else {
                try {
                    requestingPermission = true
                    permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } catch (error: Exception) {
                    requestingPermission = false
                    message = "无法打开安装权限设置：${error.message ?: "请重试"}"
                }
            }
        },
        onDismiss = onDismiss,
        installMessage = message,
        installing = installing || requestingPermission,
    )
}
