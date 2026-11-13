package com.sniffsub.sniff_sub_player.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateAccumulatorTest {
    @Test
    fun newest_unique_candidate_is_first_and_uses_page_as_referer() {
        val store = CandidateAccumulator(clock = { 123L })
        assertTrue(store.add(" https://cdn.test/a.m3u8 ", "fetch", "https://page.test", "UA"))
        assertFalse(store.add("https://cdn.test/a.m3u8", "xhr", "https://page.test", "UA"))
        assertEquals("https://page.test", store.snapshot().single().referer)
        assertEquals(123L, store.snapshot().single().detectedAtEpochMs)
    }

    @Test
    fun blank_and_non_media_urls_are_rejected() {
        val store = CandidateAccumulator()
        assertFalse(store.add("  ", "fetch", "https://page.test", "UA"))
        assertFalse(store.add("https://cdn.test/t.tsx", "fetch", "https://page.test", "UA"))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun omitted_page_url_uses_current_page() {
        val store = CandidateAccumulator(clock = { 123L })
        store.currentPageUrl = "https://current.test"
        assertTrue(store.add("https://cdn.test/a.mp4", "xhr", "", "UA"))
        assertEquals("https://current.test", store.snapshot().single().pageUrl)
        assertEquals("https://current.test", store.snapshot().single().referer)
    }

    @Test
    fun snapshot_exposes_at_most_thirty_newest_candidates() {
        val store = CandidateAccumulator(clock = { 123L })
        repeat(31) { index ->
            assertTrue(store.add("https://cdn.test/$index.mp4", "fetch", "https://page.test", "UA"))
        }
        assertEquals(30, store.snapshot().size)
        assertEquals("https://cdn.test/30.mp4", store.snapshot().first().url)
        assertEquals("https://cdn.test/1.mp4", store.snapshot().last().url)
    }
}
