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
 * The layout contract for a diff row. These few tests decide whether "an overly long line
 * can be seen in full": the text must not be ellipsized, it must be laid out at unlimited
 * width (otherwise scrollTo only scrolls into blank space), and the text area's width must
 * be the width **after subtracting the line-number column** (get that wrong and it cannot
 * scroll to the end of the line).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiffLineLayoutTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()

    private fun inflate() = ItemDiffLineBinding.inflate(LayoutInflater.from(ctx))

    @Test
    fun `a read-only single-line TextView ellipsizes at the end by default, and it must be turned off explicitly`() {
        val b = inflate()
        // record the pitfall itself: the XML never sets ellipsize at all, yet the value comes
        // out as END — TextView's constructor has
        // `if (singleLine && keyListener == null && ellipsize not set) ellipsize = END`.
        // So `android:ellipsize="none"` (which is equivalent to "not set") cannot turn it off either; it has to be turned off in code.
        assertEquals(TextUtils.TruncateAt.END, b.tvText.ellipsize)

        configureDiffLineText(b.tvText)
        assertNull("once ellipsized, the content past that point cannot be drawn, and horizontal scrolling cannot bring it back", b.tvText.ellipsize)
    }

    @Test
    fun `line text can be long-pressed to select and copy`() {
        val b = inflate()
        configureDiffLineText(b.tvText)
        assertTrue(b.tvText.isTextSelectable)
    }

    @Test
    fun `line text is laid out at unlimited width, so only the overflowing part can be scrolled to`() {
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
            "the whole line's laid-out width ($layoutWidth) must exceed the view's width (${b.tvText.width}), otherwise there is nothing to scroll",
            layoutWidth > b.tvText.width,
        )
    }

    @Test
    fun `the text area's width is the width after subtracting the line-number column`() {
        val b = inflate()
        val w = 1080
        b.root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        b.root.layout(0, 0, w, b.root.measuredHeight)
        // a match_parent child inside a horizontal LinearLayout gets **the whole parent width**
        // (getChildMeasureSpec does not subtract what siblings take up), which would overestimate
        // the visible width and leave horizontal scrolling unable to reach the end of the line
        assertEquals("the width taken up by the line-number column must be subtracted from the text area", w - b.tvNo.width, b.tvText.width)
    }

    @Test
    fun `a git virtual path is stripped down to a path relative to the repo`() {
        // the segment right after the prefix is GitVfs's group name / commit sha, not part of
        // the repo's own path; showing the virtual path as-is would render as the meaningless
        // "git:/changes/…"
        assertEquals("src/Foo.kt", gitRelPath("/changes/staged/src/Foo.kt"))
        // only the prefix segment gets cut off, none of the levels after it may be dropped —
        // writing this as substringAfterLast would leave only the file name, while what is
        // wanted here is exactly "the full path relative to the directory containing .git"
        assertEquals("a/b/c/d/Deep.kt", gitRelPath("/changes/unstaged/a/b/c/d/Deep.kt"))
        assertEquals("a/b/c/d/Deep.kt", gitRelPath("/history/a1b2c3d/a/b/c/d/Deep.kt"))
        assertEquals("src/Foo.kt", gitRelPath("/changes/unstaged/src/Foo.kt"))
        assertEquals("README.md", gitRelPath("/changes/untracked/README.md"))
        assertEquals("src/Foo.kt", gitRelPath("/history/a1b2c3d/src/Foo.kt"))
        assertEquals("when stripping leaves nothing at all, the result is an empty string, and the title shows only the side label", "", gitRelPath("/changes/staged"))
    }

    @Test
    fun `which version each side represents must line up with diffSides`() {
        // GitVfs.diffSides: staged = HEAD<->INDEX, unstaged = INDEX<->WORK,
        // untracked = none<->WORK, history = <sha>^<->sha. If the title does not spell this
        // out, the same phrase "old/new" means something completely different across these cases
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
