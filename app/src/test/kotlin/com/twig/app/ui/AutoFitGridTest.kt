package com.twig.app.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The grid's column count must be right in **the first layout pass**, not one pass later.
 *
 * It used to be computed from an `OnLayoutChangeListener`, i.e. after the rows had already been placed with
 * `GridLayoutManager`'s initial span count of 4. On a 384dp phone (1260px at density 3.28) the pane is 332dp
 * wide and wants 4 columns; on a pane recreated by a settings toggle the ViewModel still held its rows, so
 * they were laid out immediately with the stale count and nothing asked for another pass -- the grid sat at
 * the wrong number until the user scrolled, switched Activity or turned the screen off, and then silently
 * re-flowed. Reported 2026-09-19, reproduced on a vivo PD2309.
 *
 * So every assertion here is on **what a single layout pass actually produced** -- how many cells share the
 * top row, and how wide they are -- never on `spanCount`, which the old code also ended up setting correctly
 * while the screen still showed the old arrangement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi") // density 2.0, so CELL_DP 80 = 160px
class AutoFitGridTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dpi get() = app.resources.displayMetrics.density

    private lateinit var rv: RecyclerView
    private var cellPx = -1
    private var rebindAsked = false

    private fun build(width: Int): RecyclerView {
        rv = RecyclerView(app)
        rv.layoutManager = AutoFitGrid(app) { px, rowsOnScreen ->
            cellPx = px
            rebindAsked = rowsOnScreen
        }
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 40
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(
                    View(app).apply {
                        layoutParams = RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 100,
                        )
                    },
                ) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        layout(width)
        return rv
    }

    private fun layout(width: Int, height: Int = 800) {
        rv.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        rv.layout(0, 0, width, height)
    }

    /** Cells sharing the topmost row after the pass that just ran. */
    private fun firstRowCount(): Int {
        val top = (0 until rv.childCount).minOf { rv.getChildAt(it).top }
        return (0 until rv.childCount).count { rv.getChildAt(it).top == top }
    }

    @Test
    fun `one pass is enough -- the first row already has the right number of cells`() {
        build(width = 1000) // 1000 / 160 = 6 columns; 4 would be the un-corrected initial value
        assertEquals("six cells fit, and the first pass must already show six", 6, firstRowCount())
        // GridLayoutManager hands the division's remainder to the leftmost columns, so the widths are 167/166,
        // not a flat 1000/6 -- what matters is that the row is filled edge to edge with no gap left over.
        val top = (0 until rv.childCount).minOf { rv.getChildAt(it).top }
        val rowWidth = (0 until rv.childCount)
            .map { rv.getChildAt(it) }.filter { it.top == top }.sumOf { it.width }
        assertEquals("the six columns fill the pane exactly", 1000, rowWidth)
    }

    @Test
    fun `the cell edge is reported with the padding of item_thumb_cell taken out`() {
        build(width = 1000)
        assertEquals("column width minus the cell's own 4dp padding", 1000 / 6 - (4 * dpi).toInt(), cellPx)
        assertFalse("nothing was on screen yet, so no rebind is needed", rebindAsked)
    }

    @Test
    fun `a narrower pane re-flows in its own pass, and asks for the bound rows to be rebound`() {
        build(width = 1000)
        rebindAsked = false
        layout(width = 400) // 400 / 160 = 2 columns
        assertEquals("the same pass that narrowed the pane must also re-flow it", 2, firstRowCount())
        assertEquals(400 / 2 - (4 * dpi).toInt(), cellPx)
        assertTrue("rows bound at the old edge are on screen and need rebinding", rebindAsked)
    }

    @Test
    fun `a pane too narrow for even two columns still gets two`() {
        build(width = 200) // 200 / 160 = 1, clamped up
        assertEquals(2, firstRowCount())
    }

    @Test
    fun `a very wide pane is capped, so cells never turn into a stamp sheet`() {
        build(width = 4000) // 4000 / 160 = 25, clamped down
        assertEquals(8, firstRowCount())
    }
}
