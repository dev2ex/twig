package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R

/**
 * Row dividers for the tree-style list: drawn **only** between two ordinary full-width
 * rows (not between grid cells, not between a row and the info card). Intentionally
 * understated — 1px + half-transparent divider color, just a hint of boundary between
 * adjacent rows, not visually loud. Does not occupy layout space (no getItemOffsets),
 * so row height is unchanged; drawn in onDrawOver so the next row's selected-state
 * background does not paint over it.
 */
class RowDivider(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = paint(context)
    private val thickness = thickness(context)
    private val inset = 6f * context.resources.displayMetrics.density

    companion object {
        /** Divider paint; the toolbar (StripGrid) shares the same color set, so the two places look like the same line. */
        fun paint(context: Context): Paint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.divider)
            alpha = 190
        }

        /** Thickness is half a dp (1.5px on high-density screens): a full dp reads as too thick on phones, 1px is barely visible. */
        fun thickness(context: Context): Float =
            (0.5f * context.resources.displayMetrics.density).coerceAtLeast(1f)
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? FileAdapter ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            if (!adapter.isPlainRowAt(pos) || !adapter.isPlainRowAt(pos + 1)) continue
            val y = child.bottom.toFloat()
            c.drawRect(inset, y, parent.width - inset, y + thickness, paint)
        }
    }
}
