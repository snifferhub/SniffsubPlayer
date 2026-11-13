package com.sniffsub.sniff_sub_player.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import kotlin.math.roundToInt

/** Brightness is scoped to this window; volume follows the device's media stream. */
internal class PlayerLevelControls(context: Context, private val window: Window?) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val originalBrightness = window?.attributes?.screenBrightness
    private val systemBrightness = Settings.System.getInt(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS,
        128,
    ) / 255f

    var brightness: Float
        get() = window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: systemBrightness
        set(value) {
            window?.let {
                val attributes = it.attributes
                attributes.screenBrightness = value.coerceIn(0.02f, 1f)
                it.attributes = attributes
            }
        }

    var volume: Float
        get() {
            val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val range = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) - minimum
            return if (range <= 0) 0f else {
                (audio.getStreamVolume(AudioManager.STREAM_MUSIC) - minimum).toFloat() / range
            }
        }
        set(value) {
            if (audio.isVolumeFixed) return
            val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val range = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) - minimum
            val target = minimum + (value.coerceIn(0f, 1f) * range).roundToInt()
            // Only write on a new system step; don't show the platform volume popup.
            if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
        }

    fun restoreBrightness() {
        val original = originalBrightness ?: return
        window?.let {
            val attributes = it.attributes
            attributes.screenBrightness = original
            it.attributes = attributes
        }
    }
}
