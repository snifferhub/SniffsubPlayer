package com.sniffsub.sniff_sub_player.adblock

import com.sniffsub.sniff_sub_player.data.AppPreferences
import com.sniffsub.sniff_sub_player.data.AppPreferencesRepository
import com.sniffsub.sniff_sub_player.data.StoredSubscription
import com.sniffsub.sniff_sub_player.util.HttpFetcher
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AdBlockRepository(
    private val preferencesRepository: AppPreferencesRepository,
    private val httpFetcher: HttpFetcher,
    private val compiler: AdRuleCompiler = AdRuleCompiler(),
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val ruleDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableState = MutableStateFlow(AdBlockState())
    private val compiledRules = AtomicReference(EMPTY_RULES)
    private val operationMutex = Mutex()

    val state: StateFlow<AdBlockState> = mutableState.asStateFlow()

    fun blocks(url: String): Boolean = mutableState.value.isEnabled && compiledRules.get().blocks(url)

    suspend fun restore() {
        val shouldRefresh = operationMutex.withLock {
            val preferences = preferencesRepository.values.first()
            val subscriptions = mergeBuiltInSubscriptions(preferences.subscriptions)
            val restoredRules = withContext(ruleDispatcher) { restoreRules(preferences) }
            val storedSubscriptions = subscriptions.map(AdBlockSubscription::toStored)
            val subscriptionsChanged = storedSubscriptions != preferences.subscriptions
            val configurationNeedsUpdate =
                preferences.adBlockConfigurationNeedsUpdate || subscriptionsChanged

            compiledRules.set(restoredRules)
            mutableState.value = AdBlockState(
                isEnabled = preferences.adBlockEnabled,
                automaticUpdatesEnabled = preferences.automaticUpdatesEnabled,
                subscriptions = subscriptions,
                configurationNeedsUpdate = configurationNeedsUpdate,
                sourceRulesCount = restoredRules.sourceCount,
                convertedRulesCount = restoredRules.convertedCount,
                discardedRulesCount = restoredRules.discardedCount,
                lastUpdatedAtEpochMs = preferences.lastUpdatedAtEpochMs,
            )

            if (subscriptionsChanged) {
                preferencesRepository.update { current ->
                    current.copy(
                        subscriptions = storedSubscriptions,
                        adBlockConfigurationNeedsUpdate = true,
                    )
                }
            }

            preferences.adBlockEnabled && preferences.automaticUpdatesEnabled
        }
        if (shouldRefresh) refresh()
    }

    suspend fun addSubscription(rawUrl: String): Result<Unit> = runCatching {
        operationMutex.withLock {
            val normalizedUrl = validateCustomSubscriptionUrl(rawUrl)
            val current = mutableState.value
            if (current.subscriptions.any { it.url.equals(normalizedUrl, ignoreCase = true) }) {
                throw IllegalArgumentException("该订阅已经存在")
            }

            val uri = URI(normalizedUrl)
            val updated = current.subscriptions + AdBlockSubscription(
                id = UUID.randomUUID().toString(),
                name = uri.host.removePrefix("raw."),
                url = normalizedUrl,
            )
            persistSubscriptions(updated, configurationNeedsUpdate = true)
            mutableState.value = current.copy(
                subscriptions = updated,
                configurationNeedsUpdate = true,
                errorMessage = null,
            )
        }
    }

    suspend fun removeSubscription(id: String) = operationMutex.withLock {
        val current = mutableState.value
        val target = current.subscriptions.firstOrNull { it.id == id } ?: return@withLock
        if (target.builtIn) return@withLock

        val updated = current.subscriptions.filterNot { it.id == id }
        persistSubscriptions(updated, configurationNeedsUpdate = true)
        mutableState.value = current.copy(
            subscriptions = updated,
            configurationNeedsUpdate = true,
        )
    }

    suspend fun setSubscriptionEnabled(id: String, enabled: Boolean) = operationMutex.withLock {
        val current = mutableState.value
        if (current.subscriptions.none { it.id == id }) return@withLock

        val updated = current.subscriptions.map { subscription ->
            if (subscription.id == id) subscription.copy(enabled = enabled) else subscription
        }
        persistSubscriptions(updated, configurationNeedsUpdate = true)
        mutableState.value = current.copy(
            subscriptions = updated,
            configurationNeedsUpdate = true,
        )
    }

    suspend fun setEnabled(enabled: Boolean) = operationMutex.withLock {
        preferencesRepository.update { current -> current.copy(adBlockEnabled = enabled) }
        mutableState.value = mutableState.value.copy(isEnabled = enabled)
    }

    suspend fun setAutomaticUpdatesEnabled(enabled: Boolean) = operationMutex.withLock {
        preferencesRepository.update { current -> current.copy(automaticUpdatesEnabled = enabled) }
        mutableState.value = mutableState.value.copy(automaticUpdatesEnabled = enabled)
    }

    suspend fun refresh(): Result<Unit> = operationMutex.withLock {
        val subscriptions = mutableState.value.subscriptions.filter(AdBlockSubscription::enabled)
        mutableState.value = mutableState.value.copy(isUpdating = true, errorMessage = null)
        val outcome = runCatching {
            val sources = subscriptions.map { subscription ->
                httpFetcher.get(
                    url = subscription.url,
                    headers = mapOf(USER_AGENT_HEADER to USER_AGENT_VALUE),
                    timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS,
                    maximumBytes = MAXIMUM_DOWNLOAD_BYTES,
                )
            }
            val candidate = withContext(ruleDispatcher) {
                val lines = sources.asSequence().flatMap { bytes ->
                    bytes.toString(Charsets.UTF_8).lineSequence()
                }
                compiler.compile(lines)
            }
            val updatedAt = nowEpochMs()

            preferencesRepository.update { current ->
                current.copy(
                    compiledFilters = candidate.patterns,
                    sourceRulesCount = candidate.sourceCount,
                    convertedRulesCount = candidate.convertedCount,
                    discardedRulesCount = candidate.discardedCount,
                    lastUpdatedAtEpochMs = updatedAt,
                    adBlockConfigurationNeedsUpdate = false,
                )
            }

            compiledRules.set(candidate)
            mutableState.value = mutableState.value.copy(
                isUpdating = false,
                configurationNeedsUpdate = false,
                sourceRulesCount = candidate.sourceCount,
                convertedRulesCount = candidate.convertedCount,
                discardedRulesCount = candidate.discardedCount,
                lastUpdatedAtEpochMs = updatedAt,
                errorMessage = null,
            )
        }

        outcome.onFailure { error ->
            mutableState.value = mutableState.value.copy(
                isUpdating = false,
                errorMessage = "规则更新失败: ${error.message ?: error::class.simpleName.orEmpty()}",
            )
        }
        outcome
    }

    private suspend fun persistSubscriptions(
        subscriptions: List<AdBlockSubscription>,
        configurationNeedsUpdate: Boolean,
    ) {
        preferencesRepository.update { current ->
            current.copy(
                subscriptions = subscriptions.map(AdBlockSubscription::toStored),
                adBlockConfigurationNeedsUpdate = configurationNeedsUpdate,
            )
        }
    }

    private fun restoreRules(preferences: AppPreferences): CompiledRuleSet {
        val validPatterns = preferences.compiledFilters.filter { pattern ->
            runCatching { Regex(pattern) }.isSuccess
        }
        return CompiledRuleSet(
            patterns = validPatterns,
            sourceCount = preferences.sourceRulesCount,
            convertedCount = validPatterns.size,
            discardedCount = preferences.discardedRulesCount +
                (preferences.compiledFilters.size - validPatterns.size),
        )
    }

    private fun mergeBuiltInSubscriptions(
        stored: List<StoredSubscription>,
    ): List<AdBlockSubscription> {
        val restored = stored.map(StoredSubscription::toDomain)
        val mergedBuiltIns = builtInSubscriptions.map { builtIn ->
            val saved = restored.firstOrNull { candidate ->
                candidate.id == builtIn.id || candidate.url.equals(builtIn.url, ignoreCase = true)
            }
            builtIn.copy(enabled = saved?.enabled ?: builtIn.enabled)
        }
        val custom = restored.filterNot { candidate ->
            builtInSubscriptions.any { builtIn ->
                candidate.id == builtIn.id || candidate.url.equals(builtIn.url, ignoreCase = true)
            }
        }.map { it.copy(builtIn = false) }
        return mergedBuiltIns + custom
    }

    private fun validateCustomSubscriptionUrl(rawUrl: String): String {
        val normalized = rawUrl.trim()
        val uri = runCatching { URI(normalized) }.getOrNull()
        if (uri == null || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("请输入有效的 HTTPS raw 规则链接")
        }
        return uri.toString()
    }

    companion object {
        const val DOWNLOAD_TIMEOUT_MILLIS = 45_000L
        const val MAXIMUM_DOWNLOAD_BYTES = 12 * 1024 * 1024

        val builtInSubscriptions: List<AdBlockSubscription> = listOf(
            AdBlockSubscription(
                id = "builtin-adguard-adservers",
                name = "AdGuard 通用广告服务器",
                url = "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/" +
                    "master/BaseFilter/sections/adservers.txt",
                builtIn = true,
            ),
            AdBlockSubscription(
                id = "builtin-easylist-china",
                name = "EasyList China 中文规则",
                url = "https://raw.githubusercontent.com/easylist/easylistchina/" +
                    "master/easylistchina.txt",
                builtIn = true,
            ),
            AdBlockSubscription(
                id = "builtin-adguard-tracking",
                name = "AdGuard 隐私追踪防护",
                url = "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/" +
                    "master/SpywareFilter/sections/tracking_servers.txt",
                builtIn = true,
            ),
        )

        private val EMPTY_RULES = CompiledRuleSet(emptyList(), 0, 0, 0)
        private const val USER_AGENT_HEADER = "User-Agent"
        private const val USER_AGENT_VALUE = "SniffSubPlayer/1.1 Android"
    }
}

private fun StoredSubscription.toDomain(): AdBlockSubscription = AdBlockSubscription(
    id = id,
    name = name,
    url = url,
    enabled = enabled,
    builtIn = builtIn,
)

private fun AdBlockSubscription.toStored(): StoredSubscription = StoredSubscription(
    id = id,
    name = name,
    url = url,
    enabled = enabled,
    builtIn = builtIn,
)
