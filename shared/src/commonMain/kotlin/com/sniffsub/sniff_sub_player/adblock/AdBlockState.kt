package com.sniffsub.sniff_sub_player.adblock

data class AdBlockSubscription(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
)

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
    val rulesRevision: Long = 0,
)
