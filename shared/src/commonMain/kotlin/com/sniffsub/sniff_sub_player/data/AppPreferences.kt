package com.sniffsub.sniff_sub_player.data

import com.sniffsub.sniff_sub_player.platform.randomId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class Bookmark(
    val id: String,
    val title: String,
    val url: String,
    val createdAtEpochMs: Long,
)

@Serializable
data class StoredSubscription(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val builtIn: Boolean,
)

@Serializable
data class AppPreferences(
    val userAgent: String = "",
    val accentArgb: Long = 0xFFE8A7BFL,
    val bookmarks: List<Bookmark> = emptyList(),
    val adBlockEnabled: Boolean = false,
    val automaticUpdatesEnabled: Boolean = true,
    val adBlockConfigurationNeedsUpdate: Boolean = false,
    val subscriptions: List<StoredSubscription> = emptyList(),
    val compiledFilters: List<String> = emptyList(),
    val subscriptionRuleSources: Map<String, String> = emptyMap(),
    val sourceRulesCount: Int = 0,
    val convertedRulesCount: Int = 0,
    val discardedRulesCount: Int = 0,
    val lastUpdatedAtEpochMs: Long? = null,
)

interface AppPreferencesRepository {
    val values: Flow<AppPreferences>

    suspend fun update(transform: (AppPreferences) -> AppPreferences)
}

object AppPreferencesCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(preferences: AppPreferences): String = json.encodeToString(preferences)

    fun decode(value: String?): AppPreferences {
        if (value == null) return AppPreferences()

        return try {
            json.decodeFromString<AppPreferences>(value)
        } catch (_: SerializationException) {
            AppPreferences()
        }
    }
}

fun reduceBookmarkToggle(
    bookmarks: List<Bookmark>,
    url: String,
    title: String,
    now: Long,
): List<Bookmark> {
    val normalizedUrl = url.trim()
    if (normalizedUrl.isBlank()) return bookmarks

    if (bookmarks.any { it.url == normalizedUrl }) {
        return bookmarks.filterNot { it.url == normalizedUrl }
    }

    return listOf(
        Bookmark(
            id = randomId(),
            title = title.ifBlank { normalizedUrl },
            url = normalizedUrl,
            createdAtEpochMs = now,
        ),
    ) + bookmarks
}

