package com.twig.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.twig.app.R
import com.twig.app.sqlite.SqliteCell
import com.twig.app.sqlite.SqliteColumn
import com.twig.app.sqlite.SqliteIdentity
import com.twig.app.sqlite.SqliteTable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SqliteGridViewTest {
    @Test fun `accessibility can scroll both axes independently in a wide tall table`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val grid = SqliteGridView(ContextThemeWrapper(activity, R.style.Theme_Twig))
        val position = SqliteViewport()
        grid.bind("data", List(6) { "column$it" }, 100, position,
            rowAt = { List(6) { SqliteCell.text("value") } })
        activity.setContentView(grid)
        grid.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY))
        grid.layout(0, 0, 320, 280)
        val right = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_RIGHT
        val left = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_LEFT
        val down = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_DOWN
        val up = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_UP
        val info = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.obtain()
        androidx.core.view.ViewCompat.getAccessibilityDelegate(grid)!!.onInitializeAccessibilityNodeInfo(grid, info)
        val actions = info.actionList.map { it.id }
        assertTrue(right.id in actions)
        assertTrue(down.id in actions)
        assertFalse(left.id in actions)
        assertFalse(androidx.core.view.ViewCompat.performAccessibilityAction(grid, left.id, null))
        assertTrue(androidx.core.view.ViewCompat.performAccessibilityAction(grid, right.id, null))
        val x = position.x
        assertTrue(x > 0)
        assertEquals(0f, position.y, 0f)
        assertTrue(androidx.core.view.ViewCompat.performAccessibilityAction(grid, down.id, null))
        val y = position.y
        assertTrue(y > 0)
        assertEquals(x, position.x, 0f)
        assertTrue(androidx.core.view.ViewCompat.performAccessibilityAction(grid, left.id, null))
        assertEquals(0f, position.x, 0f)
        assertEquals(y, position.y, 0f)
        assertTrue(androidx.core.view.ViewCompat.performAccessibilityAction(grid, up.id, null))
        assertEquals(0f, position.y, 0f)
    }

    @Test fun `drags at either table boundary never release the outer tree`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, R.style.Theme_Twig)
        for ((count, start, dy) in listOf(Triple(20, 0f, 80f), Triple(20, Float.MAX_VALUE, -80f))) {
            val interception = mutableListOf<Boolean>()
            val parent = object : FrameLayout(context) {
                override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
                    interception.add(disallow)
                    super.requestDisallowInterceptTouchEvent(disallow)
                }
            }
            val grid = SqliteGridView(context)
            val position = SqliteViewport(y = start)
            grid.bind("data", listOf("value"), count, position,
                rowAt = { listOf(SqliteCell.text("value")) })
            parent.addView(grid, FrameLayout.LayoutParams(320, 280))
            activity.setContentView(parent)
            parent.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 320, 280)
            val original = position.y
            fun touch(action: Int, y: Float, time: Long) {
                MotionEvent.obtain(0, time, action, 100f, y, 0).also { grid.onTouchEvent(it); it.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 100f, 0)
            touch(MotionEvent.ACTION_MOVE, 100f + dy, 50)
            touch(MotionEvent.ACTION_MOVE, 100f + dy * 1.5f, 70)
            assertTrue(interception.isNotEmpty())
            assertTrue(interception.all { it })
            assertEquals(original, position.y, 0.1f)
            touch(MotionEvent.ACTION_UP, 100f + dy * 1.5f, 80)
            assertFalse(interception.last())
        }
    }

    @Test fun `short tables release vertical drags but retain horizontal scrolling`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, R.style.Theme_Twig)
        for (count in listOf(0, 2)) {
            var disallowed = false
            val parent = object : FrameLayout(context) {
                override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
                    disallowed = disallow
                    super.requestDisallowInterceptTouchEvent(disallow)
                }
            }
            val grid = SqliteGridView(context)
            val position = SqliteViewport()
            var clicks = 0
            grid.bind("data", List(6) { "column$it" }, count, position,
                rowAt = { List(6) { SqliteCell.text("value") } }, cell = { _, _ -> clicks++ })
            parent.addView(grid, FrameLayout.LayoutParams(320, 280))
            activity.setContentView(parent)
            parent.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 320, 280)
            fun touch(action: Int, x: Float, y: Float, time: Long) {
                MotionEvent.obtain(0, time, action, x, y, 0).also { grid.onTouchEvent(it); it.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 200f, 100f, 0)
            touch(MotionEvent.ACTION_MOVE, 200f, 160f, 50)
            assertFalse(disallowed)
            touch(MotionEvent.ACTION_UP, 200f, 160f, 60)
            assertEquals(0, clicks)
            touch(MotionEvent.ACTION_DOWN, 200f, 100f, 100)
            touch(MotionEvent.ACTION_MOVE, 100f, 104f, 150)
            assertTrue(disallowed)
            assertTrue(position.x > 0f)
            assertEquals(0f, position.y, 0f)
            touch(MotionEvent.ACTION_UP, 100f, 104f, 160)
        }
    }

    @Test fun `each drag locks one axis and only visible rows are rendered`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val grid = SqliteGridView(ContextThemeWrapper(activity, R.style.Theme_Twig))
        val position = SqliteViewport(y = 64f)
        val reads = mutableSetOf<Int>()
        grid.bind("data", List(6) { "column$it" }, 1000000, position,
            rowAt = { row -> reads.add(row); List(6) { SqliteCell.text("row$row") } })
        activity.setContentView(grid)
        grid.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY))
        grid.layout(0, 0, 320, 280)
        fun event(action: Int, x: Float, y: Float, time: Long) {
            MotionEvent.obtain(0, time, action, x, y, 0).also { grid.onTouchEvent(it); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 200f, 100f, 0)
        event(MotionEvent.ACTION_MOVE, 100f, 104f, 50)
        assertEquals(100f, position.x, 0.1f)
        assertEquals(64f, position.y, 0.1f)
        event(MotionEvent.ACTION_UP, 100f, 104f, 60)
        event(MotionEvent.ACTION_DOWN, 100f, 150f, 100)
        event(MotionEvent.ACTION_MOVE, 104f, 50f, 150)
        assertEquals(100f, position.x, 0.1f)
        assertEquals(164f, position.y, 0.1f)
        event(MotionEvent.ACTION_UP, 104f, 50f, 160)
        grid.draw(Canvas(Bitmap.createBitmap(320, 280, Bitmap.Config.ARGB_8888)))
        assertTrue(reads.size in 1..12)
        assertTrue(reads.max() < 20)
    }

    @Test fun `table count slots remain visible when an ordinary row holder is reused`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(ContextThemeWrapper(activity, R.style.Theme_Twig))
        val table = SqliteTable("entries", "CREATE TABLE entries(value TEXT)",
            listOf(SqliteColumn("value", "TEXT", false, null, 0)), SqliteIdentity("rowid", emptyList()))
        val node = SqliteTableNode("db", 3, SqliteTableState(table, count = 1280, counting = false))
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(listOf(node))
        shadowOf(Looper.getMainLooper()).idle()
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.metaRow).visibility)
        assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.folderCounts).visibility)
        assertEquals("1280", holder.itemView.findViewById<android.widget.TextView>(R.id.directoryCount).text.toString())
        assertEquals("1", holder.itemView.findViewById<android.widget.TextView>(R.id.fileCount).text.toString())
        assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.check).visibility)

        val dataAdapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        dataAdapter.submitList(listOf(SqliteDataNode("db", 4, node.state.copy(expanded = true))))
        val dataHolder = dataAdapter.createViewHolder(parent, dataAdapter.getItemViewType(0))
        dataAdapter.bindViewHolder(dataHolder, 0)
        val row = dataHolder.itemView
        row.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, 600, row.measuredHeight)
        val grid = row.findViewById<SqliteGridView>(R.id.sqlite_grid)
        val stroke = row.resources.getDimension(R.dimen.highlight_stroke_width)
        assertTrue(grid.left > stroke)
        assertTrue(row.width - grid.right > stroke)
        assertTrue(row.height - grid.bottom > stroke)
    }
}
