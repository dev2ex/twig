package com.twig.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Keep trailing metadata readable; drop whole leading groups when the row is narrow. */
class MetadataRow(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private var baseline = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Older TextViews enable horizontal scrolling when applying singleLine, overriding XML.
        // Keep its single-line START ellipsis, but constrain the text layout to the visible width.
        (getChildAt(childCount - 1) as? TextView)?.setHorizontallyScrolling(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var desiredWidth = paddingLeft + paddingRight
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as LayoutParams
            child.measure(unspecified, getChildMeasureSpec(heightMeasureSpec,
                paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height))
            desiredWidth += child.measuredWidth + lp.leftMargin + lp.rightMargin
        }
        val width = resolveSize(desiredWidth.coerceAtLeast(suggestedMinimumWidth), widthMeasureSpec)
        var remaining = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var clipped = false
        var trailing: View? = null
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as LayoutParams
            val margins = lp.leftMargin + lp.rightMargin
            val naturalWidth = child.measuredWidth
            val allocated = when {
                trailing == null -> (remaining - margins).coerceAtLeast(0).coerceAtMost(naturalWidth)
                clipped || naturalWidth + margins > remaining -> 0
                else -> naturalWidth
            }
            if (trailing == null) trailing = child
            else if (allocated == 0) clipped = true
            if (allocated > 0) remaining -= allocated + margins
            child.measure(MeasureSpec.makeMeasureSpec(allocated, MeasureSpec.EXACTLY),
                getChildMeasureSpec(heightMeasureSpec,
                    paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height))
        }
        // Unused space belongs between the leading groups and the end-aligned date/size.
        trailing?.takeIf { it.measuredWidth > 0 && remaining > 0 }?.let { child ->
            child.measure(MeasureSpec.makeMeasureSpec(child.measuredWidth + remaining, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY))
        }
        baseline = 0
        var height = 0
        var descent = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE || child.measuredWidth == 0) continue
            val lp = child.layoutParams as LayoutParams
            height = maxOf(height, child.measuredHeight + lp.topMargin + lp.bottomMargin)
            if (child.baseline >= 0) {
                baseline = maxOf(baseline, child.baseline + lp.topMargin)
                descent = maxOf(descent, child.measuredHeight - child.baseline + lp.bottomMargin)
            }
        }
        setMeasuredDimension(width, resolveSize(
            (maxOf(height, baseline + descent) + paddingTop + paddingBottom).coerceAtLeast(suggestedMinimumHeight),
            heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = if (rtl) width - paddingRight else paddingLeft
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE || child.measuredWidth == 0) {
                child.layout(0, 0, 0, 0)
                continue
            }
            val lp = child.layoutParams as LayoutParams
            val y = paddingTop + if (child.baseline >= 0) baseline - child.baseline else lp.topMargin
            if (rtl) {
                x -= lp.rightMargin
                child.layout(x - child.measuredWidth, y, x, y + child.measuredHeight)
                x -= child.measuredWidth + lp.leftMargin
            } else {
                x += lp.leftMargin
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                x += child.measuredWidth + lp.rightMargin
            }
        }
    }
}
