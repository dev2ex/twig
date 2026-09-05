package com.twig.app.ui

import android.annotation.SuppressLint
import android.view.Menu
import androidx.appcompat.view.menu.MenuBuilder

/**
 * Make overflow menus / PopupMenu render their icons.
 *
 * AppCompat only displays icons on the ActionBar by default; anything that falls into the
 * overflow menu has its icon hidden, and the only switch is this `@RestrictTo`-annotated method
 * on `MenuBuilder` — there is no corresponding public API.
 *
 * ★ Must be paired with `androidx.appcompat.widget.PopupMenu` — the framework's
 * `android.widget.PopupMenu` internally uses a different same-named `MenuBuilder`
 * (com.android.internal.*), so the `as?` here will miss and icons still won't render (its own
 * `setForceShowIcon` requires API 29+, while minSdk is 24). When the cast fails, do nothing —
 * the menu falls back to no icons, no crash.
 */
@SuppressLint("RestrictedApi")
fun Menu.showIcons() {
    (this as? MenuBuilder)?.setOptionalIconsVisible(true)
}
