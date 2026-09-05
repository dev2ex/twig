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
 * Regression: the "recursive stats in progress" spinner on the Properties card **must
 * land within the card's visible bounds**.
 *
 * The first version, on 2026-08-04, placed it on the tab-bar row (tab bar is
 * `wrap_content` plus a `marginStart` that grows with tree depth). In dual-pane mode the
 * pane is only half-screen wide, and once the nesting gets deep enough the tab bar fills
 * the entire row, pushing the fixed-width ProgressBar after it past the right edge, where
 * `clipChildren` cuts it off — the user sees "there is simply no spinner", while the code
 * has `visibility` set to VISIBLE the whole time, which binding logic alone cannot catch.
 * So this test checks not the visibility flag but **the measured position**.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InfoCardSpinnerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** A narrow pane (half screen) + very deep nesting = the case most likely to push things off the right edge. */
    private fun bindDeepCard(paneDp: Int, depth: Int): View {
        val dpi = app.resources.displayMetrics.density
        val parent = FrameLayout(app)
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        val node = PaneViewModel.InfoNode(
            file = XFile("file", "/storage/emulated/0/one/two/three/four/five/six/seven", isDir = true),
            depth = depth,
            details = FileInfo.Details(
                listOf(
                    FileInfo.Section(
                        app.getString(R.string.info_section_basic),
                        listOf(
                            app.getString(R.string.info_name) to "seven",
                            app.getString(R.string.info_total_size) to "12.3 GB (13,234,567,890 bytes)",
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

    /** The spinner's right edge relative to the card's root (accumulating each level's left). */
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
    fun `while scanning, the spinner is visible and entirely within the card's width`() {
        val root = bindDeepCard(paneDp = 180, depth = 8)
        val spin = root.findViewById<View>(R.id.scan_spin)

        assertEquals(View.VISIBLE, spin.visibility)
        assertTrue("the spinner must have an actual size", spin.width > 0 && spin.height > 0)
        assertTrue(
            "the spinner's right edge ${rightInRoot(spin, root)} exceeds the card's width ${root.width} and would be clipped out of view",
            rightInRoot(spin, root) <= root.width,
        )
    }

    @Test
    fun `the spinner is hidden when not scanning`() {
        val parent = FrameLayout(app)
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(
            listOf(
                PaneViewModel.InfoNode(
                    file = XFile("file", "/x", isDir = true),
                    depth = 1,
                    details = FileInfo.Details(listOf(FileInfo.Section("Basic", listOf("Name" to "x")))),
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
