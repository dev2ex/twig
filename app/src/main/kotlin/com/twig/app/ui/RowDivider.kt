package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R

/**
 * 树式列表的行间分割线:只画在两条**普通整行**之间(网格格子之间、行与属性卡片之间不画)。
 * 刻意做得淡——1px + 半透明 divider 色,只给相邻两行一点边界感,不抢视觉。
 * 不占布局空间(不实现 getItemOffsets),行高不变;画在 onDrawOver 里,免得被下一行
 * 的选中态背景盖掉。
 */
class RowDivider(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = paint(context)
    private val thickness = thickness(context)
    private val inset = 6f * context.resources.displayMetrics.density

    companion object {
        /** 分割线画笔;操作栏(StripGrid)共用同一套配色,两处看起来是一样的线。 */
        fun paint(context: Context): Paint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.divider)
            alpha = 190
        }

        /** 厚度取半 dp(高密度屏 1.5px):整 dp 在手机上偏粗,1px 又几乎看不见。 */
        fun thickness(context: Context): Float =
            (0.5f * context.resources.displayMetrics.density).coerceAtLeast(1f)
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? FileAdapter ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            if (!adapter.isPlainRowAt(pos) || !adapter.isPlainRowAt(pos + 1)) continue
            val y = child.bottom.toFloat()
            c.drawRect(inset, y, parent.width - inset, y + thickness, paint)
        }
    }
}
