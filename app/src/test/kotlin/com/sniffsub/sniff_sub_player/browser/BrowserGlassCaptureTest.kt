package com.sniffsub.sniff_sub_player.browser

import android.content.Context
import android.app.Activity
import android.view.ViewGroup
import android.view.View
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserGlassCaptureTest {
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
