package com.twig.app.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.twig.app.R

/** Reuse the toolbar and its actions while moving it above or below browser content. */
object ToolbarPlacement {
    fun apply(activity: Activity, toolbar: Toolbar, bottom: Boolean) {
        val parent = toolbar.parent as ViewGroup
        val target = if (bottom) parent.childCount - 1 else 0
        if (parent.indexOfChild(toolbar) != target) {
            val params = toolbar.layoutParams
            parent.removeView(toolbar)
            parent.addView(toolbar, target, params)
        }
        val color = if (bottom && toolbar.visibility == View.VISIBLE) R.color.toolbar else R.color.surface
        NavBarTint.apply(activity, ContextCompat.getColor(activity, color))
    }
}
