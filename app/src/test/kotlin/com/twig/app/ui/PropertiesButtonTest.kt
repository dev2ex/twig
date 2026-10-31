package com.twig.app.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PropertiesButtonTest {
    private fun node(dir: Boolean, depth: Int = 1) = PaneViewModel.FileNode(
        XFile("file", if (dir) "/folder" else "/file.txt", dir), depth, dir, false)

    private fun holder(adapter: FileAdapter, position: Int = 0): RecyclerView.ViewHolder {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(ContextThemeWrapper(activity, R.style.Theme_Twig))
        activity.setContentView(parent)
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(position))
        parent.addView(holder.itemView)
        adapter.bindViewHolder(holder, position)
        holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        holder.itemView.layout(0, 0, 600, holder.itemView.measuredHeight)
        return holder
    }

    @Test fun `hidden circular button stays at the left edge without changing tree indentation`() {
        for (directory in listOf(false, true)) for (depth in listOf(0, 1, 4, 10)) {
            val node = node(directory, depth)
            var opens = 0
            var propertiesOpen = false
            val adapter = FileAdapter(1, { opens++ }, {}, {}, { _, _ -> }, {}, onInfo = {
                assertEquals(node, it)
                propertiesOpen = !propertiesOpen
            })
            adapter.submitList(listOf(node))
            val row = holder(adapter).itemView
            val button = row.findViewById<ImageButton>(R.id.info_toggle)
            val content = row.findViewById<View>(R.id.row_content)
            val indicator = row.findViewById<View>(R.id.indicator)
            val dpi = row.resources.displayMetrics.density
            assertEquals(View.VISIBLE, button.visibility)
            assertNull(button.drawable)
            assertTrue(button.background is RippleDrawable)
            val mask = (button.background as RippleDrawable).findDrawableByLayerId(android.R.id.mask) as GradientDrawable
            assertEquals(GradientDrawable.OVAL, mask.shape)
            assertEquals(0, button.left)
            assertEquals((36 * dpi).toInt(), button.width)
            assertEquals(row.height, button.height)
            assertEquals((44 * dpi).toInt(), row.height)
            button.background.setBounds(0, 0, button.width, button.height)
            assertEquals((32 * dpi).toInt(), mask.bounds.width())
            assertEquals(mask.bounds.width(), mask.bounds.height())
            assertEquals((4 * dpi).toInt(), mask.bounds.left)
            assertEquals(button.width.toFloat(), mask.bounds.right.toFloat(), 1f)
            assertEquals(button.height / 2f, mask.bounds.exactCenterY(), 1f)
            assertEquals(content.top + indicator.top + indicator.height / 2f,
                button.top + mask.bounds.exactCenterY(), 1f)
            assertEquals(((2 + depth * 14) * dpi).toInt(), content.paddingStart)
            assertEquals(content.paddingStart, indicator.left)
            val icon = row.findViewById<View>(R.id.icon)
            assertEquals(content.paddingStart + (18 * dpi).toInt(), icon.left)
            assertEquals(row.context.getString(R.string.file_info), button.contentDescription)
            val x = button.width / 2f
            val y = button.top + button.height / 2f
            fun touch(action: Int, atX: Float = x, atY: Float = y) {
                MotionEvent.obtain(0, 10, action, atX, atY, 0).also {
                    assertTrue(row.dispatchTouchEvent(it))
                    it.recycle()
                }
            }
            touch(MotionEvent.ACTION_DOWN)
            assertTrue(button.isPressed)
            touch(MotionEvent.ACTION_UP)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(propertiesOpen)
            assertEquals(0, opens)
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(propertiesOpen)
            if (directory && indicator.right <= button.right) {
                val arrowX = indicator.left + indicator.width / 2f
                val arrowY = indicator.top + indicator.height / 2f
                touch(MotionEvent.ACTION_DOWN, arrowX, arrowY)
                touch(MotionEvent.ACTION_UP, arrowX, arrowY)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(propertiesOpen)
                assertEquals(0, opens)
                button.performClick()
                assertFalse(propertiesOpen)
            }
            // Deep arrows lie outside the fixed Properties button and retain the row's normal action.
            if (directory && indicator.left > button.right) {
                val arrowX = indicator.left + indicator.width / 2f
                val arrowY = indicator.top + indicator.height / 2f
                touch(MotionEvent.ACTION_DOWN, arrowX, arrowY)
                touch(MotionEvent.ACTION_UP, arrowX, arrowY)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, opens)
                assertFalse(propertiesOpen)
            }
        }
    }

    @Test fun `virtual rows clear the properties button when a holder is reused`() {
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(listOf(node(false), PaneViewModel.GroupNode("lan", "LAN", false)))
        val row = holder(adapter)
        assertEquals(View.VISIBLE, row.itemView.findViewById<View>(R.id.info_toggle).visibility)
        adapter.onBindViewHolder(row, 1)
        val button = row.itemView.findViewById<View>(R.id.info_toggle)
        assertEquals(View.INVISIBLE, button.visibility)
        assertFalse(button.hasOnClickListeners())
    }

    @Test fun `refreshing a file row does not hide its running properties ripple`() {
        val original = node(true)
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(listOf(original))
        val holder = holder(adapter) as FileAdapter.VH
        val button = holder.itemView.findViewById<View>(R.id.info_toggle)
        val ripple = object : RippleDrawable(ColorStateList.valueOf(Color.GREEN), null, ColorDrawable(Color.WHITE)) {
            var hides = 0
            override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
                if (!visible) hides++
                return super.setVisible(visible, restart)
            }
        }
        button.background = ripple
        ripple.hides = 0
        button.isPressed = true
        // Count probes update the visible text first; the next VM rebuild includes the cached count
        // in FileNode and DiffUtil rebinds this row as Properties opens.
        holder.bind(original.copy(directoryCount = DirectoryCounts.Count(2, 3)))
        assertEquals(0, ripple.hides)
        assertTrue(button.isPressed)
        button.isPressed = false
        holder.bind(original)
        assertEquals(0, ripple.hides)
    }

    @Test fun `a press ending in the properties square never clicks the underlying node`() {
        for (grid in listOf(0, 2)) {
            var opens = 0
            var properties = 0
            val adapter = FileAdapter(1, { opens++ }, {}, {}, { _, _ -> }, {}, gridMode = grid, onInfo = { properties++ })
            adapter.submitList(listOf(node(false)))
            val row = holder(adapter).itemView
            val button = row.findViewById<View>(R.id.info_toggle)
            val bounds = Rect()
            button.getDrawingRect(bounds)
            (row as ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
            val y = bounds.exactCenterY()
            fun event(action: Int, x: Float) {
                MotionEvent.obtain(0, 10, action, x, y, 0).also {
                    row.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
            // A small finger drift across the boundary must not become a directory expand/collapse.
            event(MotionEvent.ACTION_DOWN, bounds.right + 1f)
            event(MotionEvent.ACTION_UP, bounds.right - 1f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("grid=$grid bounds=$bounds", 0, opens)
            assertEquals(0, properties)
            assertFalse(row.isPressed)
            event(MotionEvent.ACTION_DOWN, bounds.right + 20f)
            event(MotionEvent.ACTION_UP, bounds.right + 20f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, opens)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the full height of the properties strip consumes taps and cancelled drags`() {
        for (directory in listOf(false, true)) for (density in 0..2) for (tall in listOf(false, true)) {
            var opens = 0
            var properties = 0
            val adapter = FileAdapter(density, { opens++ }, {}, {}, { _, _ -> }, {}, onInfo = { properties++ })
            val original = node(directory)
            val file = if (tall) original.copy(file = original.file.copy(displayName = "long_name_".repeat(100))) else original
            adapter.submitList(listOf(file))
            val row = holder(adapter).itemView
            val button = row.findViewById<View>(R.id.info_toggle)
            assertEquals(row.height, button.height)
            if (tall) assertTrue(row.height > intArrayOf(36, 44, 54)[density] * row.resources.displayMetrics.density)
            fun event(action: Int, x: Float, y: Float) {
                MotionEvent.obtain(0, 10, action, x, y, 0).also {
                    row.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
            for (x in listOf(button.left + 0.5f, button.right - 0.5f))
                for (y in listOf(0.5f, row.height - 0.5f)) {
                    event(MotionEvent.ACTION_DOWN, x, y)
                    event(MotionEvent.ACTION_UP, x, y)
                    shadowOf(Looper.getMainLooper()).idle()
                }
            assertEquals(4, properties)
            assertEquals(0, opens)
            val x = button.left + button.width / 2f
            val y = button.top + button.height / 2f
            val outside = button.right + 100f
            event(MotionEvent.ACTION_DOWN, x, y)
            event(MotionEvent.ACTION_MOVE, outside, y)
            event(MotionEvent.ACTION_UP, outside, y)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(4, properties)
            assertEquals(0, opens)
            event(MotionEvent.ACTION_DOWN, x, y)
            event(MotionEvent.ACTION_CANCEL, x, y)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(4, properties)
            assertEquals(0, opens)
            assertFalse(button.isPressed)
        }
    }

    @Test fun `grid files have the same blank properties button`() {
        val node = node(false)
        var clicks = 0
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, gridMode = 2, onInfo = {
            assertEquals(node, it); clicks++
        })
        adapter.submitList(listOf(node))
        val row = holder(adapter).itemView
        val button = row.findViewById<ImageButton>(R.id.info_toggle)
        assertNull(button.drawable)
        assertTrue(button.background is RippleDrawable)
        button.performClick()
        assertEquals(1, clicks)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `selection strip fills the row without enlarging it and isolates row clicks`() {
        for (density in 0..2) {
            var opens = 0
            var selections = 0
            val adapter = FileAdapter(density, { opens++ }, {}, { selections++ }, { _, _ -> }, {})
            adapter.submitList(listOf(node(false)))
            val row = holder(adapter).itemView
            shadowOf(Looper.getMainLooper()).idle()
            val check = row.findViewById<android.widget.ImageView>(R.id.check)
            val bounds = Rect()
            check.getDrawingRect(bounds)
            (row as ViewGroup).offsetDescendantRectToMyCoords(check, bounds)
            assertEquals((intArrayOf(36, 44, 54)[density] * row.resources.displayMetrics.density).toInt(), row.height)
            assertEquals(row.height, check.height)
            assertNotNull(check.drawable)
            assertTrue(check.background is RippleDrawable)
            fun event(action: Int, x: Float, y: Float) {
                MotionEvent.obtain(0, 10, action, x, y, 0).also {
                    row.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
            for (y in listOf(0.5f, row.height - 0.5f)) {
                event(MotionEvent.ACTION_DOWN, bounds.exactCenterX(), y)
                event(MotionEvent.ACTION_UP, bounds.exactCenterX(), y)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(2, selections)
            assertEquals(0, opens)
            event(MotionEvent.ACTION_DOWN, bounds.left - 1f, bounds.exactCenterY())
            event(MotionEvent.ACTION_UP, bounds.left + 1f, bounds.exactCenterY())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, selections)
            assertEquals(0, opens)
        }
    }
}
