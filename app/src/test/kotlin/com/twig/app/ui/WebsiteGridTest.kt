package com.twig.app.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteGridTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val website = PaneViewModel.WebsiteNode(Website(id = "grid", name = "My website", url = "https://example.com"))
    private fun parent() = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))

    @Test fun `website sorting uses list handles in grid mode and restores cells afterward`() {
        var clicked = false
        var dragged = false
        val adapter = FileAdapter(1, { clicked = true }, {}, {}, { _, _ -> }, {}, gridMode = 2)
        adapter.onItemDrag = { dragged = true }
        adapter.submitList(listOf(website.copy(reordering = true)))
        assertFalse(adapter.isCellAt(0))
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val handle = holder.itemView.findViewById<View>(R.id.indicator)
        assertEquals(View.VISIBLE, handle.visibility)
        holder.itemView.performClick()
        assertFalse(clicked)
        val down = android.view.MotionEvent.obtain(0, 10, android.view.MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        handle.dispatchTouchEvent(down)
        down.recycle()
        assertTrue(dragged)
        adapter.submitList(null)
        adapter.submitList(listOf(website))
        assertTrue(adapter.isCellAt(0))
    }

    @Test fun `websites use cells only in all files mode while group and add action keep full rows`() {
        for (mode in 0..2) {
            val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, gridMode = mode)
            adapter.submitList(listOf(PaneViewModel.GroupNode("websites", "Websites", true), website,
                PaneViewModel.ActionNode("add_website", "Add website")))
            assertFalse(adapter.isCellAt(0))
            assertFalse(adapter.isCellAt(2))
            assertEquals(mode == 2, adapter.isCellAt(1))
            assertEquals(mode != 2, adapter.isPlainRowAt(1))
            val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(1))
            adapter.bindViewHolder(holder, 1)
            val name = holder.itemView.findViewById<TextView>(if (mode == 2) R.id.cell_name else R.id.name)
            assertEquals("My website", name.text.toString())
        }
    }

    @Test fun `recycled website cell opens and edits without file controls and restores file controls when reused`() {
        var clicked: PaneViewModel.Node? = null
        var held: PaneViewModel.Node? = null
        val adapter = FileAdapter(1, { clicked = it }, { held = it }, {}, { _, _ -> }, {}, gridMode = 2)
        val file = PaneViewModel.FileNode(XFile("file", "/sdcard/a.txt", isDir = false),
            depth = 1, expandable = false, expanded = false)
        adapter.submitList(listOf(file, website))
        adapter.cellPx = 120
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        adapter.toggleSelection(file)
        adapter.bindViewHolder(holder, 0)
        assertTrue(holder.itemView.isActivated)
        adapter.bindViewHolder(holder, 1)
        assertFalse(holder.itemView.isActivated)
        assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.cell_check).visibility)
        assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.info_toggle).visibility)
        assertEquals(120, holder.itemView.findViewById<View>(R.id.thumb_box).layoutParams.height)
        holder.itemView.performClick()
        holder.itemView.performLongClick()
        assertEquals(website, clicked)
        assertEquals(website, held)
        adapter.bindViewHolder(holder, 0)
        assertTrue(holder.itemView.isActivated)
        assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.cell_check).visibility)
        assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.info_toggle).visibility)
        holder.itemView.findViewById<View>(R.id.cell_check).performClick()
        assertTrue(adapter.selectedItems().isEmpty())
    }

    @Test fun `website cells follow the existing grid name visibility setting`() {
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, gridMode = 2, gridNames = false)
        adapter.submitList(listOf(website))
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.cell_name).visibility)
    }
}
