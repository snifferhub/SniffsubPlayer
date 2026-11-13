package com.sniffsub.sniff_sub_player.browser

import com.sniffsub.sniff_sub_player.adblock.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class AdResourceClassificationTest {
    @Test fun browser_metadata_takes_precedence_over_extension() {
        assertEquals(ResourceType.DOCUMENT, classifyWebResource("https://site.test/page.js", true, emptyMap()))
        assertEquals(ResourceType.SCRIPT, classifyWebResource("https://site.test/get?id=1", false, mapOf("sec-fetch-dest" to "script")))
        assertEquals(ResourceType.XMLHTTPREQUEST, classifyWebResource("https://site.test/api", false, mapOf("X-Requested-With" to "XMLHttpRequest")))
    }
    @Test fun extension_and_accept_fallbacks_preserve_media_types() {
        assertEquals(ResourceType.MEDIA, classifyWebResource("https://site.test/movie.m3u8?token=1", false, emptyMap()))
        assertEquals(ResourceType.IMAGE, classifyWebResource("https://site.test/banner", false, mapOf("Accept" to "image/avif,image/webp")))
        assertEquals(ResourceType.OTHER, classifyWebResource("https://site.test/unknown", false, emptyMap()))
    }
}
