package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.widget.GridLayout

/**
 * GridLayout used for the toolbar: it draws dividers between buttons, in exactly the
 * same style as the list's [RowDivider] (same paint, same thickness), controlled by
 * the same `Prefs.rowDivider` switch.
 *
 * GridLayout has no `showDividers` like LinearLayout, so we draw them ourselves
 * after dispatchDraw: **only horizontal lines between rows, one per row, spanning
 * the full width** (in landscape's two columns it is not one segment per column —
 * buttons are grouped by bottom and the two buttons in the same row share one line);
 * no vertical lines are drawn between columns. Row membership is not decided from
 * row/column indices (the column count changes between 1 and 2 with rotation), only
 * from each child View's actual bottom, and the last row is naturally skipped.
 */
class StripGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : GridLayout(context, attrs, defStyle) {

    private val paint = RowDivider.paint(context)
    private val thickness = RowDivider.thickness(context)
    private val inset = 6f * context.resources.displayMetrics.density

    /** Whether to draw dividers; shares the row divider preference, back-filled by MainActivity. */
    var dividers = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!dividers) return
        val kids = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
        if (kids.size < 2) return
        val maxBottom = kids.maxOf { it.bottom }
        val left = paddingLeft + inset
        val right = width - paddingRight - inset
        for (y in kids.map { it.bottom }.distinct().sorted()) {
            if (y >= maxBottom) continue // skip drawing below the last row
            canvas.drawRect(left, y.toFloat(), right, y + thickness, paint)
        }
    }
}
