package com.sniffsub.sniff_sub_player.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.os.Trace
import android.view.View
import android.view.ViewTreeObserver
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

internal data class BrowserGlassFrame(
    val bitmap: Bitmap,
    val image: ImageBitmap = bitmap.asImageBitmap(),
    val origin: IntOffset,
    val size: IntSize,
)

/** Only samples the small visible strip behind the dock, never the Compose controls. */
internal class BrowserGlassCapture(
    private val onCaptureFailure: (Throwable) -> Unit = { Log.w("BrowserGlassCapture", "Snapshot failed", it) },
) {
    var frame by mutableStateOf<BrowserGlassFrame?>(null)
        private set
    var isDark by mutableStateOf(false)
        private set
    private var view: View? = null
    private var enabled = false
    private var origin = Offset.Zero
    private var scheduled = false
    private var nextCaptureAt = 0L
    private var scratchBitmap: Bitmap? = null
    private val onDraw = ViewTreeObserver.OnDrawListener { schedule() }
    private val onScroll = ViewTreeObserver.OnScrollChangedListener { schedule() }
    private val capture = object : Runnable {
        override fun run() {
            val target = view
            if (!enabled || target == null || target.visibility != View.VISIBLE || target.width <= 0 || target.height <= 0) {
                scheduled = false
                return
            }
            val startedAt = SystemClock.uptimeMillis()
            if (startedAt < nextCaptureAt) {
                target.postOnAnimation(this)
                return
            }
            scheduled = false
            Trace.beginSection("BrowserGlassCapture")
            try {
                captureFrame(target)
            } finally {
                Trace.endSection()
                // Aim for 30 fps, but leave at least two thirds of UI-thread time for the page.
                val cost = SystemClock.uptimeMillis() - startedAt
                nextCaptureAt = startedAt + maxOf(32L, cost * 3)
            }
            // Only draw/scroll notifications request another sample; never poll a static page.
        }
    }

    private fun captureFrame(target: View) {
        val bandHeight = (128 * target.resources.displayMetrics.density).roundToInt().coerceAtMost(target.height)
        val top = target.height - bandHeight
        val scale = (512f / target.width).coerceAtMost(1f)
        val width = (target.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bandHeight * scale).roundToInt().coerceAtLeast(1)
        val bitmap = scratchBitmap?.takeIf { it.width == width && it.height == height } ?: run {
            scratchBitmap?.recycle()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        scratchBitmap = bitmap
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-target.scrollX.toFloat(), -(target.scrollY + top).toFloat())
        // Software snapshots exclude separate video surfaces; the page itself remains hardware rendered.
        runCatching { target.draw(canvas) }.onFailure {
            bitmap.recycle()
            scratchBitmap = null
            onCaptureFailure(it)
            return
        }
        val next = BrowserGlassFrame(
            bitmap = bitmap,
            origin = IntOffset(origin.x.roundToInt(), (origin.y + top).roundToInt()),
            size = IntSize(target.width, bandHeight),
        )
        val previous = frame
        if (previous?.origin != next.origin || previous.size != next.size || !previous.bitmap.sameAs(bitmap)) {
            var luminance = 0f
            for (row in 0..3) for (column in 0..7) {
                val pixel = bitmap.getPixel(
                    (bitmap.width - 1) * column / 7, (bitmap.height - 1) * row / 3,
                )
                luminance += (Color.red(pixel) * 0.2126f + Color.green(pixel) * 0.7152f + Color.blue(pixel) * 0.0722f) / 255
            }
            isDark = luminance / 32 < 0.45f
            // Do not recycle a published bitmap while Compose may still draw its previous frame.
            scratchBitmap = null
            frame = next
        }
    }

    fun attach(target: View) {
        if (view === target) return
        detach()
        view = target
        target.viewTreeObserver.addOnDrawListener(onDraw)
        target.viewTreeObserver.addOnScrollChangedListener(onScroll)
        schedule()
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) schedule() else stopCapture()
    }

    fun setOrigin(value: Offset) {
        if (origin == value) return
        origin = value
        schedule()
    }

    private fun schedule() {
        val target = view ?: return
        if (!enabled || scheduled) return
        scheduled = true
        target.postOnAnimation(capture)
    }

    private fun stopCapture() {
        view?.removeCallbacks(capture)
        scheduled = false
        nextCaptureAt = 0L
        scratchBitmap?.recycle()
        scratchBitmap = null
        frame = null
    }

    fun detach(target: View? = view) {
        if (target !== view) return
        view?.let {
            it.removeCallbacks(capture)
            if (it.viewTreeObserver.isAlive) {
                it.viewTreeObserver.removeOnDrawListener(onDraw)
                it.viewTreeObserver.removeOnScrollChangedListener(onScroll)
            }
        }
        stopCapture()
        view = null
    }
}
