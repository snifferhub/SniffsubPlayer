package com.sniffsub.sniff_sub_player.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private val enabled = mutableStateOf(true)
    private var taps = 0
    private val doubleTaps = mutableListOf<Boolean>()
    private var seeking = false
    private var seekStarts = 0
    private val finishes = mutableListOf<Boolean>()
    private var session: HorizontalSeekSession? = null
    private var position = 60_000L
    private var preview = position
    private var parameters = PlaybackParameters(1.25f, 0.9f)
    private val speed = TemporaryPlaybackSpeed({ parameters }, { parameters = it })
    private var holds = 0
    private var buttonClicks = 0
    private val verticalSides = mutableListOf<Boolean>()
    private var verticalFinishes = 0
    private var level = 0.5f
    private var levelSession: VerticalLevelSession? = null

    private fun showSurface(withButton: Boolean = false) {
        compose.setContent {
            Box(Modifier.size(400.dp, 300.dp)) {
                PlayerGestureSurface(
                    enabled = enabled.value,
                    onTap = { taps++ },
                    onDoubleTap = { doubleTaps += it },
                    onSeekStart = {
                        seekStarts++
                        seeking = true
                        session = HorizontalSeekSession(position, 180_000L)
                        true
                    },
                    onSeekDelta = { delta, width -> preview = session!!.dragBy(delta, width) },
                    onSeekFinish = {
                        finishes += it
                        if (it) position = preview else preview = position
                        seeking = false
                    },
                    onHoldStart = { holds++; speed.start() },
                    onHoldEnd = { speed.stop() },
                    onVerticalStart = {
                        verticalSides += it
                        levelSession = VerticalLevelSession(level, if (it) 0.25f else 0.75f)
                    },
                    onVerticalDelta = { delta, height -> level = levelSession!!.dragBy(delta, height) },
                    onVerticalEnd = { verticalFinishes++ },
                    modifier = Modifier.fillMaxSize().testTag("gestures"),
                )
                if (withButton) Button(
                    onClick = { buttonClicks++ },
                    modifier = Modifier.align(Alignment.Center).testTag("button"),
                ) { Text("播放") }
            }
        }
    }

    @Test
    fun horizontal_swipe_seeks_without_triggering_tap_or_long_press() {
        showSurface()
        compose.onNodeWithTag("gestures").performTouchInput {
            swipe(Offset(width * 0.25f, height / 2f), Offset(width * 0.75f, height / 2f), 250)
        }
        compose.runOnIdle {
            assertTrue(position > 100_000L)
            assertEquals(listOf(true), finishes)
            assertFalse(seeking)
            assertEquals(0, taps)
            assertEquals(0, holds)
        }
    }

    @Test
    fun dragging_previews_until_release_and_cancellation_does_not_seek() {
        showSurface()
        compose.onNodeWithTag("gestures").performTouchInput {
            down(Offset(width * 0.25f, height / 2f))
            moveTo(Offset(width * 0.75f, height / 2f), delayMillis = 100)
        }
        compose.runOnIdle {
            assertTrue(seeking)
            assertTrue(preview > position)
            assertEquals(60_000L, position)
            enabled.value = false
        }
        compose.runOnIdle {
            assertEquals(listOf(false), finishes)
            assertEquals(60_000L, position)
            assertEquals(position, preview)
        }
        compose.onNodeWithTag("gestures").performTouchInput { up() }
    }

    @Test
    fun single_and_double_taps_keep_their_existing_actions() {
        showSurface()
        compose.onNodeWithTag("gestures").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("gestures").performTouchInput {
            doubleClick(Offset(width * 0.25f, height / 2f))
        }
        compose.onNodeWithTag("gestures").performTouchInput {
            advanceEventTime(400)
            doubleClick(Offset(width * 0.75f, height / 2f))
        }
        compose.runOnIdle {
            assertEquals(1, taps)
            assertEquals(listOf(false, true), doubleTaps)
            assertEquals(0, seekStarts)
            assertEquals(0, holds)
        }
    }

    @Test
    fun long_press_restores_original_speed_and_pitch_when_released() {
        showSurface()
        compose.onNodeWithTag("gestures").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, holds)
            assertEquals(PlaybackParameters(1.25f, 0.9f), parameters)
            assertEquals(0, taps)
            assertEquals(0, seekStarts)
        }
    }

    @Test
    fun interrupted_long_press_restores_speed() {
        showSurface()
        compose.onNodeWithTag("gestures").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertEquals(2f, parameters.speed)
            assertEquals(0.9f, parameters.pitch)
            enabled.value = false
        }
        compose.runOnIdle { assertEquals(PlaybackParameters(1.25f, 0.9f), parameters) }
        compose.onNodeWithTag("gestures").performTouchInput { up() }
    }

    @Test
    fun controls_above_video_handle_their_own_touches() {
        showSurface(withButton = true)
        compose.onNodeWithTag("button").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, buttonClicks)
            assertEquals(0, taps)
            assertEquals(0, holds)
            assertEquals(0, seekStarts)
        }
    }

    @Test
    fun vertical_swipes_adjust_the_initial_half_without_seeking_or_tapping() {
        showSurface()
        compose.onNodeWithTag("gestures").performTouchInput {
            swipe(Offset(width * 0.25f, height * 0.8f), Offset(width * 0.25f, height * 0.2f), 250)
            advanceEventTime(400)
            swipe(Offset(width * 0.75f, height * 0.2f), Offset(width * 0.75f, height * 0.8f), 250)
        }
        compose.runOnIdle {
            assertEquals(listOf(false, true), verticalSides)
            assertEquals(2, verticalFinishes)
            assertTrue(level > 0.5f)
            assertEquals(0, seekStarts)
            assertEquals(0, holds)
            assertEquals(0, taps)
            assertTrue(doubleTaps.isEmpty())
        }
    }

    @Test
    fun a_tap_after_scrubbing_is_not_a_double_tap_seek() {
        showSurface()
        compose.onNodeWithTag("gestures").performTouchInput {
            swipe(Offset(width * 0.25f, height / 2f), Offset(width * 0.75f, height / 2f), 150)
            advanceEventTime(60)
            click(Offset(width * 0.75f, height / 2f))
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals(listOf(true), finishes)
            assertEquals(1, taps)
            assertTrue(doubleTaps.isEmpty())
        }
    }

    @Test
    fun a_drag_as_the_second_touch_cancels_the_pending_double_tap() {
        showSurface()
        compose.onNodeWithTag("gestures").performTouchInput {
            click(Offset(width * 0.25f, height / 2f))
            advanceEventTime(60)
            swipe(Offset(width * 0.25f, height / 2f), Offset(width * 0.75f, height / 2f), 150)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals(listOf(true), finishes)
            assertEquals(0, taps)
            assertEquals(0, holds)
            assertTrue(doubleTaps.isEmpty())
        }
    }

    @Test
    fun vertical_direction_and_side_stay_locked_when_the_finger_moves_diagonally() {
        showSurface()
        compose.onNodeWithTag("gestures").performTouchInput {
            down(Offset(width * 0.45f, height * 0.8f))
            moveTo(Offset(width * 0.45f, height * 0.6f), delayMillis = 50)
            moveTo(Offset(width * 0.75f, height * 0.3f), delayMillis = 50)
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(false), verticalSides)
            assertEquals(1, verticalFinishes)
            assertEquals(0, seekStarts)
            assertEquals(0, taps)
        }
    }

    @Test
    fun interruption_finishes_the_vertical_adjustment_once() {
        showSurface()
        compose.onNodeWithTag("gestures").performTouchInput {
            down(Offset(width * 0.75f, height * 0.8f))
            moveTo(Offset(width * 0.75f, height * 0.3f), delayMillis = 100)
        }
        compose.runOnIdle { enabled.value = false }
        compose.runOnIdle {
            assertEquals(1, verticalFinishes)
            assertTrue(doubleTaps.isEmpty())
        }
        compose.onNodeWithTag("gestures").performTouchInput { up() }
    }
}
