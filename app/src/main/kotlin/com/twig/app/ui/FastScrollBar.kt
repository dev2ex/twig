package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 一根可拖动的快速滚动条,供行数极多的列表(十六进制查看器)用。
 *
 * 为什么不用现成的:RecyclerView 原生滚动条**根本拖不动**(只是个指示器),而它的
 * `computeVerticalScrollRange()` 又是 int——一个 1GB 的文件按 16 字节/行就是 6700 万行,
 * 乘上行高直接溢出,滑块位置随即失真。这里的滑块高度**固定** [THUMB_DP],位置只由
 * 调用方给的 0..1 比例决定,与总行数多大完全无关,因此多大的文件都拖得动。
 *
 * 触摸只在**落点命中滑块**时才接管([onTouchEvent] 对其余落点返回 false),事件于是继续
 * 派发给下层的 RecyclerView——右边这条 24dp 窄带不会把正常的列表滑动吃掉。
 */
class FastScrollBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 拖动回调:比例 0..1,[ended] 为 true 表示手指已抬起。 */
    var onDrag: ((Float, Boolean) -> Unit)? = null

    var dragging = false
        private set

    /** 滑块位置比例;拖动期间由自己维护,其余时候由列表滚动同步进来。 */
    var fraction = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v == field) return
            field = v
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val thumbH = THUMB_DP * dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        if (!isEnabled) return
        val cx = width / 2f
        val trackW = 3 * dp
        paint.color = TRACK_COLOR
        canvas.drawRoundRect(
            cx - trackW / 2, 0f, cx + trackW / 2, height.toFloat(), trackW / 2, trackW / 2, paint,
        )
        val top = thumbTop()
        val w = (if (dragging) 9 else 6) * dp
        paint.color = if (dragging) THUMB_ACTIVE else THUMB_COLOR
        canvas.drawRoundRect(cx - w / 2, top, cx + w / 2, top + thumbH, w / 2, w / 2, paint)
    }

    private fun thumbTop(): Float = (height - thumbH).coerceAtLeast(0f) * fraction

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 没按在滑块上就别接管——让触摸落到下面的列表上正常滑动
                val top = thumbTop()
                if (event.y < top - SLOP_DP * dp || event.y > top + thumbH + SLOP_DP * dp) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) update(event.y, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                update(event.y, true)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
            else -> return dragging
        }
        return true
    }

    private fun update(y: Float, ended: Boolean) {
        val range = height - thumbH
        fraction = if (range <= 0f) 0f else (y - thumbH / 2) / range
        onDrag?.invoke(fraction, ended)
    }

    private companion object {
        const val THUMB_DP = 44f
        const val SLOP_DP = 6f // 滑块上下各留一点富余,手指粗一点也按得中
        const val TRACK_COLOR = 0x22808080
        const val THUMB_COLOR = 0xB0808080.toInt()
        const val THUMB_ACTIVE = 0xFF66BB6A.toInt() // 与 @color/accent 一致
    }
}
