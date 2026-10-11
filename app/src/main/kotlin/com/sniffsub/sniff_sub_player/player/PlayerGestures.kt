package com.sniffsub.sniff_sub_player.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.PlaybackParameters
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
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

/** Accumulate sub-step motion so integer system volume steps still feel gradual. */
internal class VerticalLevelSession(
    private val startLevel: Float,
    private val rangePerScreen: Float,
    private val minimum: Float = 0f,
) {
    private var fraction = 0f

    fun dragBy(deltaPx: Float, heightPx: Float): Float {
        if (heightPx > 0f) fraction -= deltaPx / heightPx
        return (startLevel + fraction * rangePerScreen).coerceIn(minimum, 1f)
    }
}

private data class CompletedTap(val time: Long, val position: Offset)
private enum class DragAxis { Horizontal, Vertical }

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
    onVerticalStart: (Boolean) -> Unit = {},
    onVerticalDelta: (Float, Float) -> Unit = { _, _ -> },
    onVerticalEnd: () -> Unit = {},
) {
    // Keep callbacks current without restarting input while fingers are down.
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val seekStart by rememberUpdatedState(onSeekStart)
    val seekDelta by rememberUpdatedState(onSeekDelta)
    val seekFinish by rememberUpdatedState(onSeekFinish)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val verticalStart by rememberUpdatedState(onVerticalStart)
    val verticalDelta by rememberUpdatedState(onVerticalDelta)
    val verticalEnd by rememberUpdatedState(onVerticalEnd)
    Box(
        modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            coroutineScope {
                var pendingTap: Job? = null
                var previousTap: CompletedTap? = null
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val firstTap = previousTap
                    val isSecondTap = firstTap != null &&
                        down.uptimeMillis - firstTap.time in
                            viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                        (down.position - firstTap.position).getDistance() <= viewConfiguration.touchSlop * 2
                    pendingTap?.cancel()
                    pendingTap = null
                    previousTap = null
                    if (firstTap != null && !isSecondTap) tap()

                    var axis: DragAxis? = null
                    var seeking = false
                    var adjustingLevel = false
                    var holding = false
                    var completed = false
                    var upTime = down.uptimeMillis
                    var upPosition = down.position
                    try {
                        while (true) {
                            val event = if (axis == null && !holding) {
                                val remaining = viewConfiguration.longPressTimeoutMillis -
                                    (upTime - down.uptimeMillis)
                                withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                holding = true
                                holdStart()
                                continue
                            }
                            // Additional fingers or consumption by another control cancel this gesture.
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (event.changes.any { it.id != down.id && it.pressed } || change.isConsumed) break
                            upTime = change.uptimeMillis
                            upPosition = change.position
                            if (!change.pressed) {
                                change.consume()
                                completed = true
                                break
                            }
                            val displacement = change.position - down.position
                            if (axis == null && !holding && displacement.getDistance() > viewConfiguration.touchSlop) {
                                // Lock the direction and initial screen half for the entire gesture.
                                axis = if (abs(displacement.x) >= abs(displacement.y)) {
                                    DragAxis.Horizontal
                                } else {
                                    DragAxis.Vertical
                                }
                                if (axis == DragAxis.Horizontal) {
                                    seeking = seekStart()
                                } else {
                                    adjustingLevel = true
                                    verticalStart(down.position.x >= size.width / 2f)
                                }
                            }
                            val delta = change.position - change.previousPosition
                            when (axis) {
                                DragAxis.Horizontal -> if (seeking) seekDelta(delta.x, size.width.toFloat())
                                DragAxis.Vertical -> verticalDelta(delta.y, size.height.toFloat())
                                null -> Unit
                            }
                            if (axis != null || holding) change.consume()
                        }
                    } finally {
                        if (seeking) seekFinish(completed)
                        if (adjustingLevel) verticalEnd()
                        if (holding) holdEnd()
                    }
                    if (completed && axis == null && !holding) {
                        if (isSecondTap) {
                            doubleTap(upPosition.x >= size.width / 2f)
                        } else {
                            previousTap = CompletedTap(upTime, upPosition)
                            pendingTap = launch {
                                delay(viewConfiguration.doubleTapTimeoutMillis)
                                previousTap = null
                                tap()
                            }
                        }
                    }
                }
            }
        },
    )
}
