package com.twig.app.ui

import android.Manifest
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.twig.app.MainActivity
import com.twig.app.R
import com.twig.core.XFile
import com.twig.core.isMutable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * ★ Checking a file has to re-evaluate the action strip's write entries.
 *
 * The strip's Move / Rename / Delete follow **what the selection points at**
 * (`PaneFragment.stripActions` → `selectionOrCurrent`), but checking a row changes no tree
 * state at all — no `render`, so no `onClipTargetChanged` either. The callback the adapter
 * fires for exactly this event was an empty placeholder, so the strip kept the answer it
 * had computed at the last navigation and nothing recomputed it:
 *
 * 1. left pane on a local directory, switch to the right pane (empty → greyed out),
 * 2. right pane: open an SFTP favorite, check `dosbox-0.74-3.conf`,
 * 3. Move / Rename / Delete **still greyed out** on a perfectly writable SFTP directory,
 * 4. switch to the left pane and back → `setActiveIndex` recomputes as a side effect and
 *    suddenly they work.
 *
 * The strip was showing the state of the *previous* directory, not of the selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StripWriteActionsTest {

    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        // Without storage permission MainActivity only puts up the permission dialog and never
        // builds the panes -- sdk 28 is what keeps that on the grantable legacy check.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        activity = Robolectric.buildActivity(MainActivity::class.java).create().start().get()
        idle()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Every strip instance (landscape middle + portrait left/right) has to agree — they all show the same state. */
    private fun writeActions(): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for (strip in listOf(R.id.strip_left, R.id.strip_mid, R.id.strip_right)) {
            val root = activity.findViewById<View>(strip) ?: continue
            for ((name, id) in listOf("move" to R.id.as_move, "rename" to R.id.as_rename, "delete" to R.id.as_delete)) {
                out += "$name@$strip" to root.findViewById<View>(id).isEnabled
            }
        }
        return out
    }

    private fun assertAllWriteActions(enabled: Boolean, why: String) {
        val states = writeActions()
        assertEquals(9, states.size)
        val wrong = states.filter { it.second != enabled }
        assertTrue(why, wrong.isEmpty())
    }

    private fun pane0(): PaneFragment =
        activity.supportFragmentManager.findFragmentByTag("pane0") as PaneFragment

    private fun pane1(): PaneFragment =
        activity.supportFragmentManager.findFragmentByTag("pane1") as PaneFragment

    @Test
    fun `holding any Up icon collapses expanded groups when no frame is offscreen`() {
        val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        val navigation = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
            .first { it is android.widget.ImageButton && it.drawable === toolbar.navigationIcon }
        val controls = listOf(navigation) + listOf(R.id.strip_left, R.id.strip_mid, R.id.strip_right).map {
            activity.findViewById<View>(it).findViewById<View>(R.id.as_up)
        }
        ReflectionHelpers.setField(activity, "activeIndex", 0)
        val pane = pane0()
        val vm = pane.viewModel
        for (control in controls) {
            ReflectionHelpers.setField(vm, "currentKey", null)
            vm.expandGroup("fav")
            idle()
            assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.GroupNode>()
                .any { it.id == "fav" && it.expanded })
            assertTrue(control.performLongClick())
            idle()
            assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.GroupNode>()
                .none { it.expanded })
        }
    }

    private fun assertStrip(id: Int, enabled: Boolean) {
        for (strip in listOf(R.id.strip_left, R.id.strip_mid, R.id.strip_right)) {
            val button = activity.findViewById<View>(strip).findViewById<View>(id)
            assertEquals("enabled state for $id in $strip", enabled, button.isEnabled)
            assertEquals(if (enabled) 1f else 0.35f, button.alpha, 0.01f)
        }
    }

    private fun adapterOf(pane: PaneFragment): FileAdapter =
        ReflectionHelpers.getField<FileAdapter>(pane, "adapter")

    /** A checked row has to be in the adapter's current list — that is what `selectedItems()` filters. */
    private fun awaitList(adapter: FileAdapter, size: Int) {
        repeat(200) {
            idle()
            if (adapter.currentList.size == size) return
            Thread.sleep(10)
        }
        assertEquals("the rows never landed, so a selection over them could not be asserted", size, adapter.currentList.size)
    }

    @Test
    fun `checking a writable file enables the write actions without switching panes`() {
        val pane = pane0()
        assertTrue("the pane's view is not built, nothing to assert", pane.isReady())
        val adapter = adapterOf(pane)

        // Nothing checked, and the freshly bootstrapped tree has no green-highlighted file to
        // fall back on: this is the state the user was looking at when the buttons were dead.
        adapter.submitList(emptyList())
        idle()
        assertAllWriteActions(false, "no selection at all → nothing to modify")

        val writable = XFile("file", "/storage/emulated/0/Download/dosbox-0.74-3.conf", isDir = false)
        val readOnly = XFile("zip", "/a.zip/b.conf", isDir = false, canWrite = false)
        assertTrue("sanity: the local source must count as mutable", writable.isMutable())
        assertFalse("sanity: an archive entry must not", readOnly.isMutable())

        val nodes = listOf(
            PaneViewModel.FileNode(writable, depth = 1, expandable = false, expanded = false),
            PaneViewModel.FileNode(readOnly, depth = 1, expandable = false, expanded = false),
        )
        adapter.submitList(nodes)
        awaitList(adapter, nodes.size)

        // What a long-press "Select" does.
        adapter.setSelection(listOf(writable))
        assertAllWriteActions(true, "a checked writable file makes Move / Rename / Delete usable, no pane switch needed")

        adapter.setSelection(listOf(readOnly))
        assertAllWriteActions(false, "checking a read-only entry greys them out again")

        adapter.clearSelection()
        assertAllWriteActions(false, "and unchecking greys them out again")
    }
    @Test
    fun `virtual groups disable directory actions while transfers depend only on sources`() {
        val pane = pane0()
        val adapter = adapterOf(pane)
        val dir = XFile("file", "/destination", isDir = true)
        pane1().viewModel.currentDir = dir
        pane.viewModel.toggle(PaneViewModel.GroupNode("fav", "Favorites", expanded = false))
        idle()
        activity.onClipTargetChanged()
        for (id in listOf(R.id.as_copy, R.id.as_compress, R.id.as_compare, R.id.as_map, R.id.as_search)) {
            assertStrip(id, false)
        }

        ReflectionHelpers.setField(pane, "mapMode", true)
        activity.onClipTargetChanged()
        assertStrip(R.id.as_map, true) // The same button exits the map, even without a directory target.
        ReflectionHelpers.setField(pane, "mapMode", false)
        activity.onClipTargetChanged()
        assertStrip(R.id.as_map, false)

        val files = listOf("a.txt", "b.txt").map { XFile("file", "/source/$it", isDir = false) }
        adapter.submitList(files.map { PaneViewModel.FileNode(it, 1, false, false) })
        awaitList(adapter, 2)
        adapter.setSelection(files)
        assertStrip(R.id.as_copy, true)
        assertStrip(R.id.as_compress, true)
        assertStrip(R.id.as_move, true)
        assertStrip(R.id.as_delete, true)
        assertStrip(R.id.as_rename, true) // Multiple writable sources support batch rename.
        assertStrip(R.id.as_search, false)

        pane1().viewModel.currentDir = dir.copy(canWrite = false)
        activity.onClipTargetChanged()
        assertStrip(R.id.as_copy, true)
        assertStrip(R.id.as_compress, true)
        assertStrip(R.id.as_move, true)
        assertStrip(R.id.as_delete, true)

        pane.viewModel.currentDir = XFile("file", "/source", isDir = true)
        activity.onClipTargetChanged()
        assertStrip(R.id.as_compare, true)
        assertStrip(R.id.as_map, true)
        assertStrip(R.id.as_search, true)
        pane1().viewModel.currentDir = null
        activity.onClipTargetChanged()
        assertStrip(R.id.as_compare, true)
        activity.findViewById<View>(R.id.strip_left).findViewById<View>(R.id.as_compare).performClick()
        assertEquals(activity.getString(R.string.compare_need_two),
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertStrip(R.id.as_copy, true)
        assertStrip(R.id.as_compress, true)
        assertStrip(R.id.as_move, true)
    }

    @Test
    fun `favorite and storage roots can be read without enabling mutation`() {
        val pane = pane0()
        val adapter = adapterOf(pane)
        adapter.submitList(emptyList())
        awaitList(adapter, 0)
        pane1().viewModel.currentDir = null
        val targets = listOf(
            "fav:test" to XFile("file", "/favorite", isDir = true),
            "file:/" to XFile("file", "/", isDir = true),
            "file:internal" to XFile("file", android.os.Environment.getExternalStorageDirectory().absolutePath, isDir = true),
        )
        for ((key, dir) in targets) {
            ReflectionHelpers.setField(pane.viewModel, "currentKey", key)
            pane.viewModel.currentDir = dir
            activity.onClipTargetChanged()
            assertStrip(R.id.as_copy, true)
            assertStrip(R.id.as_compress, true)
            assertAllWriteActions(false, "a read source must not enable mutation of $key")
            // Copy picks a destination first when the other pane has none.
            pane.actionCopy(move = false)
            pane.childFragmentManager.executePendingTransactions()
            assertTrue(pane.childFragmentManager.findFragmentByTag(CopyConfirmDialog.TAG) == null)
            assertTrue(activity.isPaneActive(pane1()))
            activity.findViewById<View>(R.id.btn_copy_destination_cancel).performClick()
            idle()
            pane.actionCompress()
            assertTrue(activity.isChoosingDestination())
            assertTrue(activity.isPaneActive(pane1()))
            activity.findViewById<View>(R.id.btn_copy_destination_cancel).performClick()
            idle()
        }
        pane.viewModel.toggle(PaneViewModel.GroupNode("fav", "Favorites", expanded = false))
        activity.onClipTargetChanged()
        assertStrip(R.id.as_copy, false)
        assertStrip(R.id.as_compress, false)
    }

    @Test
    fun `long copy and move start cancellable progress immediately even for huge files`() {
        val pane = pane0()
        val adapter = adapterOf(pane)
        val source = XFile("file", "/source/huge.bin", isDir = false, size = 10L * 1024 * 1024 * 1024)
        adapter.submitList(listOf(PaneViewModel.FileNode(source, 1, false, false)))
        awaitList(adapter, 1)
        adapter.setSelection(listOf(source))
        pane1().viewModel.currentDir = XFile("file", activity.cacheDir.path, isDir = true)
        for ((button, move) in listOf(R.id.as_copy to false, R.id.as_move to true)) {
            assertTrue(activity.findViewById<View>(R.id.strip_right).findViewById<View>(button).performLongClick())
            val session = requireNotNull(Transfers.active)
            val work = session.work as Transfers.Work.Copy
            assertEquals(move, work.move)
            assertEquals(listOf(source), work.items)
            val progress = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(progress.isShowing)
            assertTrue(progress.findViewById<View>(R.id.bar_file) != null)
            progress.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            idle()
            assertTrue(session.cancelled.get())
            val worker = Thread { Transfers.execute(activity, session) {} }
            worker.start(); worker.join(5000)
            idle()
            assertFalse(worker.isAlive)
            assertTrue(Transfers.active == null)
            Transfers.consumeFinished()
            adapter.submitList(listOf(PaneViewModel.FileNode(source, 1, false, false)))
            awaitList(adapter, 1)
            adapter.setSelection(listOf(source))
        }
    }

    @Test
    fun `long rename selects just the extension`() {
        val pane = pane0()
        val adapter = adapterOf(pane)
        val source = XFile("file", "/source/photo.backup.jpg", isDir = false)
        adapter.submitList(listOf(PaneViewModel.FileNode(source, 1, false, false)))
        awaitList(adapter, 1)
        adapter.setSelection(listOf(source))
        assertTrue(activity.findViewById<View>(R.id.strip_right).findViewById<View>(R.id.as_rename).performLongClick())
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        fun inputs(view: View): List<android.widget.EditText> = when (view) {
            is android.widget.EditText -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { inputs(view.getChildAt(it)) }
            else -> emptyList()
        }
        val input = inputs(dialog.window!!.decorView).single()
        assertEquals("jpg", input.text.substring(input.selectionStart, input.selectionEnd))
        dialog.dismiss()
    }

    @Test
    fun `duplicate is hidden by default and follows writable selection`() {
        assertFalse(com.twig.app.Prefs.stripItemVisible(activity, "duplicate"))
        for (strip in listOf(R.id.strip_left, R.id.strip_mid, R.id.strip_right)) {
            assertEquals(View.GONE, activity.findViewById<View>(strip).findViewById<View>(R.id.as_duplicate).visibility)
        }
        val adapter = adapterOf(pane0())
        val file = XFile("file", "/source/a.txt", isDir = false)
        adapter.submitList(listOf(PaneViewModel.FileNode(file, 1, false, false)))
        awaitList(adapter, 1)
        adapter.setSelection(listOf(file))
        assertStrip(R.id.as_duplicate, true)
        adapter.clearSelection()
        assertStrip(R.id.as_duplicate, false)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        repeat(300) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("background operation did not finish", condition())
    }

    @Test
    fun `duplicate copies in place and long press renames without a dialog`() {
        val dir = java.io.File(activity.cacheDir, "duplicate-test").apply { mkdirs() }
        val original = java.io.File(dir, "photo.jpg").apply { writeText("image") }
        java.io.File(dir, "PHOTO_1.jpg").writeText("keep")
        val source = XFile("file", original.path, false, original.length())
        val adapter = adapterOf(pane0())
        fun select() {
            adapter.submitList(listOf(PaneViewModel.FileNode(source, 1, false, false)))
            awaitList(adapter, 1)
            adapter.setSelection(listOf(source))
        }
        select()
        FileClipboard.put(listOf(source))
        val button = activity.findViewById<View>(R.id.strip_right).findViewById<View>(R.id.as_duplicate)
        button.performClick()
        awaitCondition { Transfers.active != null }
        val session = requireNotNull(Transfers.active)
        assertTrue((session.work as Transfers.Work.Sync).duplicate)
        val worker = Thread { Transfers.execute(activity, session) {} }
        worker.start(); worker.join(5000)
        idle()
        assertFalse(worker.isAlive)
        assertTrue(session.finished?.isSuccess == true)
        assertEquals("image", java.io.File(dir, "photo_2.jpg").readText())
        assertEquals("image", original.readText())
        assertEquals("keep", java.io.File(dir, "PHOTO_1.jpg").readText())
        assertEquals(listOf(source), FileClipboard.items)
        Transfers.consumeFinished()
        select()
        assertTrue(button.performLongClick())
        awaitCondition { !original.exists() }
        assertEquals("image", java.io.File(dir, "photo_3.jpg").readText())
        assertEquals("image", java.io.File(dir, "photo_2.jpg").readText())
        FileClipboard.clear()
    }

}
