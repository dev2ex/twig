package com.twig.app.ui

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A [GridLayoutManager] that picks its own column count from the width it is given:
 * `columns = width / cellDp`, clamped to [minSpan]..[maxSpan].
 *
 * ★ The count is decided **at the top of [onLayoutChildren]**, so the very pass that asks for it already
 * uses it. The pane used to compute it from an `OnLayoutChangeListener`, which only fires *after* the pass
 * that has already placed every row with the old count — the correction then had to wait for some later
 * pass. That is the whole "it opens with 4 columns and turns into 3 the moment you scroll, switch Activity
 * or turn the screen off" report (2026-09-19): on a pane recreated by a settings toggle the ViewModel still
 * holds its rows, so they are laid out immediately with the constructor's initial span count, and nothing
 * asks for another pass until the user touches something.
 *
 * Assigning `spanCount` from inside a layout pass is safe: the `requestLayout()` it triggers is swallowed by
 * RecyclerView there, but the new value is in place before GridLayoutManager recalculates its column borders
 * further down the same pass.
 */
class AutoFitGrid(
    ctx: Context,
    private val cellDp: Int = CELL_DP,
    private val minSpan: Int = 2,
    private val maxSpan: Int = 8,
    /**
     * The cell edge in px, reported whenever it changes so square cells can size their image box.
     * `rowsOnScreen` says whether rows are already laid out: those were bound with the previous edge and
     * need a rebind, which cannot happen from inside a layout pass.
     */
    private val onCellPx: (px: Int, rowsOnScreen: Boolean) -> Unit,
) : GridLayoutManager(ctx, INITIAL_SPAN) {

    private val dpi = ctx.resources.displayMetrics.density
    private val targetPx = (cellDp * dpi).toInt().coerceAtLeast(1)

    /** The 2dp padding on each side of `item_thumb_cell`, which the image box does not cover. */
    private val insetPx = (4 * dpi).toInt()

    private var reportedCellPx = -1

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        // Pre-layout runs against the *old* positions for item animations; changing the span count there
        // would make the two passes disagree about where a row is.
        if (!state.isPreLayout()) fit()
        super.onLayoutChildren(recycler, state)
    }

    private fun fit() {
        val w = width - paddingStart - paddingEnd
        if (w <= 0) return
        val n = (w / targetPx).coerceIn(minSpan, maxSpan)
        if (spanCount != n) spanCount = n
        val edge = w / n - insetPx
        if (edge == reportedCellPx) return
        reportedCellPx = edge
        onCellPx(edge, childCount > 0)
    }

    companion object {
        /**
         * Target edge of one grid cell, and so the only knob for "how many pictures per row".
         * 80dp rather than the original 96dp: a 384dp-wide phone (1260px at density 3.28) gives 332dp to the
         * pane once the 52dp action strip is taken out, and 332/96 = 3.46 lost a whole column by half a cell.
         */
        const val CELL_DP = 80

        /** Only ever on screen before the first layout pass measures the pane. */
        private const val INITIAL_SPAN = 4
    }
}
