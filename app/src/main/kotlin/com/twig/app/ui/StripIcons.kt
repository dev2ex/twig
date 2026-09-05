package com.twig.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.twig.app.Prefs
import com.twig.app.databinding.ActionStripBinding

/**
 * Make the side strip's icons the same size as the file list row icons ([FileAdapter.rowIconDp]) —
 * the 20dp in the layout's `StripIcon` is just the default; the actual size scales with the row
 * density level.
 */
fun ActionStripBinding.sizeIconsLikeRows(ctx: Context) = sizeStripIcons(root, ctx)

/** Same as above, for action strips that don't use `ActionStripBinding` (the compare page has its own layout). */
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
