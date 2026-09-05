package com.twig.app.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The contract behind "the terminal is garbled after the screen comes back on": a
 * window that is relaid out and ends up at the size it started from must not move the
 * terminal view at all, because every intermediate size reaches termux's emulator and
 * wrecks the alternate screen buffer (tmux/htop/vim) that the peer will not repaint.
 *
 * The assertion is on the child's real width/height, not on "was frozen set" — the
 * former is what `TerminalView.onSizeChanged` reacts to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermSizeFreezeTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()

    private fun box(): Pair<TermSizeFreezeLayout, View> {
        val box = TermSizeFreezeLayout(ctx, null)
        val child = View(ctx)
        box.addView(
            child,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return box to child
    }

    private fun layoutAt(box: TermSizeFreezeLayout, w: Int, h: Int) {
        box.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        box.layout(0, 0, w, h)
    }

    @Test
    fun `a frozen round trip never moves the terminal`() {
        val (box, child) = box()
        layoutAt(box, 1080, 1600)
        assertEquals(1600, child.height)

        box.frozen = true
        layoutAt(box, 1080, 1900) // the keyguard dismisses the IME: the window grows
        assertEquals("the emulator must not see the transient size", 1600, child.height)
        layoutAt(box, 1080, 1600) // …and it comes back once we are unlocked

        box.frozen = false
        layoutAt(box, 1080, 1600)
        assertEquals(1600, child.height)
    }

    @Test
    fun `a size that really changed lands when the freeze is lifted`() {
        val (box, child) = box()
        layoutAt(box, 1080, 1600)

        box.frozen = true
        layoutAt(box, 1080, 900) // rotated while we were away
        assertEquals(1600, child.height)

        box.frozen = false
        layoutAt(box, 1080, 900)
        assertEquals(900, child.height)
    }

    @Test
    fun `an unfrozen frame follows the window as usual`() {
        val (box, child) = box()
        layoutAt(box, 1080, 1600)
        layoutAt(box, 1080, 900) // the soft keyboard: a resize we do want forwarded
        assertEquals(900, child.height)
        assertEquals(1080, child.width)
    }
}
