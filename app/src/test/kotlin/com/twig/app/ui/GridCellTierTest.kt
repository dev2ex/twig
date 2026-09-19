package com.twig.app.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The grid's cell-size tier (settings page → Grid view → Cell size).
 *
 * The tier is a **cell edge**, not a column count — that is what makes one tier mean the same thing on a
 * phone and on a tablet, where the same edge simply fits more columns. Two things have to hold for the
 * setting to work at all, and both are asserted here against what is actually laid out:
 *
 *  - a tier has to change the number of columns the grid produces, and
 *  - it has to be part of `uiSignature`, or `MainActivity` never recreates on the way back from settings
 *    and the pane keeps the [AutoFitGrid] it was built with — the setting would look like it did nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi") // density 2.0: the three tiers are 128 / 160 / 192 px
class GridCellTierTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Lays a 1000px-wide grid out once at [tier] and counts the cells in its top row. */
    private fun columnsAt(tier: Int): Int {
        Prefs.setGridCell(app, tier)
        val rv = RecyclerView(app)
        rv.layoutManager = AutoFitGrid(app, cellDp = Prefs.gridCellDp(app)) { _, _ -> }
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
        rv.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        rv.layout(0, 0, 1000, 800)
        val top = (0 until rv.childCount).minOf { rv.getChildAt(it).top }
        return (0 until rv.childCount).count { rv.getChildAt(it).top == top }
    }

    @Test
    fun `the three tiers really lay out three different column counts`() {
        assertEquals(
            "1000px at density 2.0: 1000/128, 1000/160, 1000/192",
            listOf(7, 6, 5),
            (0..2).map { columnsAt(it) },
        )
    }

    @Test
    fun `normal is the default, and matches what the pane used before the setting existed`() {
        assertEquals(1, Prefs.gridCell(app))
        assertEquals(AutoFitGrid.CELL_DP, Prefs.gridCellDp(app))
    }

    @Test
    fun `changing the tier changes uiSignature, so the main UI recreates`() {
        Prefs.setGridCell(app, 1)
        val before = Prefs.uiSignature(app)
        Prefs.setGridCell(app, 0)
        assertNotEquals(before, Prefs.uiSignature(app))
    }
}
