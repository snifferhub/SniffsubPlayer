package com.sniffsub.sniff_sub_player.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressNormalizerTest {
    @Test
    fun fully_qualified_url_is_preserved() {
        assertEquals("https://example.com/path?q=1", normalizeAddress("https://example.com/path?q=1"))
    }

    @Test
    fun domain_like_input_gets_https_scheme() {
        assertEquals("https://example.com", normalizeAddress("example.com"))
    }

    @Test
    fun words_become_encoded_google_search() {
        assertEquals(
            "https://www.google.com/search?q=%E6%B5%8B%E8%AF%95+%E8%A7%86%E9%A2%91",
            normalizeAddress("测试 视频"),
        )
    }

    @Test
    fun blank_input_is_rejected() {
        assertNull(normalizeAddress("  \t"))
    }
}
