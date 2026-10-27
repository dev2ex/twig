package com.twig.app.ui

import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.twig.app.R

/** Shared toolbar control for the main browser and share destination picker. */
object PaneSwitch {
    fun add(
        menu: Menu, toolbar: Toolbar, id: Int, index: Int,
        onSwitch: () -> Unit, onLongPress: (() -> Unit)? = null,
    ): MenuItem = menu.add(0, id, 1, toolbar.context.getString(R.string.menu_swap_pane)).apply {
        setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        actionView = AppCompatImageButton(toolbar.context, null, androidx.appcompat.R.attr.actionButtonStyle).apply {
            contentDescription = toolbar.context.getString(R.string.menu_swap_pane)
            minimumWidth = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { onSwitch() }
            if (onLongPress != null) setOnLongClickListener { onLongPress(); true }
        }
        update(this, toolbar, index)
    }

    fun update(item: MenuItem, toolbar: Toolbar, index: Int) {
        item.icon = ContextCompat.getDrawable(toolbar.context,
            if (index == 0) R.drawable.ic_pane_to_right else R.drawable.ic_pane_to_left)
        (item.actionView as AppCompatImageButton).setImageDrawable(item.icon)
    }
}
