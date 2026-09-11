package com.twig.app.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Row height and text size are two **mutually independent** preference levels (split on
 * 2026-08-26). There used to be a single "row height" level that also set the font size,
 * so "shorter rows but still-readable text" had no way to happen.
 *
 * The assertion is on **the value actually rendered** (the row's minimum height in px, the
 * TextView's font size in px), not "does reading the preference back equal such-and-such" --
 * the latter would still pass on the version where the adapter derives font size from
 * density.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RowSizeSplitTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dpi get() = app.resources.displayMetrics.density
    private val scaled get() = app.resources.displayMetrics.scaledDensity

    private fun themed() = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))

    /** Binds one plain-file row, returns (row minimum height px, file name font size px). */
    private fun render(density: Int, textSize: Int): Pair<Int, Float> {
        val adapter = FileAdapter(density, {}, {}, {}, { _, _ -> }, {}, textSize = textSize)
        val node = PaneViewModel.FileNode(
            XFile("file", "/sdcard/a.txt", isDir = false),
            depth = 1, expandable = false, expanded = false,
        )
        adapter.submitList(listOf(node))
        val vh = adapter.createViewHolder(themed(), adapter.getItemViewType(0))
        adapter.bindViewHolder(vh, 0)
        val name = vh.itemView.findViewById<TextView>(R.id.name)
        return vh.itemView.minimumHeight to name.textSize
    }

    @Test
    fun `short row but large text -- each level manages its own thing`() {
        val (h, sp) = render(density = 0, textSize = 2)
        assertEquals("row height should follow density=0's 36dp", (36 * dpi).toInt(), h)
        assertEquals("font size should follow textSize=2's 15.5sp", 15.5f * scaled, sp, 0.6f)
    }

    @Test
    fun `roomy row but small text -- holds the other way around too`() {
        val (h, sp) = render(density = 2, textSize = 0)
        assertEquals("row height should follow density=2's 54dp", (54 * dpi).toInt(), h)
        assertEquals("font size should follow textSize=0's 12.5sp", 12.5f * scaled, sp, 0.6f)
    }

    /**
     * A user upgrading from an old version (prefs has only row height, no text size key
     * yet) must look pixel-identical -- font size falls back to the row-height level. ★
     * The key name is written literally here **on purpose**: the upgrade path depends on it
     * matching, and renaming the key would silently reset every existing user's row-height
     * setting back to the default overnight.
     */
    @Test
    fun `text size falls back to the row-height level after an upgrade`() {
        app.getSharedPreferences("twig_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putInt("row_density", 2).commit()
        assertEquals("the old version only stored row height, font size should follow it", 2, Prefs.textSize(app))
    }

    /** Once the user has chosen their own text size, it becomes fully independent -- adjusting row height afterward must not touch it. */
    @Test
    fun `once text size has been chosen, adjusting row height no longer affects it`() {
        Prefs.setTextSize(app, 0)
        Prefs.setDensity(app, 1)
        Prefs.setDensity(app, 2)
        assertEquals(0, Prefs.textSize(app))
    }

    /**
     * ★ When the user has never touched text size, adjusting row height must **not** touch
     * it either -- the default following row height is only a one-time "take the default"
     * moment; `setDensity` pins down the current font size first. This is the entire point
     * of the split: without it, "adjusting row height drags the font size along" would
     * still happen.
     */
    @Test
    fun `adjusting row height does not change text size`() {
        Prefs.setDensity(app, 0) // text size was never set, so it stays pinned at the default 1 (medium)
        val (_, before) = render(Prefs.density(app), Prefs.textSize(app))

        Prefs.setDensity(app, 2) // adjust row height only

        assertEquals("text size should stay put after adjusting row height", 1, Prefs.textSize(app))
        val (h, after) = render(Prefs.density(app), Prefs.textSize(app))
        assertEquals("row height should follow it to become 54dp", (54 * dpi).toInt(), h)
        assertEquals("font size should not move by even one pixel", before, after, 0.01f)
    }

    /** Both go into uiSignature: changing text size alone must also make MainActivity recreate when returning from settings. */
    @Test
    fun `uiSignature changes when text size changes`() {
        Prefs.setTextSize(app, 0)
        val before = Prefs.uiSignature(app)
        Prefs.setTextSize(app, 2)
        assertEquals(false, before == Prefs.uiSignature(app))
    }
}
