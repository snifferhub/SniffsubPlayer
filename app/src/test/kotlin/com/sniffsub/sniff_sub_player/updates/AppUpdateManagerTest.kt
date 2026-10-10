package com.sniffsub.sniff_sub_player.updates

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateManagerTest {
    @Test
    fun current_release_and_newer_build_are_distinguished() = runTest {
        val store = MemoryUpdateStore()
        val downloader = FakeUpdateDownloader()
        var latest = sampleRelease(18)
        val manager = AppUpdateManager("1.1.0", 18, { latest }, downloader, {}, store, backgroundScope)
        manager.check(); runCurrent()
        assertEquals(UpdateStage.CURRENT, manager.state.value.stage)
        latest = sampleRelease(19)
        manager.check(); runCurrent()
        assertEquals(UpdateStage.AVAILABLE, manager.state.value.stage)
        assertEquals(0, downloader.enqueues)
    }

    @Test
    fun duplicate_taps_do_not_duplicate_checks_or_downloads_and_completion_is_verified() = runTest {
        val store = MemoryUpdateStore()
        val downloader = FakeUpdateDownloader()
        var checks = 0
        var verifications = 0
        val manager = AppUpdateManager("1.1.0", 18, { checks++; sampleRelease() }, downloader, { verifications++ }, store, backgroundScope)
        manager.check(); manager.check(); runCurrent()
        assertEquals(1, checks)
        manager.download(); manager.download(); runCurrent()
        assertEquals(1, downloader.enqueues)
        assertEquals(0.5f, manager.state.value.progress!!, 0.001f)
        assertEquals(UpdateStage.DOWNLOADING, manager.state.value.stage)
        downloader.snapshot = UpdateDownloadSnapshot(UpdateDownloadStatus.COMPLETE)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(UpdateStage.READY, manager.state.value.stage)
        assertEquals(1, verifications)
        assertNotNull(store.pending)
        manager.check(); runCurrent()
        assertEquals(UpdateStage.READY, manager.state.value.stage)
    }

    @Test
    fun downloaded_update_is_restored_and_reverified_after_process_restart() = runTest {
        val store = MemoryUpdateStore().apply { pending = PendingAppUpdate(77, sampleRelease()) }
        val downloader = FakeUpdateDownloader().apply { snapshot = UpdateDownloadSnapshot(UpdateDownloadStatus.COMPLETE) }
        var verifications = 0
        val manager = AppUpdateManager("1.1.0", 18, { error("No network check needed") }, downloader, { verifications++ }, store, backgroundScope)
        runCurrent()
        assertEquals(UpdateStage.READY, manager.state.value.stage)
        assertEquals(0, downloader.enqueues)
        assertEquals(1, verifications)
    }

    @Test
    fun bad_checksum_never_becomes_installable_and_failed_download_can_be_retried() = runTest {
        val store = MemoryUpdateStore()
        val downloader = FakeUpdateDownloader().apply { snapshot = UpdateDownloadSnapshot(UpdateDownloadStatus.COMPLETE) }
        var reject = true
        val manager = AppUpdateManager("1.1.0", 18, { sampleRelease() }, downloader, {
            check(!reject) { "校验失败" }
        }, store, backgroundScope)
        manager.check(); runCurrent()
        manager.download(); runCurrent()
        assertEquals(UpdateStage.ERROR, manager.state.value.stage)
        assertTrue(manager.state.value.retryDownload)
        assertNull(store.pending)
        assertEquals(listOf(77L), downloader.removed)
        reject = false
        manager.download(); runCurrent()
        assertEquals(UpdateStage.READY, manager.state.value.stage)
        assertEquals(2, downloader.enqueues)
    }

    @Test
    fun cancelling_during_enqueue_waits_then_removes_system_download() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryUpdateStore()
        val downloader = FakeUpdateDownloader().apply { beforeEnqueue = { gate.await() } }
        val manager = AppUpdateManager("1.1.0", 18, { sampleRelease() }, downloader, {}, store, backgroundScope)
        manager.check(); runCurrent()
        manager.download(); runCurrent()
        manager.cancelDownload(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertNull(store.pending)
        assertEquals(listOf(77L), downloader.removed)
        assertEquals(UpdateStage.AVAILABLE, manager.state.value.stage)
    }

    @Test
    fun network_failure_exposes_retry_without_downloading() = runTest {
        val downloader = FakeUpdateDownloader()
        val manager = AppUpdateManager("1.1.0", 18, { error("HTTP 403") }, downloader, {}, MemoryUpdateStore(), backgroundScope)
        manager.check(); runCurrent()
        assertEquals(UpdateStage.ERROR, manager.state.value.stage)
        assertTrue(manager.state.value.message!!.contains("HTTP 403"))
        assertFalse(manager.state.value.retryDownload)
        assertEquals(0, downloader.enqueues)
    }
}

private class MemoryUpdateStore : PendingAppUpdateStore {
    var pending: PendingAppUpdate? = null
    override fun read() = pending
    override fun write(update: PendingAppUpdate) { pending = update }
    override fun clear() { pending = null }
}

private class FakeUpdateDownloader : AppUpdateDownloader {
    var enqueues = 0
    var snapshot = UpdateDownloadSnapshot(UpdateDownloadStatus.RUNNING, 50, 100)
    val removed = mutableListOf<Long>()
    var beforeEnqueue: suspend () -> Unit = {}
    override suspend fun enqueue(release: AppRelease): Long { beforeEnqueue(); enqueues++; return 77 }
    override suspend fun query(id: Long) = snapshot
    override suspend fun remove(id: Long) { removed += id }
}
