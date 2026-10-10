package com.sniffsub.sniff_sub_player.player

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.media3.common.PlaybackParameters

class PlayerControlsTest {
    @Test
    fun swiping_uses_relative_time_and_clamps_at_both_ends() {
        val film = HorizontalSeekSession(30_000L, 7_200_000L)
        assertEquals(90_000L, film.dragBy(500f, 1_000f))
        assertEquals(0L, film.dragBy(-1_500f, 1_000f))
        val shortVideo = HorizontalSeekSession(20_000L, 60_000L)
        assertEquals(50_000L, shortVideo.dragBy(500f, 1_000f))
        assertEquals(60_000L, shortVideo.dragBy(500f, 1_000f))
        assertEquals(60_000L, shortVideo.dragBy(50f, 0f))
    }

    @Test
    fun repeated_hold_start_and_stop_preserve_original_playback_parameters() {
        var parameters = PlaybackParameters(1.5f, 0.8f)
        val speed = TemporaryPlaybackSpeed({ parameters }, { parameters = it })
        repeat(3) {
            speed.start()
            speed.start()
            assertEquals(PlaybackParameters(2f, 0.8f), parameters)
            speed.stop()
            speed.stop()
            assertEquals(PlaybackParameters(1.5f, 0.8f), parameters)
        }
    }

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
