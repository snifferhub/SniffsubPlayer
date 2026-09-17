package com.sniffsub.sniff_sub_player.adblock

import com.sniffsub.sniff_sub_player.data.AppPreferences
import com.sniffsub.sniff_sub_player.data.AppPreferencesRepository
import com.sniffsub.sniff_sub_player.data.StoredSubscription
import com.sniffsub.sniff_sub_player.util.HttpFetcher
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockRepositoryTest {
    @Test
    fun failed_refresh_keeps_last_valid_rules() = runTest {
        val saved = CompiledRuleSet(listOf("ads\\.example"), 1, 1, 0)
        val repository = repositoryWith(
            saved,
            fetchFailure = IOException("offline"),
            adBlockEnabled = true,
            automaticUpdatesEnabled = false,
        )
        repository.restore()

        assertTrue(repository.refresh().isFailure)
        assertTrue(repository.blocks("https://ads.example/a"))
        assertEquals("规则更新失败: offline", repository.state.value.errorMessage)
    }

    @Test
    fun custom_subscriptions_require_https_and_reject_duplicates() = runTest {
        val repository = repositoryWith(CompiledRuleSet(emptyList(), 0, 0, 0))
        repository.restore()

        assertTrue(repository.addSubscription("http://filters.example/list.txt").isFailure)
        assertTrue(repository.addSubscription("https://filters.example/list.txt").isSuccess)
        assertTrue(repository.addSubscription("https://filters.example/list.txt").isFailure)
        assertEquals(4, repository.state.value.subscriptions.size)
    }

    @Test
    fun built_in_subscriptions_cannot_be_removed() = runTest {
        val repository = repositoryWith(CompiledRuleSet(emptyList(), 0, 0, 0))
        repository.restore()
        val builtInId = repository.state.value.subscriptions.first().id

        repository.removeSubscription(builtInId)

        assertTrue(repository.state.value.subscriptions.any { it.id == builtInId })
    }

    @Test
    fun restore_adds_the_three_expected_built_in_subscriptions() = runTest {
        val repository = repositoryWith(CompiledRuleSet(emptyList(), 0, 0, 0))

        repository.restore()

        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/BaseFilter/sections/adservers.txt",
                "https://raw.githubusercontent.com/easylist/easylistchina/master/easylistchina.txt",
                "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/SpywareFilter/sections/tracking_servers.txt",
            ),
            repository.state.value.subscriptions.take(3).map { it.url },
        )
    }

    @Test
    fun refresh_uses_the_download_timeout_and_response_limit() = runTest {
        val fetcher = FakeHttpFetcher(responses = listOf("ads.example".encodeToByteArray()))
        val repository = repositoryWith(
            CompiledRuleSet(emptyList(), 0, 0, 0),
            fetcher = fetcher,
            subscriptions = subscriptionsWithOnly(subscription("one")),
            adBlockEnabled = true,
            automaticUpdatesEnabled = false,
        )
        repository.restore()

        assertTrue(repository.refresh().isSuccess)

        assertEquals(45_000L, fetcher.requests.single().timeoutMillis)
        assertEquals(12 * 1024 * 1024, fetcher.requests.single().maximumBytes)
        assertTrue(repository.blocks("https://ads.example/banner"))
    }

    @Test
    fun enabled_restore_automatically_refreshes_rules() = runTest {
        val fetcher = FakeHttpFetcher(responses = listOf("ads.example".encodeToByteArray()))
        val repository = repositoryWith(
            CompiledRuleSet(emptyList(), 0, 0, 0),
            fetcher = fetcher,
            subscriptions = subscriptionsWithOnly(subscription("one")),
            adBlockEnabled = true,
            automaticUpdatesEnabled = true,
        )

        repository.restore()

        assertEquals(1, fetcher.requests.size)
        assertTrue(repository.blocks("https://ads.example/banner"))
    }

    @Test
    fun refresh_persists_rules_only_after_every_subscription_succeeds() = runTest {
        val preferences = FakePreferencesRepository(
            AppPreferences(
                adBlockEnabled = true,
                automaticUpdatesEnabled = false,
                subscriptions = listOf(subscription("one"), subscription("two")),
                compiledFilters = listOf("old\\.example"),
                sourceRulesCount = 1,
                convertedRulesCount = 1,
            ),
        )
        val repository = AdBlockRepository(
            preferencesRepository = preferences,
            httpFetcher = FakeHttpFetcher(
                responses = listOf("new.example".encodeToByteArray()),
                failure = IOException("second source unavailable"),
            ),
        )
        repository.restore()

        assertTrue(repository.refresh().isFailure)

        assertEquals(listOf("old\\.example"), preferences.current.compiledFilters)
        assertTrue(repository.blocks("https://old.example/banner"))
        assertFalse(repository.blocks("https://new.example/banner"))
    }

    @Test
    fun successful_refresh_persists_rules_and_stats_atomically() = runTest {
        val preferences = FakePreferencesRepository(
            AppPreferences(
                adBlockEnabled = true,
                automaticUpdatesEnabled = false,
                subscriptions = subscriptionsWithOnly(subscription("one")),
            ),
        )
        val repository = AdBlockRepository(
            preferencesRepository = preferences,
            httpFetcher = FakeHttpFetcher(
                responses = listOf("! metadata\nads.example\nads.example".encodeToByteArray()),
            ),
            nowEpochMs = { 37L },
        )
        repository.restore()
        val updatesBeforeRefresh = preferences.updateCount

        assertTrue(repository.refresh().isSuccess)

        assertEquals(updatesBeforeRefresh + 1, preferences.updateCount)
        assertEquals(listOf("ads\\.example"), preferences.current.compiledFilters)
        assertEquals(3, preferences.current.sourceRulesCount)
        assertEquals(1, preferences.current.convertedRulesCount)
        assertEquals(1, preferences.current.discardedRulesCount)
        assertEquals(37L, preferences.current.lastUpdatedAtEpochMs)
        assertTrue(repository.blocks("https://ads.example/banner"))
    }

    @Test
    fun concurrent_subscription_additions_do_not_lose_each_other() = runTest {
        val preferences = FakePreferencesRepository(
            AppPreferences(
                automaticUpdatesEnabled = false,
                subscriptions = subscriptionsWithOnly().map { it.copy(enabled = false) },
            ),
        )
        val repository = AdBlockRepository(preferences, FakeHttpFetcher())
        repository.restore()
        preferences.blockUpdates()

        val first = async { repository.addSubscription("https://one.example/list.txt") }
        preferences.awaitUpdateStarted()
        val second = async { repository.addSubscription("https://two.example/list.txt") }
        testScheduler.runCurrent()
        assertFalse(second.isCompleted)
        preferences.releaseUpdates()

        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(
            setOf("https://one.example/list.txt", "https://two.example/list.txt"),
            repository.state.value.subscriptions.filterNot { it.builtIn }.map { it.url }.toSet(),
        )
        assertEquals(
            setOf("https://one.example/list.txt", "https://two.example/list.txt"),
            preferences.current.subscriptions.filterNot { it.builtIn }.map { it.url }.toSet(),
        )
    }

    @Test
    fun subscription_change_waits_for_refresh_then_marks_configuration_dirty() = runTest {
        val preferences = FakePreferencesRepository(
            AppPreferences(
                adBlockEnabled = true,
                automaticUpdatesEnabled = false,
                subscriptions = subscriptionsWithOnly(subscription("one")),
            ),
        )
        val fetcher = SuspendingHttpFetcher("new.example".encodeToByteArray())
        val repository = AdBlockRepository(preferences, fetcher)
        repository.restore()

        val refresh = async { repository.refresh() }
        fetcher.awaitStarted()
        val disable = launch { repository.setSubscriptionEnabled("one", false) }
        testScheduler.runCurrent()
        assertFalse(disable.isCompleted)

        fetcher.release()
        assertTrue(refresh.await().isSuccess)
        disable.join()

        assertFalse(repository.state.value.subscriptions.single { it.id == "one" }.enabled)
        assertTrue(repository.state.value.configurationNeedsUpdate)
        assertTrue(preferences.current.adBlockConfigurationNeedsUpdate)
    }

    @Test
    fun restore_preserves_persisted_configuration_mismatch() = runTest {
        val preferences = FakePreferencesRepository(
            AppPreferences(
                automaticUpdatesEnabled = false,
                subscriptions = subscriptionsWithOnly(subscription("one")),
            ),
        )
        val firstRepository = AdBlockRepository(preferences, FakeHttpFetcher())
        firstRepository.restore()
        firstRepository.setSubscriptionEnabled("one", false)

        val restoredRepository = AdBlockRepository(preferences, FakeHttpFetcher())
        restoredRepository.restore()

        assertTrue(restoredRepository.state.value.configurationNeedsUpdate)
    }

    @Test
    fun refresh_with_no_enabled_sources_clears_the_last_valid_snapshot() = runTest {
        val preferences = FakePreferencesRepository(
            AppPreferences(
                adBlockEnabled = true,
                automaticUpdatesEnabled = false,
                subscriptions = subscriptionsWithOnly().map { it.copy(enabled = false) },
                compiledFilters = listOf("old\\.example"),
                sourceRulesCount = 1,
                convertedRulesCount = 1,
                adBlockConfigurationNeedsUpdate = true,
            ),
        )
        val repository = AdBlockRepository(preferences, FakeHttpFetcher())
        repository.restore()
        assertTrue(repository.blocks("https://old.example/banner"))

        assertTrue(repository.refresh().isSuccess)

        assertFalse(repository.blocks("https://old.example/banner"))
        assertEquals(emptyList<String>(), preferences.current.compiledFilters)
        assertEquals(0, preferences.current.sourceRulesCount)
        assertEquals(0, preferences.current.convertedRulesCount)
        assertFalse(preferences.current.adBlockConfigurationNeedsUpdate)
        assertFalse(repository.state.value.configurationNeedsUpdate)
    }

    @Test
    fun rule_snapshot_construction_uses_the_injected_dispatcher() = runTest {
        val ruleDispatcher = QueueingDispatcher()
        val preferences = FakePreferencesRepository(
            AppPreferences(
                adBlockEnabled = true,
                automaticUpdatesEnabled = false,
                subscriptions = subscriptionsWithOnly(subscription("one")),
                compiledFilters = listOf("old\\.example"),
                sourceRulesCount = 1,
                convertedRulesCount = 1,
            ),
        )
        val repository = AdBlockRepository(
            preferencesRepository = preferences,
            httpFetcher = FakeHttpFetcher(responses = listOf("new.example".encodeToByteArray())),
            ruleDispatcher = ruleDispatcher,
        )

        val restore = launch { repository.restore() }
        testScheduler.runCurrent()
        assertFalse(restore.isCompleted)
        ruleDispatcher.runAll()
        testScheduler.advanceUntilIdle()
        assertTrue(restore.isCompleted)

        val refresh = launch { repository.refresh() }
        testScheduler.runCurrent()
        assertFalse(refresh.isCompleted)
        ruleDispatcher.runAll()
        testScheduler.advanceUntilIdle()
        assertTrue(refresh.isCompleted)
        assertTrue(repository.blocks("https://new.example/banner"))
    }

    private fun repositoryWith(
        saved: CompiledRuleSet,
        fetchFailure: IOException? = null,
        fetcher: FakeHttpFetcher = FakeHttpFetcher(failure = fetchFailure),
        subscriptions: List<StoredSubscription> = AdBlockRepository.builtInSubscriptions.map {
            StoredSubscription(it.id, it.name, it.url, it.enabled, it.builtIn)
        },
        adBlockEnabled: Boolean = false,
        automaticUpdatesEnabled: Boolean = true,
    ): AdBlockRepository = AdBlockRepository(
        preferencesRepository = FakePreferencesRepository(
            AppPreferences(
                adBlockEnabled = adBlockEnabled,
                automaticUpdatesEnabled = automaticUpdatesEnabled,
                subscriptions = subscriptions,
                compiledFilters = saved.patterns,
                sourceRulesCount = saved.sourceCount,
                convertedRulesCount = saved.convertedCount,
                discardedRulesCount = saved.discardedCount,
            ),
        ),
        httpFetcher = fetcher,
    )

    private fun subscription(id: String): StoredSubscription = StoredSubscription(
        id = id,
        name = id,
        url = "https://filters.example/$id.txt",
        enabled = true,
        builtIn = false,
    )

    private fun subscriptionsWithOnly(custom: StoredSubscription? = null): List<StoredSubscription> =
        AdBlockRepository.builtInSubscriptions.map { builtIn ->
            StoredSubscription(
                id = builtIn.id,
                name = builtIn.name,
                url = builtIn.url,
                enabled = false,
                builtIn = true,
            )
        } + listOfNotNull(custom)
}

private class FakePreferencesRepository(initial: AppPreferences) : AppPreferencesRepository {
    private val valuesFlow = MutableStateFlow(initial)
    private val persistenceMutex = Mutex()
    private var updateGate: CompletableDeferred<Unit>? = null
    private var updateStarted = CompletableDeferred<Unit>()

    override val values: Flow<AppPreferences> = valuesFlow
    val current: AppPreferences get() = valuesFlow.value
    var updateCount: Int = 0
        private set

    fun blockUpdates() {
        updateGate = CompletableDeferred()
        updateStarted = CompletableDeferred()
    }

    suspend fun awaitUpdateStarted() {
        updateStarted.await()
    }

    fun releaseUpdates() {
        updateGate?.complete(Unit)
        updateGate = null
    }

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        updateStarted.complete(Unit)
        updateGate?.await()
        persistenceMutex.withLock {
            updateCount += 1
            valuesFlow.value = transform(valuesFlow.value)
        }
    }
}

private class FakeHttpFetcher(
    private val responses: List<ByteArray> = emptyList(),
    private val failure: IOException? = null,
) : HttpFetcher {
    data class Request(
        val url: String,
        val headers: Map<String, String>,
        val timeoutMillis: Long,
        val maximumBytes: Int,
    )

    val requests = mutableListOf<Request>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        maximumBytes: Int,
    ): ByteArray {
        requests += Request(url, headers, timeoutMillis, maximumBytes)
        if (failure != null && requests.size > responses.size) throw failure
        return responses.getOrElse(requests.lastIndex) { "ads.example".encodeToByteArray() }
    }
}

private class SuspendingHttpFetcher(
    private val response: ByteArray,
) : HttpFetcher {
    private val started = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()

    suspend fun awaitStarted() {
        started.await()
    }

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        maximumBytes: Int,
    ): ByteArray {
        started.complete(Unit)
        gate.await()
        return response
    }
}

private class QueueingDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runAll() {
        while (true) {
            val task = tasks.pollFirst() ?: return
            task.run()
        }
    }
}
