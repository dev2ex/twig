package com.twig.app

import com.twig.app.ui.HexLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for the hex viewer's row-width budget.
 *
 * The row's padding lives in `dimens.xml` and is read by **both** `item_hex_row.xml` and
 * `HexViewerActivity`. It used to be a dimension in the layout plus a `ROW_PADDING_DP`
 * constant in Kotlin, and the two drifted: widening the layout's padding changed nothing
 * on screen, because the byte-count budget still reserved the old amount.
 *
 * What made that invisible bug expensive is the flooring. [HexLayout.bytesPerRow] rounds
 * down to a whole 2-byte group, so a row routinely lands just short of the next group and
 * leaves most of a group's width sitting empty — on the device this was found on the
 * budget worked out to 19.6 bytes and displayed 18. A few dp of over-reservation is
 * therefore not "a few dp narrower", it is a whole group missing, which is exactly what it
 * looked like: an obviously wide blank strip between the hex column and the divider.
 *
 * Numbers below are that device (1644px wide, density 3.5375, 6-digit offsets, and the
 * character width measured off its own screenshot).
 */
class HexRowWidthTest {

    private val screenPx = 1644f
    private val density = 3.5375f
    private val charW = 19.06f
    private val offDigits = 6
    private val dividerDp = 9f

    private fun bprFor(padStartDp: Float, padEndDp: Float): Int {
        val usable = screenPx - (padStartDp + padEndDp + dividerDp) * density
        return HexLayout.bytesPerRow(usable, charW, offDigits, 4, 64)
    }

    @Test
    fun `over-reserved end padding costs a whole group`() {
        // The old 30dp reservation, from when the scrollbar was always visible with a track.
        assertEquals(18, bprFor(8f, 30f))
        // And 16dp still lands short: the budget reaches 19.6 bytes and floors to 18.
        assertEquals(18, bprFor(8f, 16f))
    }

    @Test
    fun `releasing the reservation gains a group`() {
        assertEquals(20, bprFor(8f, 0f))
    }

    @Test
    fun `bytes per row is always a whole group`() {
        for (endDp in 0..40) {
            val bpr = bprFor(8f, endDp.toFloat())
            assertEquals("end padding ${endDp}dp produced an odd byte count", 0, bpr % 2)
        }
    }
}
