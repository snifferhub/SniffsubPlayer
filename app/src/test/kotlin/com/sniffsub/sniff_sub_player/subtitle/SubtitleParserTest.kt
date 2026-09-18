package com.sniffsub.sniff_sub_player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleParserTest {
    @Test
    fun srt_normalizes_bom_crlf_commas_markup_and_multiline_text() {
        val cues = parseSubtitles(
            "\uFEFF1\r\n00:00:01,250 --> 00:00:03,500\r\n<b>First</b>\r\n<i>Second</i>\r\n",
        )

        assertEquals(
            listOf(SubtitleCue(startMs = 1_250, endMs = 3_500, text = "First\nSecond")),
            cues,
        )
    }

    @Test
    fun vtt_accepts_header_cue_identifier_settings_and_short_timestamps() {
        val cues = parseSubtitles(
            """
                WEBVTT - Example

                intro-cue
                01:02.125 --> 01:04.500 align:start position:10%
                Hello <c.green>world</c>
            """.trimIndent(),
        )

        assertEquals(
            listOf(SubtitleCue(startMs = 62_125, endMs = 64_500, text = "Hello world")),
            cues,
        )
    }

    @Test
    fun invalid_timestamps_empty_text_and_non_increasing_ranges_are_skipped() {
        val cues = parseSubtitles(
            """
                1
                nonsense --> 00:00:02.000
                Bad start

                2
                00:00:03.000 --> 00:00:03.000
                Equal

                3
                00:00:04.000 --> 00:00:02.000
                Reversed

                4
                00:00:05.000 --> 00:00:06.000
                <b></b>

                5
                00:00:07.000 --> 00:00:08.000
                Valid
            """.trimIndent(),
        )

        assertEquals(
            listOf(SubtitleCue(startMs = 7_000, endMs = 8_000, text = "Valid")),
            cues,
        )
    }

    @Test
    fun cues_are_sorted_and_positive_offset_is_applied() {
        val cues = parseSubtitles(
            """
                00:00:02.000 --> 00:00:03.000
                Later

                00:00:01.000 --> 00:00:01.500
                Earlier
            """.trimIndent(),
            offsetMs = 500,
        )

        assertEquals(
            listOf(
                SubtitleCue(startMs = 1_500, endMs = 2_000, text = "Earlier"),
                SubtitleCue(startMs = 2_500, endMs = 3_500, text = "Later"),
            ),
            cues,
        )
    }

    @Test
    fun negative_offset_clamps_at_zero_and_discards_collapsed_cues() {
        val cues = parseSubtitles(
            """
                00:00:00.250 --> 00:00:01.250
                Keep

                00:00:00.100 --> 00:00:00.400
                Drop
            """.trimIndent(),
            offsetMs = -500,
        )

        assertEquals(
            listOf(SubtitleCue(startMs = 0, endMs = 750, text = "Keep")),
            cues,
        )
    }

    @Test
    fun overlapping_cues_join_in_start_order() {
        val cues = parseSubtitles(
            "00:00:02.000 --> 00:00:04.000\nB\n\n00:00:01.000 --> 00:00:03.000\nA",
        )

        assertEquals("A\nB", subtitleAt(cues, 2_500))
    }

    @Test
    fun cue_lookup_is_end_exclusive() {
        val cues = listOf(
            SubtitleCue(startMs = 1_000, endMs = 2_000, text = "First"),
            SubtitleCue(startMs = 2_000, endMs = 3_000, text = "Second"),
        )

        assertEquals("", subtitleAt(cues, 999))
        assertEquals("First", subtitleAt(cues, 1_999))
        assertEquals("Second", subtitleAt(cues, 2_000))
        assertEquals("", subtitleAt(cues, 3_000))
    }
}
