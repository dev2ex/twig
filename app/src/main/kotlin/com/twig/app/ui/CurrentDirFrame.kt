package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R

/**
 * 当前选中节点高亮框:框住"最近选中的节点行(目录/服务器/分组/收藏等任意类型)
 * + 它展开的**直接一层子级**"。只到直接子级为止(底边落在最后一个直接子级),
 * 不再递归包住更深的孙级——否则展开一个根/分组(下面挂着深展开的服务器/目录)时,
 * 框会从头拉到整棵树的底,过高。焦点点进具体子级时,框会随 currentKey 转移到那一层。
 * 框的上/下边超出可见区时,对应边推到屏幕外,只画侧边。
 */
class CurrentDirFrame(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * context.resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val rect = RectF()

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? FileAdapter ?: return
        if (parent.childCount == 0) return
        val key = adapter.currentKey ?: return
        val list = adapter.currentList
        val start = list.indexOfFirst { it.key == key }
        if (start < 0) return
        // 底边只到"最后一个直接子级(depth+1)",跳过其展开的更深孙级
        val depth = list[start].depth
        var end = start
        var i = start + 1
        while (i < list.size && list[i].depth > depth) {
            if (list[i].depth == depth + 1) end = i
            i++
        }

        val firstPos = parent.getChildAdapterPosition(parent.getChildAt(0))
        val lastPos = parent.getChildAdapterPosition(parent.getChildAt(parent.childCount - 1))
        if (firstPos == RecyclerView.NO_POSITION || lastPos == RecyclerView.NO_POSITION) return
        if (end < firstPos || start > lastPos) return // 子树完全在可见区外

        val off = paint.strokeWidth // 越界时把边推到屏幕外,只留侧边
        // 用最终布局位置(top/bottom),不含动画过渡偏移,避免框随动画拉伸
        val top = if (start >= firstPos) {
            parent.findViewHolderForAdapterPosition(start)?.itemView?.top?.toFloat() ?: -off
        } else -off
        val bottom = if (end <= lastPos) {
            parent.findViewHolderForAdapterPosition(end)?.itemView?.bottom?.toFloat()
                ?: (parent.height + off)
        } else parent.height + off

        val half = paint.strokeWidth / 2
        rect.set(half, top + half, parent.width - half, bottom - half)
        c.drawRect(rect, paint)
    }
}
