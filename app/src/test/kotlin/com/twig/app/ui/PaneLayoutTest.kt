package com.twig.app.ui

import android.animation.ValueAnimator
import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.R
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PaneLayoutTest {
    private fun layout(landscape: Boolean = false, mode: Int = Prefs.PANE_LAYOUT_FOLLOW_ORIENTATION): PaneLayout {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs.setPaneLayout(app, mode)
        val config = Configuration(app.resources.configuration).apply {
            orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        val ctx = app.createConfigurationContext(config)
        val dual = Prefs.dualPane(ctx)
        return PaneLayout(ctx).apply {
            for (id in intArrayOf(R.id.strip_left, R.id.pane_a, R.id.strip_mid, R.id.pane_b, R.id.strip_right)) {
                addView(View(ctx).apply {
                    this.id = id
                    visibility = when (id) {
                        R.id.strip_left -> View.GONE
                        R.id.strip_mid -> if (dual) View.VISIBLE else View.GONE
                        R.id.strip_right -> if (dual) View.GONE else View.VISIBLE
                        else -> View.VISIBLE
                    }
                }, ViewGroup.LayoutParams(if (id == R.id.pane_a || id == R.id.pane_b) 0 else 52, 600))
            }
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 600)
        }
    }

    @Test fun `all display modes measure and scroll according to visibility rather than orientation`() {
        for (landscape in listOf(false, true)) {
            for (mode in listOf(Prefs.PANE_LAYOUT_FOLLOW_ORIENTATION, Prefs.PANE_LAYOUT_SINGLE, Prefs.PANE_LAYOUT_DUAL)) {
                val dual = mode == Prefs.PANE_LAYOUT_DUAL || mode == Prefs.PANE_LAYOUT_FOLLOW_ORIENTATION && landscape
                val pages = layout(landscape, mode)
                val a = pages.findViewById<View>(R.id.pane_a)
                val b = pages.findViewById<View>(R.id.pane_b)
                assertEquals(if (dual) 174 else 348, a.width)
                assertEquals(if (dual) 400 else 748, b.right)
                pages.showPane(1, false)
                pages.finishTransition()
                assertEquals(if (dual) 0 else 348, pages.scrollX)
                pages.showPane(0, false)
                assertEquals(0, pages.scrollX)
            }
        }
    }

    @Test
    fun `portrait slide has exactly one action strip between adjacent panes at every frame`() {
        val pages = layout()
        val a = pages.findViewById<View>(R.id.pane_a)
        val b = pages.findViewById<View>(R.id.pane_b)
        val strip = pages.findViewById<View>(R.id.strip_right)
        assertEquals(348, a.width)
        assertEquals(748, b.right)
        pages.showPane(1, true)
        // Seek the real animator: unattached views have no display vsync in Robolectric.
        val animator = ReflectionHelpers.getField<ValueAnimator>(pages, "transition")
        for (time in listOf(0L, 40L, 80L, 120L, 160L)) {
            animator.currentPlayTime = time
            val x = pages.scrollX
            assertEquals(a.right - x, strip.left - x)
            assertEquals(strip.right - x, b.left - x)
            assertTrue("shared strip stays inside the viewport", strip.left - x >= 0 && strip.right - x <= 400)
            val visibleStrips = listOf(R.id.strip_left, R.id.strip_mid, R.id.strip_right)
                .map { pages.findViewById<View>(it) }
                .count { it.visibility == View.VISIBLE && it.right - x > 0 && it.left - x < 400 }
            assertEquals("only one action strip at time=$time", 1, visibleStrips)
        }
        animator.end()
        assertEquals(348, pages.scrollX)
        assertEquals(0, strip.left - pages.scrollX)
        assertEquals(52, b.left - pages.scrollX)
    }

    @Test
    fun `reverse swipe and backgrounding settle on the active page`() {
        val pages = layout()
        pages.showPane(1, true)
        // Seek the real animator: unattached views have no display vsync in Robolectric.
        ReflectionHelpers.getField<ValueAnimator>(pages, "transition").currentPlayTime = 100L
        pages.showPane(0, true)
        pages.finishTransition()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(0, pages.scrollX)
        pages.showPane(1, false)
        assertEquals(348, pages.scrollX)
    }

    @Test
    fun `landscape keeps both panes visible around the middle strip without scrolling`() {
        val pages = layout(landscape = true)
        val a = pages.findViewById<View>(R.id.pane_a)
        val b = pages.findViewById<View>(R.id.pane_b)
        val middle = pages.findViewById<View>(R.id.strip_mid)
        assertEquals(a.right, middle.left)
        assertEquals(middle.right, b.left)
        assertEquals(400, b.right)
        pages.showPane(1, true)
        pages.finishTransition()
        assertEquals(0, pages.scrollX)
    }
}
