package com.sniffsub.sniff_sub_player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerControlsTest {
    @Test
    fun time_format_uses_minutes_until_one_hour() {
        assertEquals("00:00", formatPlayerTime(0L))
        assertEquals("01:05", formatPlayerTime(65_900L))
        assertEquals("59:59", formatPlayerTime(3_599_999L))
    }

    @Test
    fun time_format_includes_hours_for_long_media() {
        assertEquals("1:00:00", formatPlayerTime(3_600_000L))
        assertEquals("2:03:04", formatPlayerTime(7_384_000L))
    }

    @Test
    fun seek_position_is_clamped_to_media_bounds() {
        assertEquals(0L, clampSeekPosition(-10_000L, 60_000L))
        assertEquals(25_000L, clampSeekPosition(25_000L, 60_000L))
        assertEquals(60_000L, clampSeekPosition(70_000L, 60_000L))
    }
}
