package com.twig.app.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.twig.app.FileInfo
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ★ 回归:属性卡片上那个"正在递归统计"的转圈**必须落在卡片可视范围内**。
 *
 * 2026-08-04 的第一版把它放在 tab 条那一行(tab 条 `wrap_content` + 随树深度增长的
 * `marginStart`)。双面板下面板只有半屏宽,层级一深,tab 条就把整行吃满,后面那个
 * 固定宽的 ProgressBar 被排到右边界外、`clipChildren` 一裁就没了——用户看到的是
 * "根本没有转圈",而代码里 `visibility` 明明是 VISIBLE,单看绑定逻辑查不出来。
 * 所以这条测的不是可见性标志,而是**量出来的位置**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InfoCardSpinnerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** 窄面板(半屏)+ 很深的层级 = 最容易把右边的东西挤出去的那种情况。 */
    private fun bindDeepCard(paneDp: Int, depth: Int): View {
        val dpi = app.resources.displayMetrics.density
        val parent = FrameLayout(app)
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        val node = PaneViewModel.InfoNode(
            file = XFile("file", "/storage/emulated/0/一/二/三/四/五/六/七", isDir = true),
            depth = depth,
            details = FileInfo.Details(
                listOf(
                    FileInfo.Section(
                        app.getString(R.string.info_section_basic),
                        listOf(
                            app.getString(R.string.info_name) to "七",
                            app.getString(R.string.info_total_size) to "12.3 GB(13,234,567,890 字节)",
                        ),
                    ),
                ),
            ),
            dirStat = DirStat(files = 1234, dirs = 56, bytes = 13_234_567_890L),
            scanning = true,
        )
        adapter.submitList(listOf(node))
        val vh = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        adapter.bindViewHolder(vh, 0)
        val root = vh.itemView
        val w = (paneDp * dpi).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    /** 转圈相对卡片根的右边缘(层层加上各级 left)。 */
    private fun rightInRoot(v: View, root: View): Int {
        var x = 0
        var cur: View = v
        while (cur !== root) {
            x += cur.left
            cur = cur.parent as View
        }
        return x + v.width
    }

    @Test
    fun `扫描中转圈可见且整个落在卡片宽度内`() {
        val root = bindDeepCard(paneDp = 180, depth = 8)
        val spin = root.findViewById<View>(R.id.scan_spin)

        assertEquals(View.VISIBLE, spin.visibility)
        assertTrue("转圈得有实际尺寸", spin.width > 0 && spin.height > 0)
        assertTrue(
            "转圈右边缘 ${rightInRoot(spin, root)} 超出了卡片宽度 ${root.width},会被裁掉看不见",
            rightInRoot(spin, root) <= root.width,
        )
    }

    @Test
    fun `没在扫描时不显示转圈`() {
        val parent = FrameLayout(app)
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(
            listOf(
                PaneViewModel.InfoNode(
                    file = XFile("file", "/x", isDir = true),
                    depth = 1,
                    details = FileInfo.Details(listOf(FileInfo.Section("基本", listOf("名称" to "x")))),
                    dirStat = DirStat(1, 1, 1),
                    scanning = false,
                ),
            ),
        )
        val vh: androidx.recyclerview.widget.RecyclerView.ViewHolder =
            adapter.createViewHolder(parent as ViewGroup, adapter.getItemViewType(0))
        adapter.bindViewHolder(vh, 0)

        assertEquals(View.GONE, vh.itemView.findViewById<View>(R.id.scan_spin).visibility)
    }
}
