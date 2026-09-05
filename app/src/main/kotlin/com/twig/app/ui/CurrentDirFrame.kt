package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R

/**
 * Highlight frame around the currently selected node: encloses "the most recently
 * selected node row (directory / server / group / favorite — any kind) **plus its
 * directly-expanded children**". Only down to direct children (bottom edge on the last
 * direct child), not recursively wrapping deeper grandchildren — otherwise expanding a
 * root / group (with deeply-expanded servers / directories under it) would pull the
 * frame all the way down the tree, making it too tall. When focus moves into a specific
 * child, the frame follows currentKey to that level. If the frame's top / bottom edges
 * fall outside the visible area, that edge is pushed off-screen and only the side is drawn.
 */
class CurrentDirFrame(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * context.resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val rect = RectF()

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? FileAdapter ?: return
        if (parent.childCount == 0) return
        val key = adapter.currentKey ?: return
        val list = adapter.currentList
        val start = list.indexOfFirst { it.key == key }
        if (start < 0) return
        // The bottom edge stops at "the last direct child (depth+1)", skipping the deeper
        // grandchildren that direct children may themselves have expanded
        val depth = list[start].depth
        var end = start
        var i = start + 1
        while (i < list.size && list[i].depth > depth) {
            if (list[i].depth == depth + 1) end = i
            i++
        }

        val firstPos = parent.getChildAdapterPosition(parent.getChildAt(0))
        val lastPos = parent.getChildAdapterPosition(parent.getChildAt(parent.childCount - 1))
        if (firstPos == RecyclerView.NO_POSITION || lastPos == RecyclerView.NO_POSITION) return
        if (end < firstPos || start > lastPos) return // Subtree is entirely outside the visible area

        val off = paint.strokeWidth // When out of bounds, push the edge off-screen, leaving only the side
        // Use the final layout positions (top/bottom), excluding the animation transition
        // offsets, so the frame does not stretch along with animations
        val top = if (start >= firstPos) {
            parent.findViewHolderForAdapterPosition(start)?.itemView?.top?.toFloat() ?: -off
        } else -off
        val bottom = if (end <= lastPos) {
            parent.findViewHolderForAdapterPosition(end)?.itemView?.bottom?.toFloat()
                ?: (parent.height + off)
        } else parent.height + off

        val half = paint.strokeWidth / 2
        rect.set(half, top + half, parent.width - half, bottom - half)
        c.drawRect(rect, paint)
    }
}
