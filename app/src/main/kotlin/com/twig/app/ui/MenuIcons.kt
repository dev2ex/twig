package com.twig.app.ui

import android.annotation.SuppressLint
import android.view.Menu
import androidx.appcompat.view.menu.MenuBuilder

/**
 * 让溢出菜单 / PopupMenu 把图标画出来。
 *
 * AppCompat 默认只在 ActionBar 上显示图标,落进溢出菜单就一律藏掉,而开关只有
 * `MenuBuilder` 上这个带 `@RestrictTo` 的方法,没有对应的公开 API。
 *
 * ★ 必须配 `androidx.appcompat.widget.PopupMenu`——framework 的
 * `android.widget.PopupMenu` 内部是另一个同名 `MenuBuilder`(com.android.internal.*),
 * 这里的 as? 会落空、图标依旧不显示(它自己的 `setForceShowIcon` 要 API 29+,
 * 而 minSdk 是 24)。转型失败时什么都不做,菜单退回没有图标的样子,不会崩。
 */
@SuppressLint("RestrictedApi")
fun Menu.showIcons() {
    (this as? MenuBuilder)?.setOptionalIconsVisible(true)
}
