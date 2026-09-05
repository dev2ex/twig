package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * Vertical scroll container that additionally draws the inner [hsv]'s horizontal scroll
 * position as an indicator bar **pinned to the bottom of the viewport** — the native
 * horizontal scrollbar is drawn on HorizontalScrollView's own bottom edge, and that
 * edge is the same height as the full content, so it is not visible unless you scroll
 * to the very end. When the content is not wider than the viewport (word-wrap is on),
 * nothing is drawn.
 *
 * Two-finger pinch ([onScale] / [onScaleEnd]) adjusts the font size: as soon as the second
 * finger lands, [onInterceptTouchEvent] takes over the entire gesture (without forwarding
 * to the inner EditText) so that text selection or horizontal scrolling is not triggered
 * at the same time as the pinch.
 */
class CodeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    var hsv: HorizontalScrollView? = null
    var onScale: ((Float) -> Unit)? = null
    var onScaleEnd: (() -> Unit)? = null
    private val paint = Paint().apply { color = 0x80909090.toInt() }
    private val dp = resources.displayMetrics.density
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onScale?.invoke(detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onScaleEnd?.invoke()
            }
        },
    )

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) return true
        return super.onTouchEvent(ev)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val h = hsv ?: return
        val range = h.getChildAt(0)?.width ?: return
        val extent = h.width
        if (extent <= 0 || range <= extent) return
        val trackW = width.toFloat()
        val thumbW = maxOf(trackW * extent / range, 24 * dp)
        val x = (trackW - thumbW) * h.scrollX / (range - extent)
        val bottom = scrollY + height - 2 * dp // the canvas is translated by the scroll; adding scrollY pins it to the viewport bottom
        canvas.drawRoundRect(x, bottom - 4 * dp, x + thumbW, bottom, 2 * dp, 2 * dp, paint)
    }
}
