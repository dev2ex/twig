package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.twig.app.R

/** Scan-size fill matching selection, with a stronger overlay on selected rows. */
class UsageBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    private val paint = Paint()
    private val selectionColor = ContextCompat.getColor(context, R.color.selected)
    private val overlapColor = ContextCompat.getColor(context, R.color.action_text)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The overlay follows the row's final bounds. View's default AT_MOST
        // measurement would consume the entire viewport and enlarge a wrap-content row.
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val selected = android.R.attr.state_activated in drawableState
        paint.color = if (selected) overlapColor else selectionColor
        paint.alpha = if (selected) 76 else 255
        val extent = width * fraction
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.drawRect(width - extent, 0f, width.toFloat(), height.toFloat(), paint)
        else canvas.drawRect(0f, 0f, extent, height.toFloat(), paint)
    }
}
