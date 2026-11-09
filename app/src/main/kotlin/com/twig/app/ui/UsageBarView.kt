package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.twig.app.R

/** Green scan-size overlay, strengthened where it overlaps the selection background. */
class UsageBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.action_text)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        paint.alpha = if (android.R.attr.state_activated in drawableState) 76 else 38
        val extent = width * fraction
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.drawRect(width - extent, 0f, width.toFloat(), height.toFloat(), paint)
        else canvas.drawRect(0f, 0f, extent, height.toFloat(), paint)
    }
}
