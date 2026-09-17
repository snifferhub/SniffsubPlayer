package com.sniffsub.sniff_sub_player.adblock

data class AdBlockSubscription(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
)

data class CompiledRuleSet(
    val patterns: List<String>,
    val sourceCount: Int,
    val convertedCount: Int,
    val discardedCount: Int,
) {
    private val matchers = patterns.map(::Regex)

    fun blocks(url: String): Boolean = matchers.any { matcher ->
        matcher.containsMatchIn(url)
    }
}

data class AdBlockState(
    val isEnabled: Boolean = false,
    val automaticUpdatesEnabled: Boolean = true,
    val subscriptions: List<AdBlockSubscription> = emptyList(),
    val isUpdating: Boolean = false,
    val configurationNeedsUpdate: Boolean = false,
    val sourceRulesCount: Int = 0,
    val convertedRulesCount: Int = 0,
    val discardedRulesCount: Int = 0,
    val lastUpdatedAtEpochMs: Long? = null,
    val errorMessage: String? = null,
)
