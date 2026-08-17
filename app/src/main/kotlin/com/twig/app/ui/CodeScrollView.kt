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
 * 竖向滚动容器,额外把内层 [hsv] 的横向滚动位置画成**钉在视口底部**的指示条——
 * 原生横向滚动条画在 HorizontalScrollView 自己的底边,而它和全文一样高,
 * 不滚到文末根本看不见。内容不超宽(自动换行开着)时不画。
 *
 * 双指缩放([onScale]/[onScaleEnd])用来调字号:双指落下就走 [onInterceptTouchEvent]
 * 整体接管(不下发给内层 EditText),避免缩放期间同时触发文本选中或横向滚动。
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
        val bottom = scrollY + height - 2 * dp // 画布随滚动平移,加 scrollY 才钉在视口底
        canvas.drawRoundRect(x, bottom - 4 * dp, x + thumbW, bottom, 2 * dp, 2 * dp, paint)
    }
}
