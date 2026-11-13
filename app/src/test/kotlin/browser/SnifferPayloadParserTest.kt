package com.sniffsub.sniff_sub_player.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnifferPayloadParserTest {
    @Test
    fun bridge_rejects_missing_malformed_or_unsupported_media_urls() {
        assertNull(parseSnifferPayload("{}"))
        assertNull(parseSnifferPayload("not-json"))
        assertNull(parseSnifferPayload("""{"url":"javascript:alert(1)","source":"a"}"""))
        assertNull(parseSnifferPayload("""{"url":"https:///missing-host.mp4"}"""))
    }

    @Test
    fun bridge_accepts_structured_https_payload_and_trims_fields() {
        assertEquals(
            SnifferPayload(
                url = "https://cdn.test/video.mp4",
                source = "fetch",
                pageUrl = "https://page.test/watch",
                userAgent = "Test UA",
            ),
            parseSnifferPayload(
                """
                {
                  "url": "  https://cdn.test/video.mp4  ",
                  "source": " fetch ",
                  "pageURL": " https://page.test/watch ",
                  "userAgent": " Test UA "
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun bridge_accepts_blob_urls_and_safe_defaults() {
        assertEquals(
            SnifferPayload(
                url = "blob:https://page.test/id",
                source = "unknown",
                pageUrl = "",
                userAgent = "",
            ),
            parseSnifferPayload("""{"url":"blob:https://page.test/id"}"""),
        )
    }

    @Test
    fun untrusted_referer_and_user_agent_metadata_is_sanitized() {
        assertEquals(
            SnifferPayload(
                url = "https://cdn.test/video.mp4",
                source = "xhr",
                pageUrl = "",
                userAgent = "",
            ),
            parseSnifferPayload(
                """
                {
                  "url": "https://cdn.test/video.mp4",
                  "source": "xhr",
                  "pageUrl": "javascript:alert(1)",
                  "userAgent": "unsafe\nheader"
                }
                """.trimIndent(),
            ),
        )
    }
}
