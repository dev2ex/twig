package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class SearchStripItemTest {
    @Test fun `disabled search accepts long press but short taps movement and cancellation launch nothing`() {
        val page = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val item = SearchStripItem(page.get())
            page.get().setContentView(item)
            item.layout(0, 0, 200, 200)
            var clicks = 0
            var holds = 0
            item.setOnClickListener { clicks++ }
            item.setOnLongClickListener { holds++; true }
            item.isEnabled = false
            fun event(action: Int, down: Long, x: Float = 20f) {
                val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, 20f, 0)
                try { item.dispatchTouchEvent(motion) } finally { motion.recycle() }
            }
            fun waitForHold() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                ViewConfiguration.getLongPressTimeout().toLong() + ViewConfiguration.getTapTimeout() + 1))
            var down = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, down)
            event(MotionEvent.ACTION_UP, down)
            waitForHold()
            assertEquals(0, clicks)
            assertEquals(0, holds)

            down = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, down)
            waitForHold()
            event(MotionEvent.ACTION_UP, down)
            assertEquals(1, holds)
            assertEquals(0, clicks)

            down = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, down)
            event(MotionEvent.ACTION_CANCEL, down)
            waitForHold()
            assertEquals(1, holds)

            down = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, down)
            event(MotionEvent.ACTION_MOVE, down, x = 180f)
            waitForHold()
            event(MotionEvent.ACTION_UP, down, x = 180f)
            assertEquals(1, holds)
            assertEquals(0, clicks)

            item.isEnabled = true
            down = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, down)
            event(MotionEvent.ACTION_UP, down)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, clicks)
        } finally { page.pause().stop().destroy() }
    }
}
