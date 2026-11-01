package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class ToolbarLongPressTest {
    class Host : AppCompatActivity() {
        override fun onCreate(state: android.os.Bundle?) {
            setTheme(R.style.Theme_Twig)
            super.onCreate(state)
        }
    }

    @Test fun `real toolbar buttons consume long presses and keep their click actions`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        try {
            val toolbar = Toolbar(host.get()).apply {
                title = "Twig"
                setNavigationIcon(R.drawable.ic_parent_directory)
                menu.add(0, 12345, 0, "Terminal").apply {
                    setIcon(R.drawable.ic_terminal)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                menu.add("Settings")
            }
            host.get().setContentView(toolbar)
            toolbar.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
            toolbar.layout(0, 0, 600, 100)
            var clicks = 0
            val longPresses = mutableListOf<String>()
            toolbar.setOnMenuItemClickListener { clicks++; true }
            ToolbarLongPress.bind(toolbar, mapOf(12345 to { longPresses += "terminal" }),
                overflow = { longPresses += "menu" }, navigation = { longPresses += "up" },
                title = { longPresses += "title" })
            shadowOf(Looper.getMainLooper()).idle()
            val action = toolbar.findViewById<View>(12345)
            assertNotNull(action)
            assertTrue(action.performLongClick())
            val children = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
            assertTrue(children.filterIsInstance<ImageButton>().single().performLongClick())
            assertTrue(children.filterIsInstance<TextView>().single().performLongClick())
            val menu = children.filterIsInstance<ActionMenuView>().single()
            val overflow = (0 until menu.childCount).map { menu.getChildAt(it) }
                .single { (it.layoutParams as ActionMenuView.LayoutParams).isOverflowButton }
            assertTrue(overflow.performLongClick())
            assertEquals(listOf("terminal", "up", "title", "menu"), longPresses)
            assertEquals(0, clicks)
            action.performClick()
            assertEquals(1, clicks)
        } finally { host.pause().stop().destroy() }
    }
}
