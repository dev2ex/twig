package com.twig.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.twig.app.Prefs
import com.twig.app.R

/** Single-pane mode is one continuous track: left pane, shared action strip, right pane.
 * Scrolling by one pane width moves the strip from right to left without duplicating it.
 * Dual-pane mode lays out both panes around the middle strip, in either orientation. */
class PaneLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ViewGroup(context, attrs) {
    private var activePane = 0
    private var transition: ValueAnimator? = null
    private var previousWidth = 0
    private val dualPane get() = Prefs.dualPane(context)

    fun showPane(index: Int, animate: Boolean) {
        activePane = index.coerceIn(0, 1)
        transition?.cancel()
        transition = null
        val target = if (dualPane) 0 else activePane * findViewById<View>(R.id.pane_a).measuredWidth
        if (animate && width > 0 && scrollX != target) {
            transition = ValueAnimator.ofInt(scrollX, target).apply {
                duration = 160L
                interpolator = DecelerateInterpolator()
                addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                start()
            }
        } else {
            scrollTo(target, 0)
        }
    }

    fun finishTransition() {
        transition?.cancel()
        transition = null
        scrollTo(if (dualPane) 0 else activePane * findViewById<View>(R.id.pane_a).measuredWidth, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val strip = findViewById<View>(if (dualPane) R.id.strip_mid else R.id.strip_right)
        val stripWidth = strip.layoutParams.width.coerceAtLeast(0).coerceAtMost(w)
        val paneWidth = if (dualPane) (w - stripWidth) / 2 else w - stripWidth
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val childWidth = when {
                child.visibility == GONE -> 0
                child.id == R.id.pane_b && dualPane -> w - stripWidth - paneWidth
                child.id == R.id.pane_a || child.id == R.id.pane_b -> paneWidth
                else -> stripWidth
            }
            child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val a = findViewById<View>(R.id.pane_a)
        val other = findViewById<View>(R.id.pane_b)
        val left = findViewById<View>(R.id.strip_left)
        val middle = findViewById<View>(R.id.strip_mid)
        val right = findViewById<View>(R.id.strip_right)
        fun place(view: View, x: Int) = view.layout(x, 0, x + view.measuredWidth, height)
        place(a, 0)
        if (dualPane) {
            place(middle, a.measuredWidth)
            place(other, a.measuredWidth + middle.measuredWidth)
            place(left, 0)
            place(right, 0)
        } else {
            place(right, a.measuredWidth)
            place(left, 0)
            place(other, a.measuredWidth + right.measuredWidth)
            place(middle, 0)
        }
        if (previousWidth != width || dualPane || transition == null) finishTransition()
        previousWidth = width
    }

    override fun onDetachedFromWindow() {
        finishTransition()
        super.onDetachedFromWindow()
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    override fun generateLayoutParams(attrs: AttributeSet) = LayoutParams(context, attrs)
}
