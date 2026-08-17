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
 * 带图标的长按菜单(文件/目录/服务器/收藏那几套共用)。
 *
 * `AlertDialog.setItems` 只吃字符串数组,给不了图标——换成 `setAdapter` 铺
 * [R.layout.item_menu_action]。图标一律**代码染色**:项目里的矢量图自带
 * `android:tint="@color/white"`(为工具栏准备的),放进对话框会白到看不见;
 * 默认染成 [R.color.menu_icon](跟着深浅色主题走),删除/卸载这类不可逆动作
 * 用 [DANGER] 单独染红——菜单里最该一眼认出来的就是这几项。
 */
class MenuAct(
    val label: String,
    @DrawableRes val icon: Int,
    @ColorRes val tint: Int = R.color.menu_icon,
    val run: () -> Unit,
) {
    /** ArrayAdapter 靠 toString() 填文案。 */
    override fun toString(): String = label
}

/** 危险动作的图标色(删除、卸载、移出服务器…)。 */
@ColorRes
val DANGER: Int = R.color.menu_icon_danger

/** 往菜单里加一项;写法比 `add(MenuAct(...))` 贴近原来的 `actions += 文案 to {}`。 */
fun MutableList<MenuAct>.item(
    label: String,
    @DrawableRes icon: Int,
    @ColorRes tint: Int = R.color.menu_icon,
    run: () -> Unit,
) {
    add(MenuAct(label, icon, tint, run))
}

/** 弹出菜单;选中即执行对应动作。 */
fun showActionMenu(ctx: Context, title: CharSequence, actions: List<MenuAct>) {
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setAdapter(menuAdapter(ctx, actions)) { _, w -> actions[w].run() }
        .show()
}

/** 单独一层是为了能在单测里直接拿视图来看(布局用了 `?attr/dialogPreferredPadding`,
 *  主题里少了这个属性就是 inflate 当场崩,而那时菜单已经在用户手上了)。 */
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
