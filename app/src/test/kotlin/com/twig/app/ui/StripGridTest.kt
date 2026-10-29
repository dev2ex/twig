package com.twig.app.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.databinding.ActionStripBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class, qualifiers = "mdpi")
class StripGridTest {
    private fun layout(grid: StripGrid, columns: Int) {
        grid.measure(View.MeasureSpec.makeMeasureSpec(52 * columns, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
        grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
    }

    @Test fun `measured action grids can repeatedly shrink to one column without losing button state`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val strip = ActionStripBinding.inflate(LayoutInflater.from(ContextThemeWrapper(app, R.style.Theme_Twig)))
        for (grid in listOf(strip.asGrid, strip.asTop)) {
            grid.visibility = View.VISIBLE
            val first = grid.getChildAt(0)
            val hidden = grid.getChildAt(grid.childCount - 1)
            grid.setColumns(2)
            layout(grid, 2)
            hidden.visibility = View.GONE
            var clicks = 0
            first.setOnClickListener { clicks++ }
            repeat(10) {
                grid.setColumns(2)
                layout(grid, 2)
                assertEquals(2, grid.columnCount)
                assertEquals(52, first.width)
                assertTrue((0 until grid.childCount).map { grid.getChildAt(it) }
                    .any { it.visibility != View.GONE && it.left >= 52 })
                grid.setColumns(1)
                layout(grid, 1)
                assertEquals(1, grid.columnCount)
                assertEquals(52, first.width)
                for (index in 0 until grid.childCount) {
                    val child = grid.getChildAt(index)
                    if (child.visibility != View.GONE) assertEquals(0, child.left)
                }
                assertEquals(View.GONE, hidden.visibility)
                first.performClick()
            }
            assertEquals(10, clicks)
            // A previously hidden action must also have had its stale two-column index cleared.
            hidden.visibility = View.VISIBLE
            layout(grid, 1)
            assertEquals(0, hidden.left)
            assertEquals(52, hidden.width)
        }
    }
    @Test fun `custom order and visibility survive measured rotations and picker filtering`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("twig_prefs", 0)
        prefs.edit().clear().commit()
        try {
            val strip = ActionStripBinding.inflate(LayoutInflater.from(ContextThemeWrapper(app, R.style.Theme_Twig)))
            var clicks = 0
            strip.asInfo.setOnClickListener { clicks++ }
            strip.asGrid.setColumns(2)
            layout(strip.asGrid, 2)
            val all = com.twig.app.StripItems.all.map { it.id }.toSet()
            com.twig.app.Prefs.setStripItems(app, listOf("info", "copy", "info", "obsolete"), all - "delete")
            strip.asGrid.applyItems()
            assertSame(strip.asInfo, strip.asGrid.getChildAt(0))
            assertSame(strip.asCopy, strip.asGrid.getChildAt(1))
            assertEquals(View.GONE, strip.asDelete.visibility)
            repeat(4) {
                strip.asGrid.setColumns(1)
                layout(strip.asGrid, 1)
                assertEquals(0, strip.asInfo.left)
                assertEquals(52, strip.asInfo.width)
                strip.asGrid.setColumns(2)
                layout(strip.asGrid, 2)
            }
            strip.asGrid.applyItems(setOf("up", "info", "search"))
            assertEquals(View.GONE, strip.asCopy.visibility)
            assertEquals(View.VISIBLE, strip.asInfo.visibility)
            strip.asInfo.performClick()
            assertEquals(1, clicks)
            com.twig.app.Prefs.setStripItems(app, all.reversed(), all)
            strip.asGrid.applyItems()
            strip.asGrid.setColumns(1)
            layout(strip.asGrid, 1)
            assertEquals(View.VISIBLE, strip.asDelete.visibility)
            assertSame(strip.asExit, strip.asGrid.getChildAt(0))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun `properties defaults hidden and an explicit choice overrides the default`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("twig_prefs", 0)
        prefs.edit().clear().commit()
        try {
            val strip = ActionStripBinding.inflate(LayoutInflater.from(ContextThemeWrapper(app, R.style.Theme_Twig)))
            strip.asGrid.applyItems()
            assertEquals(View.GONE, strip.asInfo.visibility)
            assertEquals(View.GONE, strip.asExit.visibility)
            assertEquals("exit", com.twig.app.Prefs.stripItemOrder(app).last().id)
            assertEquals(View.VISIBLE, strip.asCopy.visibility)
            val ids = com.twig.app.StripItems.all.map { it.id }
            com.twig.app.Prefs.setStripItems(app, ids, ids.toSet())
            strip.asGrid.applyItems()
            assertEquals(View.VISIBLE, strip.asInfo.visibility)
            assertEquals(View.VISIBLE, strip.asExit.visibility)
        } finally {
            prefs.edit().clear().commit()
        }
    }

}
