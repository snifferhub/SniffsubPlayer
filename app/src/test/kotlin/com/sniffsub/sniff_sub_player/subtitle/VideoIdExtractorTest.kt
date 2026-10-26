package com.sniffsub.sniff_sub_player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoIdExtractorTest {
    @Test
    fun extracts_search_identifier_with_optional_separator() {
        assertEquals("ABC-123", extractVideoId(listOf("https://example.com/watch/abc-123")))
        assertEquals("HEYZO-4567", extractVideoId(listOf("title heyzo_4567 sample")))
        assertEquals("FC-29876", extractVideoId(listOf("fc298765")))
    }

    @Test
    fun falls_back_to_the_video_path_pattern() {
        assertEquals(
            "1PONDO-alpha",
            extractVideoId(listOf("https://example.com/1pondo-alpha/stream.m3u8")),
        )
    }

    @Test
    fun scans_values_and_patterns_in_priority_order() {
        assertEquals(
            "ABC-123",
            extractVideoId(
                listOf(
                    "https://example.com/watch/video",
                    "https://example.com/abc-123/also/xyz-999",
                ),
            ),
        )
    }

    @Test
    fun returns_empty_when_no_identifier_matches() {
        assertEquals("", extractVideoId(listOf("", "https://example.com/watch/video")))
    }
}
