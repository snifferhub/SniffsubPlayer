package com.sniffsub.sniff_sub_player.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.VideoSize
import kotlin.math.roundToInt

internal class PlayerPictureInPicture(private val activity: Activity) {
    val isSupported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    var isShowing by mutableStateOf(activity.isInPictureInPictureMode)

    private var video: Video? = null

    fun updateVideo(bounds: Rect?, size: VideoSize, autoEnter: Boolean) {
        if (!isSupported) return
        val ratio = size.width.toDouble() * size.pixelWidthHeightRatio / size.height
        val updatedVideo = if (bounds != null && !bounds.isEmpty && ratio.isFinite() && ratio > 0) {
            val pipRatio = Rational((ratio.coerceIn(1.0 / 2.39, 2.39) * 2_390).roundToInt(), 2_390)
            // Match the video inside the letterboxed player for the system transition.
            val insetX = ((bounds.width() - bounds.height() * ratio) / 2).coerceAtLeast(0.0).toInt()
            val insetY = ((bounds.height() - bounds.width() / ratio) / 2).coerceAtLeast(0.0).toInt()
            val source = Rect(bounds).apply { inset(insetX, insetY) }
            Video(pipRatio, source, autoEnter)
        } else {
            null
        }
        if (updatedVideo != video) {
            video = updatedVideo
            activity.setPictureInPictureParams(buildParams(updatedVideo))
        }
    }

    fun enter(): Boolean {
        val currentVideo = video ?: return false
        if (activity.isInPictureInPictureMode) return true
        return activity.enterPictureInPictureMode(buildParams(currentVideo))
    }

    fun enterOnUserLeave() {
        if (video?.autoEnter == true) enter()
    }

    private fun buildParams(video: Video?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (video != null) {
            builder.setAspectRatio(video.aspectRatio)
            builder.setSourceRectHint(video.sourceBounds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(video?.autoEnter == true)
        }
        return builder.build()
    }

    private data class Video(
        val aspectRatio: Rational,
        val sourceBounds: Rect,
        val autoEnter: Boolean,
    )
}
