package com.sniffsub.sniff_sub_player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleScreensContractTest {
    @Test
    fun document_picker_uses_only_the_supported_subtitle_mime_types() {
        assertEquals(
            listOf("application/x-subrip", "text/vtt", "text/plain"),
            SUBTITLE_DOCUMENT_MIME_TYPES.toList(),
        )
    }

    @Test
    fun subtitlecat_query_is_encoded_as_a_single_parameter() {
        assertEquals(
            "https://subtitlecat.com/index.php?search=AB%20C%2F%E4%B8%AD%E6%96%87%3F",
            buildSubtitlecatUrl("AB C/中文?"),
        )
    }

    @Test
    fun imported_bytes_use_utf8_replacement_for_malformed_input() {
        assertEquals(
            "中�(",
            decodeSubtitleBytes(
                byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte(), 0xC3.toByte(), 0x28),
            ),
        )
    }

    @Test
    fun embedded_browser_allows_https_and_rejects_insecure_or_local_navigation() {
        assertTrue(isSafeSubtitlecatNavigation("https://subtitlecat.com/index.php?search=A"))
        assertTrue(isSafeSubtitlecatNavigation("https://cdn.subtitlecat.com/file.srt"))
        assertFalse(isSafeSubtitlecatNavigation("https://example.com/file.srt"))
        assertFalse(isSafeSubtitlecatNavigation("https://subtitlecat.com@evil.example/file.srt"))
        assertFalse(isSafeSubtitlecatNavigation("http://subtitlecat.com/"))
        assertFalse(isSafeSubtitlecatNavigation("file:///sdcard/private.txt"))
        assertFalse(isSafeSubtitlecatNavigation("javascript:alert(1)"))
        assertFalse(isSafeSubtitlecatNavigation("not a url"))
    }
}
