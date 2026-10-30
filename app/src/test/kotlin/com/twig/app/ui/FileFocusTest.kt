package com.twig.app.ui

import android.app.Activity
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import com.twig.app.R
import com.twig.app.Prefs
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
    @Test fun `checked mode selects rows while icons open without changing selection`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val parent = FrameLayout(ContextThemeWrapper(host.get(), R.style.Theme_Twig))
            for (grid in listOf(0, 2)) {
                var opens = 0
                val adapter = FileAdapter(1, { opens++ }, {}, {}, { _, _ -> }, {}, gridMode = grid)
                val first = PaneViewModel.FileNode(XFile("file", "/selection/a.txt", false, canRead = false), 1, false, false)
                val second = PaneViewModel.FileNode(XFile("file", "/selection/b.txt", false, canRead = false), 1, false, false)
                adapter.submitList(listOf(first, second))
                val holder = adapter.createViewHolder(parent, adapter.getItemViewType(1))
                adapter.bindViewHolder(holder, 1)
                val row = holder.itemView
                row.performClick()
                assertFalse(adapter.isSelected(second))
                assertEquals(1, opens)
                adapter.toggleSelection(first)
                assertFalse(Prefs.tapRowToSelect(parent.context))
                val before = Prefs.uiSignature(parent.context)
                row.performClick()
                assertEquals(2, opens)
                assertFalse(adapter.isSelected(second))
                Prefs.setTapRowToSelect(parent.context, true)
                assertNotEquals(before, Prefs.uiSignature(parent.context))
                row.performClick()
                assertTrue(adapter.isSelected(first))
                assertTrue(adapter.isSelected(second))
                assertEquals(2, opens)
                row.findViewById<android.view.View>(if (grid == 0) R.id.icon else R.id.thumb).performClick()
                assertEquals(3, opens)
                assertTrue(adapter.isSelected(second))
                row.performClick()
                assertFalse(adapter.isSelected(second))
                adapter.clearSelection()
                row.findViewById<android.view.View>(if (grid == 0) R.id.icon else R.id.thumb).performClick()
                assertEquals(4, opens)
                assertTrue(adapter.selectedItems().isEmpty())
                row.performClick()
                assertFalse(adapter.isSelected(second))
                assertEquals(5, opens)
                Prefs.setTapRowToSelect(parent.context, false)
            }
        } finally { host.pause().stop().destroy() }
    }

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
                val node = PaneViewModel.FileNode(file, 1, false, false)
                adapter.submitList(listOf(node))
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
                assertFalse(adapter.isSelected(node))
                assertTrue(row.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER)))
                row.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(1, clicks)
                assertFalse(adapter.isSelected(node))
                parent.removeView(row)
            }
        } finally { host.pause().stop().destroy() }
    }
}
