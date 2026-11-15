package com.twig.app.ui

import android.os.Looper
import com.twig.core.XFile
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class CrossDirectorySelectionTest {
    private fun node(path: String, dir: Boolean = false, depth: Int = 1) =
        PaneViewModel.FileNode(XFile("file", path, dir, canRead = false), depth, dir, dir)
    private fun adapter(state: PaneSelection = PaneSelection()) =
        FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, selection = state)

    private fun submit(adapter: FileAdapter, rows: List<PaneViewModel.Node>) {
        var committed = false
        adapter.submitList(rows) { committed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!committed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue("List must commit", committed)
    }

    @Test fun `folding clears only descendant checks and reopening does not restore them`() {
        var changes = 0
        val adapter = FileAdapter(1, {}, {}, { changes++ }, { _, _ -> }, {})
        val folder = node("/photos/trip", true, 0)
        val a = node("/photos/trip/a.jpg")
        val nested = node("/photos/trip/day2/b.jpg", depth = 2)
        val b = node("/downloads/docs/b.pdf", depth = 0)
        val expanded = listOf(folder, a, nested, b)
        submit(adapter, expanded)
        adapter.toggleSelection(folder)
        adapter.toggleSelection(a)
        adapter.toggleSelection(nested)
        adapter.toggleSelection(b)
        changes = 0
        submit(adapter, listOf(folder.copy(expanded = false), b))
        assertEquals(listOf(folder.file, b.file), adapter.selectedItems())
        assertEquals(1, changes)
        assertFalse(adapter.isSelected(a))
        assertFalse(adapter.isSelected(nested))
        submit(adapter, expanded)
        assertFalse(adapter.isSelected(a))
        assertFalse(adapter.isSelected(nested))
        assertTrue(adapter.isSelected(b))
    }

    @Test fun `removing all rows clears checks and notifies the host`() {
        var changes = 0
        val adapter = FileAdapter(1, {}, {}, { changes++ }, { _, _ -> }, {})
        val a = node("/photos/trip/a.jpg")
        val b = node("/downloads/docs/b.pdf")
        submit(adapter, listOf(a, b))
        adapter.toggleSelection(a)
        adapter.toggleSelection(b)
        assertEquals(listOf(a.file, b.file), adapter.selectedItems())
        changes = 0
        adapter.submitList(null)
        assertTrue(adapter.selectedItems().isEmpty())
        assertEquals(1, changes)
        submit(adapter, listOf(a, b))
        assertFalse(adapter.isSelected(a))
        assertFalse(adapter.isSelected(b))
    }

    @Test fun `pane state survives adapter recreation and aliases share one check`() {
        val state = PaneSelection()
        val first = adapter(state)
        val a = node("/photos/a.jpg")
        val alias = a.copy(keyPrefix = "fav:")
        first.submitList(listOf(a, alias))
        first.toggleSelection(alias)
        assertTrue(first.isSelected(a))
        assertEquals(listOf(a.file), first.selectedItems())
        val second = adapter(state)
        assertEquals(listOf(a.file), second.selectedItems())
        second.submitList(listOf(a))
        second.toggleSelection(a)
        assertTrue(second.selectedItems().isEmpty())
    }

    @Test fun `directory three state cycle preserves checks outside its branch`() {
        val adapter = adapter()
        val folder = node("/photos", true, 0)
        val a = node("/photos/a.jpg")
        val b = node("/photos/b.jpg")
        val other = node("/downloads/c.pdf", depth = 0)
        adapter.submitList(listOf(folder, a, b, other))
        adapter.toggleSelection(other)
        adapter.cycleSelection(folder)
        assertEquals(setOf(folder.file, other.file), adapter.selectedItems().toSet())
        adapter.cycleSelection(folder)
        assertEquals(setOf(a.file, b.file, other.file), adapter.selectedItems().toSet())
        adapter.cycleSelection(folder)
        assertEquals(listOf(other.file), adapter.selectedItems())
    }

    @Test fun `whole selection replacement retains unseen entries from viewer or review dialog`() {
        val adapter = adapter()
        val a = node("/photos/a.jpg")
        val b = node("/downloads/b.pdf")
        adapter.submitList(listOf(a))
        adapter.setSelection(listOf(a.file, b.file))
        assertEquals(listOf(a.file, b.file), adapter.selectedItems())
        adapter.setSelection(listOf(b.file))
        assertFalse(adapter.isSelected(a))
        assertEquals(listOf(b.file), adapter.selectedItems())
    }
}
