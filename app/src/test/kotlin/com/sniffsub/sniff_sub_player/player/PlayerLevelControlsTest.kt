package com.sniffsub.sniff_sub_player.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerLevelControlsTest {
    @Test
    fun brightness_uses_the_system_default_and_restores_the_original_window_override() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 153)
        val controls = PlayerLevelControls(activity, activity.window)
        assertEquals(0.6f, controls.brightness, 0.001f)

        controls.brightness = 0f
        assertEquals(0.02f, activity.window.attributes.screenBrightness, 0.001f)
        assertEquals(153, Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        controls.restoreBrightness()
        assertEquals(-1f, activity.window.attributes.screenBrightness, 0.001f)

        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.4f }
        val overridden = PlayerLevelControls(activity, activity.window)
        overridden.brightness = 0.8f
        overridden.restoreBrightness()
        assertEquals(0.4f, activity.window.attributes.screenBrightness, 0.001f)
    }

    @Test
    fun a_full_height_swipe_changes_media_volume_by_only_a_quarter_of_the_range() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, minimum, 0)
        val controls = PlayerLevelControls(activity, activity.window)
        val session = VerticalLevelSession(controls.volume, 0.25f)
        controls.volume = session.dragBy(-400f, 400f)
        assertEquals(0.25f, controls.volume, 1f / (maximum - minimum))
        controls.volume = session.dragBy(400f, 400f)
        assertEquals(minimum, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
}
