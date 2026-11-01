package com.twig.app.ui

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar

/** Bind the real toolbar controls after AppCompat has laid out its menu. */
object ToolbarLongPress {
    fun bind(
        toolbar: Toolbar, actions: Map<Int, () -> Unit> = emptyMap(),
        overflow: (() -> Unit)? = null, navigation: (() -> Unit)? = null,
        title: (() -> Unit)? = null,
    ) {
        toolbar.post {
            fun View.longPress(action: () -> Unit) {
                setOnLongClickListener { action(); true }
            }
            actions.forEach { (id, action) -> toolbar.findViewById<View>(id)?.longPress(action) }
            for (i in 0 until toolbar.childCount) {
                when (val child = toolbar.getChildAt(i)) {
                    is ActionMenuView -> if (overflow != null) {
                        for (j in 0 until child.childCount) {
                            val button = child.getChildAt(j)
                            if ((button.layoutParams as? ActionMenuView.LayoutParams)?.isOverflowButton == true)
                                button.longPress(overflow)
                        }
                    }
                    is ImageButton -> if (navigation != null && child.drawable === toolbar.navigationIcon)
                        child.longPress(navigation)
                    is TextView -> if (title != null && child.text == toolbar.title) child.longPress(title)
                }
            }
        }
    }
}
