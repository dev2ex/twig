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

    /** Strip buttons use automatic positions and equal-width columns in both orientations. */
    fun setColumns(columns: Int) {
        if (columnCount == columns) return
        // GridLayout writes assigned indices back into its child specs during layout.
        // Reset them before reducing the column count, including specs of hidden buttons.
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layoutParams = (child.layoutParams as LayoutParams).apply {
                rowSpec = spec(UNDEFINED)
                columnSpec = spec(UNDEFINED, FILL, 1f)
            }
        }
        columnCount = columns
    }

    private var appliedItems: List<Pair<String, Boolean>>? = null

    /** Reset assigned specs even without a rotation: reordering follows a previous layout. */
    fun applyItems(available: Set<String> = com.twig.app.StripItems.all.mapTo(HashSet()) { it.id }) {
        val items = com.twig.app.Prefs.stripItemOrder(context)
        val state = items.map { it.id to (it.id in available && com.twig.app.Prefs.stripItemVisible(context, it.id)) }
        if (appliedItems == state) return
        appliedItems = state
        val views = items.map { item -> item to findViewById<View>(item.viewId) }
        removeAllViews()
        for ((item, child) in views) {
            child.layoutParams = (child.layoutParams as LayoutParams).apply {
                rowSpec = spec(UNDEFINED)
                columnSpec = spec(UNDEFINED, FILL, 1f)
            }
            child.visibility = if (item.id in available && com.twig.app.Prefs.stripItemVisible(context, item.id))
                View.VISIBLE else View.GONE
            addView(child)
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
