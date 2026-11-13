package com.sniffsub.sniff_sub_player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkReducerTest {
    @Test
    fun toggling_a_new_bookmark_puts_it_first_then_removes_it() {
        val existing = Bookmark(
            id = "existing",
            title = "Existing",
            url = "https://existing.test",
            createdAtEpochMs = 1L,
        )

        val added = reduceBookmarkToggle(
            bookmarks = listOf(existing),
            url = "https://a.test",
            title = "A",
            now = 7L,
        )

        assertEquals("https://a.test", added.first().url)
        assertEquals(existing, added.last())
        assertTrue(reduceBookmarkToggle(added, "https://a.test", "A", now = 8L).none {
            it.url == "https://a.test"
        })
    }

    @Test
    fun adding_a_bookmark_trims_its_url_and_uses_it_for_a_blank_title() {
        val added = reduceBookmarkToggle(
            bookmarks = emptyList(),
            url = "  https://a.test/video  ",
            title = "  ",
            now = 7L,
        )

        assertEquals("https://a.test/video", added.single().url)
        assertEquals("https://a.test/video", added.single().title)
        assertEquals(7L, added.single().createdAtEpochMs)
    }

    @Test
    fun blank_urls_are_ignored() {
        val existing = listOf(
            Bookmark(
                id = "existing",
                title = "Existing",
                url = "https://existing.test",
                createdAtEpochMs = 1L,
            ),
        )

        assertEquals(existing, reduceBookmarkToggle(existing, "  ", "Ignored", now = 7L))
    }
}
