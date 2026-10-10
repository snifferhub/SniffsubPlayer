package com.sniffsub.sniff_sub_player.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.PlaybackParameters
import kotlin.math.min
import kotlin.math.roundToLong

/** A screen-width swipe spans at most two minutes, including for a long film. */
internal class HorizontalSeekSession(val startPositionMs: Long, val durationMs: Long) {
    private var fraction = 0.0
    var targetPositionMs: Long = startPositionMs.coerceIn(0L, durationMs)
        private set

    fun dragBy(deltaPx: Float, widthPx: Float): Long {
        if (widthPx > 0f) fraction += deltaPx / widthPx.toDouble()
        val offsetMs = (fraction * min(durationMs, 120_000L)).roundToLong()
        targetPositionMs = clampSeekPosition(startPositionMs + offsetMs, durationMs)
        return targetPositionMs
    }
}

/** Restores the full parameters, including pitch, on release or cancellation. */
internal class TemporaryPlaybackSpeed(
    private val readParameters: () -> PlaybackParameters,
    private val writeParameters: (PlaybackParameters) -> Unit,
) {
    private var original: PlaybackParameters? = null

    fun start() {
        if (original != null) return
        val parameters = readParameters()
        original = parameters
        writeParameters(parameters.withSpeed(2f))
    }

    fun stop() {
        val parameters = original ?: return
        original = null
        writeParameters(parameters)
    }
}

@Composable
internal fun PlayerGestureSurface(
    enabled: Boolean,
    onTap: () -> Unit,
    onDoubleTap: (Boolean) -> Unit,
    onSeekStart: () -> Boolean,
    onSeekDelta: (Float, Float) -> Unit,
    onSeekFinish: (Boolean) -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Playback state changes while fingers are down; keep callbacks current without restarting input.
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val seekStart by rememberUpdatedState(onSeekStart)
    val seekDelta by rememberUpdatedState(onSeekDelta)
    val seekFinish by rememberUpdatedState(onSeekFinish)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    Box(
        modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { doubleTap(it.x >= size.width / 2f) },
                    onLongPress = { holdStart() },
                    onPress = {
                        try {
                            tryAwaitRelease()
                        } finally {
                            holdEnd()
                        }
                    },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var seeking = false
                fun finish(commit: Boolean) {
                    if (seeking) {
                        seeking = false
                        seekFinish(commit)
                    }
                }
                try {
                    detectHorizontalDragGestures(
                        onDragStart = { seeking = seekStart() },
                        onHorizontalDrag = { _, delta ->
                            if (seeking) seekDelta(delta, size.width.toFloat())
                        },
                        onDragEnd = { finish(true) },
                        onDragCancel = { finish(false) },
                    )
                } finally {
                    finish(false)
                }
            },
    )
}
