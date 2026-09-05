package com.twig.app.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The behavioral contract for a directional key's "hold to repeat", see [HoldRepeat]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HoldRepeatTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun view(fires: MutableList<Unit>): View =
        View(ctx).apply {
            layout(0, 0, 100, 50)
            HoldRepeat.install(this, Handler(Looper.getMainLooper())) { fires += Unit }
        }

    private fun touch(v: View, action: Int, x: Float = 10f, y: Float = 10f) {
        val e = MotionEvent.obtain(0, 0, action, x, y, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    @Test
    fun `a tap sends exactly one key`() {
        val fires = mutableListOf<Unit>()
        val v = view(fires)
        touch(v, MotionEvent.ACTION_DOWN)
        touch(v, MotionEvent.ACTION_UP)
        looper.idleFor(java.time.Duration.ofSeconds(2))
        assertEquals(1, fires.size)
    }

    @Test
    fun `holding keeps sending until the finger comes up`() {
        val fires = mutableListOf<Unit>()
        val v = view(fires)
        touch(v, MotionEvent.ACTION_DOWN)
        assertEquals("the first one must not wait for the repeat delay", 1, fires.size)

        looper.idleFor(java.time.Duration.ofMillis(HoldRepeat.DELAY_MS - 50))
        assertEquals("nothing repeats before the hold delay", 1, fires.size)

        looper.idleFor(java.time.Duration.ofMillis(50 + HoldRepeat.INTERVAL_MS * 5))
        assertTrue("held: expected several repeats, got ${fires.size}", fires.size >= 5)

        val held = fires.size
        touch(v, MotionEvent.ACTION_UP)
        looper.idleFor(java.time.Duration.ofSeconds(2))
        assertEquals("releasing stops it", held, fires.size)
    }

    @Test
    fun `sliding off the key stops the repeat`() {
        val fires = mutableListOf<Unit>()
        val v = view(fires)
        touch(v, MotionEvent.ACTION_DOWN)
        looper.idleFor(java.time.Duration.ofMillis(HoldRepeat.DELAY_MS + HoldRepeat.INTERVAL_MS * 3))
        val moved = fires.size
        assertTrue(moved > 1)

        touch(v, MotionEvent.ACTION_MOVE, x = 500f) // slide off the button
        looper.idleFor(java.time.Duration.ofSeconds(2))
        assertEquals(moved, fires.size)
    }

    @Test
    fun `a cancelled gesture stops the repeat`() {
        val fires = mutableListOf<Unit>()
        val v = view(fires)
        touch(v, MotionEvent.ACTION_DOWN)
        looper.idleFor(java.time.Duration.ofMillis(HoldRepeat.DELAY_MS + HoldRepeat.INTERVAL_MS * 2))
        val sent = fires.size
        touch(v, MotionEvent.ACTION_CANCEL)
        looper.idleFor(java.time.Duration.ofSeconds(2))
        assertEquals(sent, fires.size)
    }
}
