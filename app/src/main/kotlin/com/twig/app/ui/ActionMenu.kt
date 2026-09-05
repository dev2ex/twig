package com.twig.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.twig.app.R

/**
 * The long-press menu with icons (shared by the file / directory / server /
 * favourites sets).
 *
 * `AlertDialog.setItems` only takes a String array — no room for icons —
 * so we switch to `setAdapter` backed by [R.layout.item_menu_action].
 * Icons are **tinted in code**: the project's vector drawables come with
 * `android:tint="@color/white"` (set up for the toolbar), so when dropped
 * into a dialog they end up pure white and invisible; the default tint is
 * [R.color.menu_icon] (which follows the light/dark theme), and irreversible
 * actions like delete / uninstall are tinted with [DANGER] — those are the
 * entries the menu user should recognise at a glance.
 */
class MenuAct(
    val label: String,
    @DrawableRes val icon: Int,
    @ColorRes val tint: Int = R.color.menu_icon,
    val run: () -> Unit,
) {
    /** ArrayAdapter fills the label via toString(). */
    override fun toString(): String = label
}

/** Icon colour for dangerous actions (delete, uninstall, remove from server, ...). */
@ColorRes
val DANGER: Int = R.color.menu_icon_danger

/** Add one item to the menu; the syntax stays close to the old `actions += "label" to {}`. */
fun MutableList<MenuAct>.item(
    label: String,
    @DrawableRes icon: Int,
    @ColorRes tint: Int = R.color.menu_icon,
    run: () -> Unit,
) {
    add(MenuAct(label, icon, tint, run))
}

/** Pop up the menu; selecting an item runs its action immediately. */
fun showActionMenu(ctx: Context, title: CharSequence, actions: List<MenuAct>) {
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setAdapter(menuAdapter(ctx, actions)) { _, w -> actions[w].run() }
        .show()
}

/** A dedicated layer so unit tests can grab the view directly (the layout uses `?attr/dialogPreferredPadding`, and missing that attribute makes inflate crash on the spot — by which time the menu is already in the user's hand). */
internal fun menuAdapter(ctx: Context, actions: List<MenuAct>): ArrayAdapter<MenuAct> =
    object : ArrayAdapter<MenuAct>(ctx, R.layout.item_menu_action, R.id.label, actions) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)
            val a = getItem(position) ?: return v
            v.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(a.icon)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, a.tint))
            }
            return v
        }
    }
