package com.sniffsub.sniff_sub_player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPreferencesCodecTest {
    @Test
    fun corrupt_json_falls_back_to_defaults() {
        assertEquals(AppPreferences(), AppPreferencesCodec.decode("not-json"))
    }

    @Test
    fun encoded_preferences_round_trip_without_losing_values() {
        val preferences = AppPreferences(
            userAgent = "SniffSubPlayer/1.1",
            accentArgb = 0xFF123456L,
            bookmarks = listOf(
                Bookmark(
                    id = "bookmark-1",
                    title = "Example",
                    url = "https://example.test/video",
                    createdAtEpochMs = 17L,
                ),
            ),
            adBlockEnabled = true,
            automaticUpdatesEnabled = false,
            adBlockConfigurationNeedsUpdate = true,
            subscriptions = listOf(
                StoredSubscription(
                    id = "subscription-1",
                    name = "Built in",
                    url = "https://example.test/filters.txt",
                    enabled = true,
                    builtIn = true,
                ),
            ),
            compiledFilters = listOf("||ads.example.test^"),
            sourceRulesCount = 12,
            convertedRulesCount = 10,
            discardedRulesCount = 2,
            lastUpdatedAtEpochMs = 23L,
        )

        assertEquals(preferences, AppPreferencesCodec.decode(AppPreferencesCodec.encode(preferences)))
    }
}
