package com.twig.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.twig.app.Prefs
import com.twig.app.databinding.ActionStripBinding

/**
 * 把侧边操作列的图标撑成和文件列表行图标([FileAdapter.rowIconDp])一样大——
 * 布局里 `StripIcon` 的 20dp 只是默认值,实际尺寸随行高档位走。
 */
fun ActionStripBinding.sizeIconsLikeRows(ctx: Context) = sizeStripIcons(root, ctx)

/** 同上,给不是 `ActionStripBinding` 的操作列用(对比页有自己那份布局)。 */
fun sizeStripIcons(root: View, ctx: Context) {
    val px = (FileAdapter.rowIconDp(Prefs.density(ctx)) * ctx.resources.displayMetrics.density).toInt()
    resizeIcons(root, px)
}

private fun resizeIcons(v: View, px: Int) {
    when (v) {
        is ImageView -> v.layoutParams = v.layoutParams.apply { width = px; height = px }
        is ViewGroup -> for (i in 0 until v.childCount) resizeIcons(v.getChildAt(i), px)
    }
}
