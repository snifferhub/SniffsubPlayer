package com.sniffsub.sniff_sub_player.browser

import android.content.Context
import android.app.Activity
import android.view.ViewGroup
import android.view.View
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserGlassCaptureTest {
    private class SampleView(context: Context, private val captureCostMs: Long = 0) : View(context) {
        var color = Color.BLUE
        var draws = 0
        val drawTimes = mutableListOf<Long>()

        override fun onDraw(canvas: Canvas) {
            draws++
            drawTimes += SystemClock.uptimeMillis()
            canvas.drawColor(color)
            if (captureCostMs > 0) SystemClock.sleep(captureCostMs)
        }
    }

    private fun sampleView(captureCostMs: Long = 0): SampleView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val view = SampleView(activity, captureCostMs)
        activity.setContentView(view, ViewGroup.LayoutParams(1080, 1800))
        shadowOf(Looper.getMainLooper()).idle()
        view.layout(0, 0, 1080, 1800)
        return view
    }

    private fun startCapture(view: View): BrowserGlassCapture = BrowserGlassCapture {
        throw AssertionError("Capture failed", it)
    }.also {
        it.attach(view)
        it.setEnabled(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun dispatchScroll(view: View) {
        ReflectionHelpers.callInstanceMethod<Unit>(view.viewTreeObserver, "dispatchOnScrollChanged")
    }

    @Test
    fun scrolling_refreshes_the_glass_within_two_frames_instead_of_waiting_200ms() {
        val view = sampleView()
        val capture = startCapture(view)
        val first = requireNotNull(capture.frame)
        view.color = Color.RED

        repeat(10) { dispatchScroll(view) }
        assertSame("Sampling must wait for a display frame", first, capture.frame)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

        assertEquals(Color.RED, requireNotNull(capture.frame).bitmap.getPixel(0, 0))
        assertEquals("Publishing must not mutate the previous Compose frame", Color.BLUE, first.bitmap.getPixel(0, 0))
        capture.detach()
    }

    @Test
    fun sustained_scroll_refreshes_smoothly_without_sampling_more_than_30fps() {
        val view = sampleView()
        val capture = startCapture(view)
        var previous = capture.frame
        var updates = 0
        val loopStarted = SystemClock.uptimeMillis()
        repeat(60) { tick ->
            view.color = Color.rgb(tick, 0, 255)
            repeat(5) { view.viewTreeObserver.dispatchOnDraw() }
            dispatchScroll(view)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            if (capture.frame !== previous) {
                updates++
                previous = capture.frame
            }
        }
        // Native graphics and frame callbacks can advance Robolectric's clock inside idleFor.
        val elapsed = SystemClock.uptimeMillis() - loopStarted
        val updatesPerSecond = updates * 1000.0 / elapsed
        assertTrue("Expected fluid updates, got $updatesPerSecond fps", updatesPerSecond >= 20)
        assertTrue("Sampling must respect its frame budget: $updatesPerSecond fps", updatesPerSecond <= 32)
        capture.detach()
    }

    @Test
    fun static_pages_do_not_poll_and_identical_snapshots_do_not_publish_a_new_frame() {
        val view = sampleView()
        val capture = startCapture(view)
        val first = requireNotNull(capture.frame)
        val initialDraws = view.draws
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(initialDraws, view.draws)

        view.viewTreeObserver.dispatchOnDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertSame(first, capture.frame)
        val afterDraw = view.draws
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(afterDraw, view.draws)
        capture.detach()
        assertTrue("Published frames must remain valid after detach", !first.bitmap.isRecycled)
    }

    @Test
    fun expensive_snapshots_back_off_to_leave_time_for_webpage_rendering() {
        val view = sampleView(captureCostMs = 40)
        val capture = startCapture(view)
        view.color = Color.RED
        dispatchScroll(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals(Color.RED, requireNotNull(capture.frame).bitmap.getPixel(0, 0))

        val redCapturedAt = view.drawTimes.last()
        view.color = Color.GREEN
        dispatchScroll(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(Color.GREEN, requireNotNull(capture.frame).bitmap.getPixel(0, 0))
        assertTrue("40ms snapshots must leave at least 80ms for other UI work", view.drawTimes.last() - redCapturedAt >= 120)
        capture.detach()
    }

    @Test
    fun disabling_or_detaching_cancels_pending_animation_frame_captures() {
        val view = sampleView()
        val capture = startCapture(view)
        val initialDraws = view.draws
        dispatchScroll(view)
        capture.setEnabled(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(initialDraws, view.draws)
        assertNull(capture.frame)

        capture.setEnabled(true)
        capture.detach()
        dispatchScroll(view)
        view.viewTreeObserver.dispatchOnDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(initialDraws, view.draws)
        assertNull(capture.frame)
    }

    @Test
    fun captures_only_a_bounded_bottom_strip_and_stops_when_hidden_or_released() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val webView = object : View(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(1080, 1800)
            }
            override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.BLUE) }
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        activity.setContentView(webView, ViewGroup.LayoutParams(1080, 1800))
        shadowOf(Looper.getMainLooper()).idle()
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, 1080, 1800)
        val capture = BrowserGlassCapture { throw AssertionError("Capture failed", it) }
        capture.attach(webView)
        capture.setEnabled(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertNotNull("attached=${webView.isAttachedToWindow} visibility=${webView.visibility} size=${webView.width}x${webView.height}", capture.frame)
        val frame = requireNotNull(capture.frame)
        assertTrue(frame.bitmap.width <= 512)
        assertTrue(frame.size.height < 1800)
        assertEquals(1800, frame.origin.y + frame.size.height)
        assertEquals(Color.BLUE, frame.bitmap.getPixel(0, 0))
        capture.setEnabled(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(capture.frame)
        capture.setEnabled(true)
        capture.detach(webView)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(capture.frame)
    }
}
