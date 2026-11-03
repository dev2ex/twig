package com.twig.app.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView

/** Reclaims indentation only after every visible ancestor has left the viewport. */
internal class TreeIndentation(
    private val list: RecyclerView,
    private val adapter: FileAdapter,
    private val enabled: () -> Boolean,
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private var target = 0
    private var animator: ValueAnimator? = null
    private var dirty = true
    private var wasEnabled = enabled()
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0) dirty = true
        }
    }
    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() { dirty = true }
        override fun onItemRangeChanged(start: Int, count: Int) { dirty = true }
        override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) { dirty = true }
        override fun onItemRangeInserted(start: Int, count: Int) { dirty = true }
        override fun onItemRangeRemoved(start: Int, count: Int) { dirty = true }
        override fun onItemRangeMoved(from: Int, to: Int, count: Int) { dirty = true }
    }
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) dirty = true
    }

    init {
        list.addOnAttachStateChangeListener(this)
        list.addOnScrollListener(scrollListener)
        list.addOnLayoutChangeListener(layoutListener)
        adapter.registerAdapterDataObserver(dataObserver)
        if (list.isAttachedToWindow) onViewAttachedToWindow(list)
    }

    override fun onPreDraw(): Boolean {
        if (!list.isShown || list.isComputingLayout) return true
        val isEnabled = enabled()
        if (isEnabled != wasEnabled) dirty = true
        wasEnabled = isEnabled
        if (!dirty) return true
        dirty = false
        // Padding can rewrap long names. Recalculate for navigation/data/viewport changes,
        // never for the animation's own layouts, which could otherwise oscillate at the bottom edge.
        var depth = Int.MAX_VALUE
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            // Attached prefetch rows do not count; even one visible pixel retains its ancestry.
            if (row.bottom <= list.paddingTop || row.top >= list.height - list.paddingBottom) continue
            val node = adapter.currentList.getOrNull(list.getChildAdapterPosition(row)) ?: continue
            val ownerDepth = if (node is PaneViewModel.InfoNode || node is SqliteDataNode || node is SqliteInfoNode) node.depth - 1 else node.depth
            depth = minOf(depth, ownerDepth.coerceAtLeast(0))
        }
        val next = if (isEnabled && depth != Int.MAX_VALUE) depth else 0
        if (next != target) {
            target = next
            animator?.cancel()
            animator = ValueAnimator.ofFloat(adapter.indentOffset, next.toFloat()).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    adapter.indentOffset = it.animatedValue as Float
                    applyVisibleRows()
                }
                start()
            }
        }
        // New/rebound holders share the ongoing transition instead of starting their own animation.
        applyVisibleRows()
        return true
    }

    private fun applyVisibleRows() {
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i))
            val node = adapter.currentList.getOrNull(holder.bindingAdapterPosition) ?: continue
            adapter.applyIndentation(holder, node)
        }
    }

    override fun onViewAttachedToWindow(v: View) {
        dirty = true
        list.viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onViewDetachedFromWindow(v: View) {
        animator?.cancel()
        animator = null
        target = -1 // Reattachment resumes from the last displayed offset.
        if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnPreDrawListener(this)
    }

    fun dispose() {
        onViewDetachedFromWindow(list)
        list.removeOnAttachStateChangeListener(this)
        list.removeOnScrollListener(scrollListener)
        list.removeOnLayoutChangeListener(layoutListener)
        adapter.unregisterAdapterDataObserver(dataObserver)
    }
}
