package com.sniffsub.sniff_sub_player.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCandidateTest {
    @Test
    fun media_extensions_are_case_insensitive_and_allow_query_strings() {
        assertEquals(MediaKind.HLS, classifyMedia("https://cdn.test/VIDEO.M3U8?token=x"))
        assertEquals(MediaKind.MP4, classifyMedia("https://cdn.test/clip.MP4#fragment"))
        assertEquals(MediaKind.DASH, classifyMedia("https://cdn.test/manifest.mpd"))
        assertEquals(MediaKind.SEGMENT, classifyMedia("https://cdn.test/audio.aac&part=1"))
    }

    @Test
    fun blob_is_classified_before_extension_matching() {
        assertEquals(MediaKind.BLOB, classifyMedia("blob:https://cdn.test/clip.mp4"))
    }

    @Test
    fun media_extension_must_end_at_a_url_boundary() {
        assertEquals(MediaKind.OTHER, classifyMedia("https://cdn.test/t.tsx"))
        assertEquals(MediaKind.OTHER, classifyMedia("https://www.ts.example/path"))
    }
}
