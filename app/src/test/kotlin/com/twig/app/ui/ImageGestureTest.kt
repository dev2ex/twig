package com.twig.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class ImageGestureTest {
    private lateinit var image: ZoomableImageView
    private var downTime = 0L
    private var menus = 0
    private var pages = 0

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.setTheme(R.style.Theme_Twig)
        image = ZoomableImageView(app)
        image.layout(0, 0, 400, 400)
        image.setImage(Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888))
        image.onLongPress = { menus++ }
        image.onPage = { pages++ }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun hold() = idle((ViewConfiguration.getLongPressTimeout() + ViewConfiguration.getTapTimeout() + 100).toLong())
    private fun touch(action: Int, vararg points: Pair<Float, Float>) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
            x = points[index].first; y = points[index].second; pressure = 1f; size = 1f
        } }
        val event = MotionEvent.obtain(downTime, now, action, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(image.onTouchEvent(event)) } finally { event.recycle() }
    }
    private fun secondTapDown() {
        touch(MotionEvent.ACTION_DOWN, 200f to 200f)
        idle(30)
        touch(MotionEvent.ACTION_UP, 200f to 200f)
        idle(70)
        touch(MotionEvent.ACTION_DOWN, 200f to 200f)
    }
    private fun matrix() = FloatArray(9).also { image.imageMatrix.getValues(it) }

    @Test fun `ordinary long press still opens the menu`() {
        touch(MotionEvent.ACTION_DOWN, 200f to 200f)
        hold()
        assertEquals(1, menus)
        touch(MotionEvent.ACTION_UP, 200f to 200f)
    }

    @Test fun `second tap held before dragging does not open menu and next ordinary hold works`() {
        secondTapDown()
        hold()
        assertEquals(0, menus)
        touch(MotionEvent.ACTION_UP, 200f to 200f)
        idle(ViewConfiguration.getDoubleTapTimeout().toLong() + 100)
        touch(MotionEvent.ACTION_DOWN, 200f to 200f)
        hold()
        assertEquals(1, menus)
        touch(MotionEvent.ACTION_UP, 200f to 200f)
    }

    @Test fun `double tap still changes mode and single finger drag scales without a menu`() {
        image.setImage(Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888))
        val fit = matrix()[Matrix.MSCALE_X]
        secondTapDown()
        val doubleTap = matrix()[Matrix.MSCALE_X]
        assertTrue(doubleTap > fit)
        // Start the quick-scale drag, then pause long enough to expose the pending menu callback.
        idle(20)
        touch(MotionEvent.ACTION_MOVE, 200f to 250f)
        idle(20)
        touch(MotionEvent.ACTION_MOVE, 200f to 300f)
        hold()
        assertEquals(0, menus)
        assertNotEquals(doubleTap, matrix()[Matrix.MSCALE_X])
        touch(MotionEvent.ACTION_UP, 200f to 300f)
        assertEquals(0, pages)
    }

    @Test fun `pinch applies scale around its focus without an extra ordinary pan`() {
        image.applyViewport(floatArrayOf(.2f, .2f, .5f))
        touch(MotionEvent.ACTION_DOWN, 100f to 200f)
        idle(20)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            100f to 200f, 300f to 200f)
        idle(20)
        touch(MotionEvent.ACTION_MOVE, 80f to 200f, 360f to 200f)
        val before = matrix()
        idle(20)
        touch(MotionEvent.ACTION_MOVE, 60f to 200f, 420f to 200f)
        val after = matrix()
        val ratio = after[Matrix.MSCALE_X] / before[Matrix.MSCALE_X]
        assertTrue(ratio > 1f)
        assertEquals(240f - (240f - before[Matrix.MTRANS_X]) * ratio, after[Matrix.MTRANS_X], .01f)
        assertEquals(200f - (200f - before[Matrix.MTRANS_Y]) * ratio, after[Matrix.MTRANS_Y], .01f)
        hold()
        assertEquals(0, menus)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            60f to 200f, 420f to 200f)
        idle(20)
        touch(MotionEvent.ACTION_UP, 60f to 200f)
        assertEquals(0, pages)
    }

    @Test fun `ordinary drag still pans and a fresh swipe can turn pages`() {
        image.applyViewport(floatArrayOf(.2f, .2f, .5f))
        val before = matrix()[Matrix.MTRANS_X]
        touch(MotionEvent.ACTION_DOWN, 240f to 200f)
        idle(30)
        touch(MotionEvent.ACTION_MOVE, 160f to 200f)
        assertTrue(matrix()[Matrix.MTRANS_X] < before)
        touch(MotionEvent.ACTION_UP, 160f to 200f)
        idle(500)
        image.setImage(Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888))
        touch(MotionEvent.ACTION_DOWN, 300f to 200f)
        idle(30)
        touch(MotionEvent.ACTION_MOVE, 200f to 200f)
        idle(30)
        touch(MotionEvent.ACTION_UP, 100f to 200f)
        assertEquals(1, pages)
        assertEquals(0, menus)
    }
}
