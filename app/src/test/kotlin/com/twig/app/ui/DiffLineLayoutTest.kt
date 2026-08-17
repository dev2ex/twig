package com.twig.app.ui

import android.app.Application
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.twig.app.databinding.ItemDiffLineBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * diff 行的布局契约。这几条决定了"超长行能不能看全":
 * 文本不能被省略、要按无限宽排版(否则 scrollTo 只能滚出空白)、
 * 文本区的宽度必须是**扣掉行号列之后**的宽度(算错就滚不到行尾)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiffLineLayoutTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()

    private fun inflate() = ItemDiffLineBinding.inflate(LayoutInflater.from(ctx))

    @Test
    fun `只读单行 TextView 默认会末尾省略,必须显式关掉`() {
        val b = inflate()
        // 记录这个坑本身:XML 里根本没写 ellipsize,值却是 END——TextView 构造函数里有
        // `if (singleLine && keyListener == null && ellipsize 未设) ellipsize = END`。
        // 所以 `android:ellipsize="none"`(等于"没设")也关不掉,只能在代码里关。
        assertEquals(TextUtils.TruncateAt.END, b.tvText.ellipsize)

        configureDiffLineText(b.tvText)
        assertNull("省略之后,后面的内容绘制不出来,横滚也救不回来", b.tvText.ellipsize)
    }

    @Test
    fun `行文本可长按选中复制`() {
        val b = inflate()
        configureDiffLineText(b.tvText)
        assertTrue(b.tvText.isTextSelectable)
    }

    @Test
    fun `行文本按无限宽排版,超出部分才滚得到`() {
        val b = inflate()
        configureDiffLineText(b.tvText)
        b.tvText.text = "x".repeat(4000)
        val w = 1080
        b.root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        b.root.layout(0, 0, w, b.root.measuredHeight)
        val layoutWidth = b.tvText.layout?.getLineWidth(0) ?: 0f
        assertTrue(
            "整行的排版宽度($layoutWidth)必须超过视图宽度(${b.tvText.width}),否则没得滚",
            layoutWidth > b.tvText.width,
        )
    }

    @Test
    fun `文本区宽度是扣掉行号列之后的宽度`() {
        val b = inflate()
        val w = 1080
        b.root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        b.root.layout(0, 0, w, b.root.measuredHeight)
        // ★ 水平 LinearLayout 里 match_parent 的子 view 拿到的是**整个父宽**
        // (getChildMeasureSpec 不扣兄弟占掉的部分),那样可见宽被高估、横滚到不了行尾
        assertEquals("行号列占掉的宽度必须从文本区里扣掉", w - b.tvNo.width, b.tvText.width)
    }

    @Test
    fun `git 虚拟路径剥成仓库内相对路径`() {
        // 前缀后面那一段是 GitVfs 的分组名 / 提交 sha,不属于仓库里的路径;
        // 直接拿虚拟路径当真实路径显示,会变成没有意义的 "git:/changes/…"
        assertEquals("src/Foo.kt", gitRelPath("/changes/staged/src/Foo.kt"))
        // ★ 只切掉前缀那一段,后面的层级一个都不能少——写成 substringAfterLast
        // 就只剩文件名了,而这里要的正是"相对 .git 所在目录的完整路径"
        assertEquals("a/b/c/d/Deep.kt", gitRelPath("/changes/unstaged/a/b/c/d/Deep.kt"))
        assertEquals("a/b/c/d/Deep.kt", gitRelPath("/history/a1b2c3d/a/b/c/d/Deep.kt"))
        assertEquals("src/Foo.kt", gitRelPath("/changes/unstaged/src/Foo.kt"))
        assertEquals("README.md", gitRelPath("/changes/untracked/README.md"))
        assertEquals("src/Foo.kt", gitRelPath("/history/a1b2c3d/src/Foo.kt"))
        assertEquals("剥完什么都不剩时给空串,标题就只显示侧别", "", gitRelPath("/changes/staged"))
    }

    @Test
    fun `两侧各是哪个版本要跟 diffSides 对得上`() {
        // GitVfs.diffSides:staged = HEAD↔INDEX、unstaged = INDEX↔WORK、
        // untracked = 无↔WORK、history = <sha>^↔<sha>。标题不写出来的话,
        // 同样一句"旧/新"在这几种情况下指的完全不是一回事
        assertEquals("HEAD", gitSideSources(ctx, "/changes/staged/a.kt")?.first)
        assertEquals(
            gitSideSources(ctx, "/changes/staged/a.kt")?.second,
            gitSideSources(ctx, "/changes/unstaged/a.kt")?.first,
        )
        assertEquals("", gitSideSources(ctx, "/changes/untracked/a.kt")?.first)
        assertEquals("a1b2c3d^" to "a1b2c3d", gitSideSources(ctx, "/history/a1b2c3d4e5f6/a.kt"))
        assertNull(gitSideSources(ctx, "/branches/main"))
    }
}
