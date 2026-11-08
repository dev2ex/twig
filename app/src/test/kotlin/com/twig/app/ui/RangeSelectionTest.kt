package com.twig.app.ui

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class RangeSelectionTest {
    private fun node(path: String, depth: Int = 1, directory: Boolean = false, label: String? = null) =
        PaneViewModel.FileNode(XFile("file", path, directory, canRead = false), depth,
            directory, directory, label = label)

    private fun adapter(onChanged: () -> Unit = {}, filesOnly: Boolean = false) =
        FileAdapter(1, {}, {}, onChanged, { _, _ -> }, {}, selectFilesByRow = filesOnly)

    @Test fun `range uses display order and only selection payloads with one callback`() {
        var changes = 0
        val adapter = adapter({ changes++ })
        val rows = listOf(node("/photos/z.jpg"), node("/photos/b.jpg"), node("/photos/a.jpg"))
        adapter.submitList(rows)
        adapter.toggleSelection(rows.first())
        changes = 0
        var payloads = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = fail("Range selection must not invalidate the whole tree")
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                assertNotNull(payload)
                payloads++
            }
        })
        assertTrue(adapter.selectRangeTo(rows.last()))
        assertEquals(rows.map { it.file }, adapter.selectedItems())
        assertEquals(1, changes)
        assertTrue(payloads > 0)
    }

    @Test fun `latest check anchors reverse ranges and endpoint anchors the next range`() {
        val adapter = adapter()
        val rows = (0..6).map { node("/photos/$it.jpg") }
        adapter.submitList(rows)
        adapter.toggleSelection(rows[6])
        adapter.toggleSelection(rows[4])
        assertTrue(adapter.selectRangeTo(rows[2]))
        assertEquals(listOf(2, 3, 4, 6).map { rows[it].file }, adapter.selectedItems())
        assertTrue(adapter.selectRangeTo(rows[0]))
        assertEquals(listOf(0, 1, 2, 3, 4, 6).map { rows[it].file }, adapter.selectedItems())
        assertTrue(adapter.selectRangeTo(rows[4]))
        assertTrue(adapter.selectRangeTo(rows[6]))
        assertEquals(rows.map { it.file }, adapter.selectedItems())
    }

    @Test fun `range skips expanded descendants and rejects other tree branches and roots`() {
        val adapter = adapter()
        val first = node("/photos/a.jpg")
        val directory = node("/photos/folder", directory = true)
        val child = node("/photos/folder/child.jpg", depth = 2)
        val last = node("/photos/z.jpg")
        val other = node("/other/a.jpg")
        val root = node("/photos", depth = 0, directory = true, label = "Photos")
        val duplicate = last.copy(keyPrefix = "favorite:")
        adapter.submitList(listOf(root, first, directory, child, last, other, root.copy(keyPrefix = "favorite:"), duplicate))
        adapter.toggleSelection(first)
        assertFalse(adapter.selectRangeTo(child))
        assertFalse(adapter.selectRangeTo(other))
        assertFalse(adapter.selectRangeTo(duplicate))
        assertFalse(adapter.selectRangeTo(root))
        assertTrue(adapter.selectRangeTo(last))
        assertEquals(listOf(first.file, directory.file, last.file), adapter.selectedItems())
    }

    @Test fun `empty and cleared selection cannot start a range and unchecked anchor removes a range`() {
        val adapter = adapter()
        val rows = (0..4).map { node("/photos/$it.jpg") }
        adapter.submitList(rows)
        assertFalse(adapter.selectRangeTo(rows.last()))
        adapter.toggleSelection(rows[0])
        adapter.toggleSelection(rows[3])
        adapter.toggleSelection(rows[3])
        assertTrue(adapter.selectRangeTo(rows[2]))
        assertEquals(listOf(rows[0].file), adapter.selectedItems())
        adapter.clearSelection()
        assertFalse(adapter.selectRangeTo(rows.last()))
    }

    @Test fun `unchecking the final item retains a deselection anchor until explicitly cleared`() {
        val adapter = adapter()
        val rows = (0..4).map { node("/photos/$it.jpg") }
        adapter.submitList(rows)
        adapter.toggleSelection(rows[1])
        adapter.toggleSelection(rows[1])
        assertTrue(adapter.selectedItems().isEmpty())
        assertTrue(adapter.selectRangeTo(rows[4]))
        assertTrue(adapter.selectedItems().isEmpty())
        adapter.clearSelection()
        assertFalse(adapter.selectRangeTo(rows[0]))
        adapter.toggleSelection(rows[0])
        assertTrue(adapter.selectRangeTo(rows[2]))
        assertEquals(rows.take(3).map { it.file }, adapter.selectedItems())
    }

    @Test fun `checkbox long press repeats uncheck in either direction without clearing outside the range`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val parent = FrameLayout(ContextThemeWrapper(host.get(), R.style.Theme_Twig))
            for (grid in listOf(0, 2)) {
                for ((start, end) in listOf(1 to 4, 4 to 1)) {
                    for (endpointChecked in listOf(false, true)) {
                        var changes = 0
                        val adapter = FileAdapter(1, {}, { fail("Checkbox must not open a menu") },
                            { changes++ }, { _, _ -> }, {}, gridMode = grid)
                        val rows = (0..6).map { node("/photos/$it.jpg") }
                        adapter.submitList(rows)
                        adapter.setSelection(rows.filterIndexed { index, _ -> endpointChecked || index != end }.map { it.file })
                        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(start))
                        adapter.bindViewHolder(holder, start)
                        val id = if (grid == 0) R.id.check else R.id.cell_check
                        holder.itemView.findViewById<View>(id).performClick()
                        assertFalse(adapter.isSelected(rows[start]))
                        adapter.bindViewHolder(holder, end)
                        changes = 0
                        assertTrue(holder.itemView.findViewById<View>(id).performLongClick())
                        assertEquals(listOf(0, 5, 6).map { rows[it].file }, adapter.selectedItems())
                        assertEquals(1, changes)
                        // A deliberate check switches subsequent ranges back to selection.
                        adapter.toggleSelection(rows[2])
                        adapter.bindViewHolder(holder, 4)
                        holder.itemView.findViewById<View>(id).performLongClick()
                        assertEquals(listOf(0, 2, 3, 4, 5, 6).map { rows[it].file }, adapter.selectedItems())
                    }
                }
            }
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun `file picker ranges skip folders and reject folder endpoints`() {
        val adapter = adapter(filesOnly = true)
        val first = node("/photos/a.jpg")
        val folder = node("/photos/folder", directory = true)
        val last = node("/photos/z.jpg")
        adapter.submitList(listOf(first, folder, last))
        adapter.toggleSelection(first)
        assertFalse(adapter.selectRangeTo(folder))
        assertTrue(adapter.selectRangeTo(last))
        assertEquals(listOf(first.file, last.file), adapter.selectedItems())
    }

    @Test fun `only checkbox long press selects ranges in lists and grids`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val parent = FrameLayout(ContextThemeWrapper(host.get(), R.style.Theme_Twig))
            for (grid in listOf(0, 2)) {
                val menus = mutableListOf<PaneViewModel.Node>()
                val adapter = FileAdapter(1, {}, { menus.add(it) }, {}, { _, _ -> }, {}, gridMode = grid)
                val rows = (0..4).map { node("/photos/$it.jpg") }
                adapter.submitList(rows)
                val holder = adapter.createViewHolder(parent, adapter.getItemViewType(4))
                adapter.bindViewHolder(holder, 4)
                val check = holder.itemView.findViewById<View>(if (grid == 0) R.id.check else R.id.cell_check)
                assertTrue(check.performLongClick())
                assertTrue(adapter.selectedItems().isEmpty())
                assertTrue(menus.isEmpty())
                adapter.toggleSelection(rows[0])
                holder.itemView.performLongClick()
                holder.itemView.findViewById<View>(if (grid == 0) R.id.icon else R.id.thumb).performLongClick()
                assertEquals(listOf(rows[4], rows[4]), menus)
                assertEquals(listOf(rows[0].file), adapter.selectedItems())
                assertTrue(check.performLongClick())
                assertEquals(rows.map { it.file }, adapter.selectedItems())
                assertEquals(2, menus.size)
                // Long-press another checked item, not just the most recent endpoint.
                val firstHolder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
                adapter.bindViewHolder(firstHolder, 0)
                val firstCheck = firstHolder.itemView.findViewById<View>(if (grid == 0) R.id.check else R.id.cell_check)
                assertTrue(firstCheck.performLongClick())
                assertEquals(rows.map { it.file }, adapter.selectedItems())
                assertEquals(2, menus.size)
                assertTrue(check.performLongClick())
                assertEquals(rows.map { it.file }, adapter.selectedItems())
                firstCheck.performClick()
                assertEquals(rows.drop(1).map { it.file }, adapter.selectedItems())
                assertTrue(check.performLongClick())
                assertTrue(adapter.selectedItems().isEmpty()) // Uncheck only through the explicit range.
                adapter.clearSelection()
                assertTrue(check.performLongClick())
                assertTrue(adapter.selectedItems().isEmpty()) // Explicit clear resets the range anchor.
            }
        } finally {
            host.pause().stop().destroy()
        }
    }
}
