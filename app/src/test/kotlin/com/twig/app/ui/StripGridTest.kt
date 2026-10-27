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
}
