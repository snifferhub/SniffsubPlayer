package com.sniffsub.sniff_sub_player.adblock

import com.sniffsub.sniff_sub_player.data.AppPreferences
import com.sniffsub.sniff_sub_player.data.AppPreferencesRepository
import com.sniffsub.sniff_sub_player.data.StoredSubscription
import com.sniffsub.sniff_sub_player.util.HttpFetcher
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private fun subscriptionsWithOnly(custom: StoredSubscription): List<StoredSubscription> =
        AdBlockRepository.builtInSubscriptions.map { builtIn ->
            StoredSubscription(
                id = builtIn.id,
                name = builtIn.name,
                url = builtIn.url,
                enabled = false,
                builtIn = true,
            )
        } + custom
}

private class FakePreferencesRepository(initial: AppPreferences) : AppPreferencesRepository {
    private val valuesFlow = MutableStateFlow(initial)

    override val values: Flow<AppPreferences> = valuesFlow
    val current: AppPreferences get() = valuesFlow.value
    var updateCount: Int = 0
        private set

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        updateCount += 1
        valuesFlow.value = transform(valuesFlow.value)
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
