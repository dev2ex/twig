package com.twig.app.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TreeIndentationTest {
    private fun node(index: Int, depth: Int) = PaneViewModel.FileNode(
        XFile("indent-test", "/$index.txt", false), depth, false, false,
    )

    private fun fixture(nodes: List<PaneViewModel.Node>, block: (RecyclerView, FileAdapter, TreeIndentation) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.get().setTheme(R.style.Theme_Twig)
        val list = RecyclerView(activity.get())
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        list.layoutManager = LinearLayoutManager(activity.get())
        list.itemAnimator = null
        list.adapter = adapter
        adapter.submitList(nodes)
        activity.get().setContentView(list, ViewGroup.LayoutParams(480, 176))
        layout(list)
        val indentation = TreeIndentation(list, adapter) { Prefs.autoTreeIndent(list.context) }
        try {
            block(list, adapter, indentation)
        } finally {
            indentation.dispose()
            activity.pause().stop().destroy()
        }
    }

    private fun layout(list: RecyclerView) {
        list.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(176, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 480, 176)
    }

    private fun settle(list: RecyclerView, indentation: TreeIndentation) {
        indentation.onPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        layout(list)
        indentation.onPreDraw()
    }

    private fun padding(list: RecyclerView, position: Int) =
        list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.row_content).paddingStart

    @Test fun `partially visible parents retain indentation and scrolling back restores it`() {
        fixture(listOf(node(0, 0), node(1, 1)) + (2..40).map { node(it, 2) }) { list, adapter, indentation ->
            settle(list, indentation)
            assertEquals(16, padding(list, 1))
            val parentHeight = list.findViewHolderForAdapterPosition(0)!!.itemView.height
            list.scrollBy(0, parentHeight - 1)
            settle(list, indentation)
            assertEquals(0f, adapter.indentOffset, 0f)
            list.scrollBy(0, 1)
            settle(list, indentation)
            assertEquals(1f, adapter.indentOffset, 0f)
            assertEquals(2, padding(list, 1))
            assertEquals(16, padding(list, 2))
            list.scrollBy(0, -parentHeight)
            settle(list, indentation)
            assertEquals(0f, adapter.indentOffset, 0f)
            assertEquals(16, padding(list, 1))
        }
    }

    @Test fun `transition has intermediate frames and can reverse without jumping`() {
        fixture((0..40).map { node(it, 3) }) { list, adapter, indentation ->
            indentation.onPreDraw()
            assertEquals(0f, adapter.indentOffset, 0f)
            val animation = ReflectionHelpers.getField<ValueAnimator>(indentation, "animator")
            assertEquals(250L, animation.duration)
            animation.currentPlayTime = 64
            assertTrue("intermediate offset: ${adapter.indentOffset}", adapter.indentOffset > 0f && adapter.indentOffset < 3f)
            val beforeReverse = adapter.indentOffset
            Prefs.setAutoTreeIndent(list.context, false)
            indentation.onPreDraw()
            assertEquals(beforeReverse, adapter.indentOffset, 0.001f)
            settle(list, indentation)
            assertEquals(0f, adapter.indentOffset, 0f)
            assertEquals(44, padding(list, 0))
        }
    }

    @Test fun `properties retain owner depth and selection buttons stay fixed during indentation`() {
        val owner = node(0, 3)
        fixture(listOf(owner, PaneViewModel.InfoNode(owner.file, 4, null)) +
            (2..40).map { node(it, 4) }) { list, adapter, indentation ->
            val check = list.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.check)
            val right = check.right
            settle(list, indentation)
            assertEquals(3f, adapter.indentOffset, 0f)
            assertEquals(2, padding(list, 0))
            assertEquals(right, check.right)
            val tabs = list.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.tab_bar)
            assertEquals(16, (tabs.layoutParams as ViewGroup.MarginLayoutParams).marginStart)
            val ownerHeight = list.findViewHolderForAdapterPosition(0)!!.itemView.height
            list.scrollBy(0, ownerHeight)
            settle(list, indentation)
            assertEquals(3f, adapter.indentOffset, 0f)
        }
    }

    @Test fun `grid cells keep their width and newly attached tree rows use the current offset`() {
        fixture((0..40).map { node(it, 3) }) { list, adapter, indentation ->
            adapter.gridMode = 2
            adapter.cellPx = 236
            list.layoutManager = GridLayoutManager(list.context, 2)
            adapter.notifyDataSetChanged()
            layout(list)
            val width = list.getChildAt(0).width
            settle(list, indentation)
            assertEquals(3f, adapter.indentOffset, 0f)
            assertEquals(width, list.getChildAt(0).width)
            assertNull(list.getChildAt(0).findViewById<View>(R.id.row_content))
            adapter.gridMode = 0
            list.layoutManager = LinearLayoutManager(list.context)
            adapter.notifyDataSetChanged()
            layout(list)
            list.scrollBy(0, 500)
            settle(list, indentation)
            assertEquals(2, list.getChildAt(0).findViewById<View>(R.id.row_content).paddingStart)
        }
    }
}
