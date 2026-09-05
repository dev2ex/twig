package com.twig.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.text.TextPaint
import android.util.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The accessory key bar's text sizing. Runs with native graphics so labels are measured
 * with real font metrics — Robolectric's stub paint reports one pixel per character, and
 * with it every label "fits" at any size, which would make these assertions meaningless.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TermKeyFitTest {

    /** The widest labels of the two rows; those are the ones that get clipped. */
    private val labels = listOf("ESC", "/", "|", "-", "HOME", "▲", "END", "PGUP",
        "TAB", "CTRL", "SHIFT", "ALT", "◀", "▼", "▶", "PGDN")

    private fun ctx(fontScale: Float): Context {
        val base = Robolectric.buildActivity(Activity::class.java).setup().get()
        val cfg = Configuration(base.resources.configuration).apply { this.fontScale = fontScale }
        return base.createConfigurationContext(cfg)
    }

    private fun fit(fontScale: Float): Triple<Float, Float, Int> {
        val c = ctx(fontScale)
        val dm = c.resources.displayMetrics
        val full = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, dm)
        val cell = (45 * dm.density).toInt() // 360dp / 8 keys
        val size = TermKeyFit.textSize(
            cell, labels, TextPaint(),
            fullPx = full,
            minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, dm),
            padPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, dm),
        )
        return Triple(size, full, cell)
    }

    /** At the normal font scale nothing may shrink: the bar must look exactly as before. */
    @Test fun `at the default font scale the keys keep the full size`() {
        val (size, full, _) = fit(1f)
        assertEquals(full, size, 0.01f)
    }

    /** …and at a large one every label fits, at one size shared by the whole bar. */
    @Test fun `at a large font scale every label still fits its cell`() {
        for (scale in listOf(1.3f, 1.5f, 2f)) {
            val (size, full, cell) = fit(scale)
            assertTrue("$scale: should have shrunk from $full", size < full)
            val p = TextPaint().apply { textSize = size }
            val widest = labels.maxOf { p.measureText(it) }
            assertTrue("$scale: widest label is ${widest}px in a ${cell}px cell", widest <= cell)
        }
    }
}
