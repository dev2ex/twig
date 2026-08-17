package com.twig.app.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 长按菜单的行渲染。
 *
 * 值得单测的点只有一个,但它很致命:行布局用了 `?attr/dialogPreferredPadding`,
 * 主题里没有这个属性 **inflate 当场抛异常**——而这一刻用户已经长按下去了,
 * 表现是"长按什么都不出来 / 直接闪退"。顺带钉住图标真的被换成了这一项自己的图标
 * (`ArrayAdapter` 复用 convertView,忘了每次都 setImageResource 的话会串图)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionMenuTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val ctx = ContextThemeWrapper(app, R.style.Theme_Twig)

    @Test
    fun rendersLabelAndIcon() {
        val actions = listOf(
            MenuAct("复制", R.drawable.ic_copy) {},
            MenuAct("删除", R.drawable.ic_delete, DANGER) {},
        )
        val adapter = menuAdapter(ctx, actions)
        val parent = FrameLayout(ctx)

        val first = adapter.getView(0, null, parent)
        assertEquals("复制", first.findViewById<TextView>(R.id.label).text.toString())
        assertNotNull(first.findViewById<ImageView>(R.id.icon).drawable)

        // 复用第一行的视图渲染第二行:文案与图标都得换过来
        val second = adapter.getView(1, first, parent)
        assertEquals("删除", second.findViewById<TextView>(R.id.label).text.toString())
        assertEquals(
            app.getColor(R.color.menu_icon_danger),
            second.findViewById<ImageView>(R.id.icon).imageTintList?.defaultColor,
        )
    }
}
