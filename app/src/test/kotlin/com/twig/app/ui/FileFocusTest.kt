package com.twig.app.ui

import android.app.Activity
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class FileFocusTest {
    @Test fun `list and grid rows can receive remote focus with a distinct background even when checked`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = host.get()
            val ctx = ContextThemeWrapper(activity, R.style.Theme_Twig)
            val parent = FrameLayout(ctx)
            activity.setContentView(parent)
            for (grid in listOf(0, 2)) {
                var clicks = 0
                val adapter = FileAdapter(1, { clicks++ }, {}, {}, { _, _ -> }, {}, gridMode = grid)
                val file = XFile("file", "/focus-test.txt", false, canRead = false)
                adapter.submitList(listOf(PaneViewModel.FileNode(file, 1, false, false)))
                val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
                adapter.bindViewHolder(holder, 0)
                val row = holder.itemView
                parent.addView(row)
                assertTrue("grid=$grid", row.isFocusable)
                val background = row.background
                background.state = intArrayOf()
                val normal = background.current
                background.state = intArrayOf(android.R.attr.state_focused)
                val focused = background.current
                assertNotSame(normal, focused)
                background.state = intArrayOf(android.R.attr.state_activated)
                assertNotSame(focused, background.current)
                background.state = intArrayOf(android.R.attr.state_activated, android.R.attr.state_focused)
                assertSame(focused, background.current)
                row.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.AT_MOST))
                row.layout(0, 0, 400, row.measuredHeight)
                assertTrue(row.requestFocus())
                assertTrue(row.isFocused)
                assertTrue(row.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER)))
                row.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(1, clicks)
                parent.removeView(row)
            }
        } finally { host.pause().stop().destroy() }
    }
}
