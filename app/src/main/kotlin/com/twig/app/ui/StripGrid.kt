package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.widget.GridLayout

/**
 * 操作栏用的 GridLayout:按钮之间画分割线,样式与列表的 [RowDivider] 完全一致
 * (同一支画笔、同一厚度),由同一个开关 `Prefs.rowDivider` 控制。
 *
 * GridLayout 没有 LinearLayout 的 showDividers,这里自己在 dispatchDraw 之后画:
 * **只画行与行之间的横线,一行一根、横跨整个宽度**(横屏双列时不是左右各一段——按钮
 * 按 bottom 分组,同一行的两个按钮共用一根线),列之间不画竖线。行的判定不看行列索引
 * (列数随横竖屏在 1/2 之间变),只按子 View 的实际 bottom 分组,最后一行自然不画。
 */
class StripGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : GridLayout(context, attrs, defStyle) {

    private val paint = RowDivider.paint(context)
    private val thickness = RowDivider.thickness(context)
    private val inset = 6f * context.resources.displayMetrics.density

    /** 是否画分割线;跟列表分割线同一个偏好,由 MainActivity 回填。 */
    var dividers = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
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
            if (y >= maxBottom) continue // 最后一行下面不画
            canvas.drawRect(left, y.toFloat(), right, y + thickness, paint)
        }
    }
}
