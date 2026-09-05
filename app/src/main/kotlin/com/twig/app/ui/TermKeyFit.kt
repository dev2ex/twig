package com.twig.app.ui

import android.graphics.Paint

/**
 * Text sizing for the terminal's accessory key bar.
 *
 * The eight keys of a row split the width evenly, so a cell is only ~45dp on a 360dp
 * screen — at a large system font scale the longest labels ("SHIFT", "PGUP") no longer
 * fit. Per-button auto-sizing does fix that, but it leaves the bar ragged: "SHIFT" ends
 * up at 9sp right next to "ESC" at 12sp. So the whole bar shares **one** size: the
 * largest at which the widest label still fits its cell.
 */
object TermKeyFit {
    /**
     * @param cellPx   width of a single key
     * @param fullPx   the size we would like to use (12sp, so it still follows the
     *                 user's font scale as long as it fits)
     * @param minPx    never go below this, however narrow the screen
     * @param padPx    breathing room kept on both sides of a label together
     * @param paint    a paint already configured like the buttons (bold: modifier keys
     *                 turn bold while held, which is the widest they ever get)
     */
    fun textSize(
        cellPx: Int,
        labels: List<CharSequence>,
        paint: Paint,
        fullPx: Float,
        minPx: Float,
        padPx: Float,
    ): Float {
        val avail = cellPx - padPx
        if (cellPx <= 0 || avail <= 0f || labels.isEmpty()) return fullPx
        paint.textSize = fullPx
        val widest = labels.maxOf { paint.measureText(it, 0, it.length) }
        if (widest <= avail) return fullPx
        return maxOf(minPx, fullPx * avail / widest)
    }
}
