package com.sniffsub.sniff_sub_player.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

enum class UpdateStage { IDLE, CHECKING, AVAILABLE, CURRENT, DOWNLOADING, VERIFYING, READY, ERROR }

data class AppUpdateState(
    val stage: UpdateStage = UpdateStage.IDLE,
    val release: AppRelease? = null,
    val progress: Float? = null,
    val message: String? = null,
    val retryDownload: Boolean = false,
) {
    val busy: Boolean get() = stage in setOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)
}

@Serializable
data class PendingAppUpdate(val downloadId: Long, val release: AppRelease)

interface PendingAppUpdateStore {
    fun read(): PendingAppUpdate?
    fun write(update: PendingAppUpdate)
    fun clear()
}

enum class UpdateDownloadStatus { WAITING, RUNNING, COMPLETE, FAILED }
data class UpdateDownloadSnapshot(val status: UpdateDownloadStatus, val downloaded: Long = 0, val total: Long = 0)

interface AppUpdateDownloader {
    suspend fun enqueue(release: AppRelease): Long
    suspend fun query(id: Long): UpdateDownloadSnapshot
    suspend fun remove(id: Long)
}

fun interface AppUpdateVerifier {
    suspend fun verify(release: AppRelease)
}

class AppUpdateManager(
    private val currentVersion: String,
    private val currentBuild: Int,
    private val source: AppUpdateSource,
    private val downloader: AppUpdateDownloader,
    private val verifier: AppUpdateVerifier,
    private val store: PendingAppUpdateStore,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var verifiedRelease: AppRelease? = null

    init {
        store.read()?.let { pending ->
            if (compareReleaseVersions(pending.release.version, pending.release.build, currentVersion, currentBuild) <= 0) {
                operation = scope.launch {
                    runCatching { downloader.remove(pending.downloadId) }
                    store.clear()
                }
            } else {
                mutableState.value = AppUpdateState(UpdateStage.DOWNLOADING, pending.release)
                operation = scope.launch { monitor(pending) }
            }
        }
    }

    fun check() {
        if (operation?.isActive == true) return
        mutableState.value = AppUpdateState(UpdateStage.CHECKING)
        operation = scope.launch {
            try {
                val release = source.latest()
                mutableState.value = AppUpdateState(
                    stage = when {
                        compareReleaseVersions(release.version, release.build, currentVersion, currentBuild) <= 0 -> UpdateStage.CURRENT
                        release == verifiedRelease -> UpdateStage.READY
                        else -> UpdateStage.AVAILABLE
                    },
                    release = release,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = AppUpdateState(UpdateStage.ERROR, message = "检查更新失败：${error.message ?: "请检查网络后重试"}")
            }
        }
    }

    fun download() {
        if (operation?.isActive == true) return
        val release = state.value.release ?: return
        if (state.value.stage !in setOf(UpdateStage.AVAILABLE, UpdateStage.ERROR, UpdateStage.READY)) return
        mutableState.value = AppUpdateState(UpdateStage.DOWNLOADING, release)
        operation = scope.launch {
            try {
                store.read()?.let { downloader.remove(it.downloadId) }
                store.clear()
                verifiedRelease = null
                val pending = withContext(NonCancellable) {
                    PendingAppUpdate(downloader.enqueue(release), release).also(store::write)
                }
                currentCoroutineContext().ensureActive()
                monitor(pending)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failDownload(release, error)
            }
        }
    }

    fun cancelDownload() {
        if (state.value.stage != UpdateStage.DOWNLOADING) return
        val release = state.value.release
        val previous = operation
        previous?.cancel()
        operation = scope.launch {
            previous?.join()
            store.read()?.let { runCatching { downloader.remove(it.downloadId) } }
            store.clear()
            mutableState.value = AppUpdateState(UpdateStage.AVAILABLE, release)
        }
    }

    private suspend fun monitor(pending: PendingAppUpdate) {
        try {
            while (true) {
                val snapshot = downloader.query(pending.downloadId)
                when (snapshot.status) {
                    UpdateDownloadStatus.COMPLETE -> {
                        mutableState.value = AppUpdateState(UpdateStage.VERIFYING, pending.release)
                        verifier.verify(pending.release)
                        verifiedRelease = pending.release
                        mutableState.value = AppUpdateState(UpdateStage.READY, pending.release, progress = 1f)
                        return
                    }
                    UpdateDownloadStatus.FAILED -> error("下载失败，请检查网络或可用存储空间后重试")
                    else -> mutableState.value = AppUpdateState(
                        UpdateStage.DOWNLOADING, pending.release,
                        progress = snapshot.total.takeIf { it > 0 }?.let {
                            (snapshot.downloaded.toDouble() / it).toFloat().coerceIn(0f, 1f)
                        },
                        message = if (snapshot.status == UpdateDownloadStatus.WAITING) "等待网络或系统继续下载…" else null,
                    )
                }
                delay(1_000)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failDownload(pending.release, error)
        }
    }

    private suspend fun failDownload(release: AppRelease, error: Exception) {
        store.read()?.let { runCatching { downloader.remove(it.downloadId) } }
        store.clear()
        verifiedRelease = null
        mutableState.value = AppUpdateState(
            UpdateStage.ERROR, release, message = error.message ?: "下载失败，请重试", retryDownload = true,
        )
    }
}
