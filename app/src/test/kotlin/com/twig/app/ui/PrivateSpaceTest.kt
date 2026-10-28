package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.PrivateSpace
import com.twig.app.RootItems
import com.twig.app.MainActivity
import com.twig.core.FsRegistry
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateSpaceTest {
    private lateinit var app: Application
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
        Dispatchers.setMain(dispatcher)
        FsRegistry.register(LocalFileSystem())
    }

    @After fun teardown() {
        PrivateSpace.directory(app).deleteRecursively()
        FsRegistry.unregister("file")
        Dispatchers.resetMain()
    }

    @Test fun `private space is visible by default and explicit hiding is independent per pane`() {
        val left = PaneViewModel(app).apply { io = dispatcher }
        val right = PaneViewModel(app).apply { io = dispatcher; paneIndex = 1 }
        fun privateRow(vm: PaneViewModel) = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .firstOrNull { it.file.path == PrivateSpace.directory(app).path }
        left.bootstrap()
        right.bootstrap()
        assertNotNull(privateRow(left))
        assertNotNull(privateRow(right))
        Prefs.setRootItemVisible(app, 0, "private", false)
        left.refreshRootItems()
        right.refreshRootItems()
        assertNull(privateRow(left))
        assertNotNull(privateRow(right))
    }

    @Test fun `private space uses local files supports favorites and clears hidden copy target`() = runTest(dispatcher) {
        Prefs.setRootItemVisible(app, 0, "private", true)
        val vm = PaneViewModel(app).apply { io = dispatcher }
        vm.bootstrap()
        val row = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == PrivateSpace.directory(app).path }
        vm.toggle(row)
        advanceUntilIdle()
        assertTrue(PrivateSpace.directory(app).isDirectory)
        assertEquals(row.file.path, vm.currentDir?.path)
        assertNull("The Private space root must not be a default rename/delete target", vm.currentSelection())
        assertNotNull(vm.favoriteFrom(row.file))
        File(PrivateSpace.directory(app), "note.txt").writeText("private text")
        assertEquals("private text", FsRegistry.of(row.file).openInput(
            row.file.copy(path = row.file.path + "/note.txt", isDir = false),
        ).bufferedReader().use { it.readText() })
        Prefs.setRootItemVisible(app, 0, "private", false)
        vm.refreshRootItems()
        assertNull(vm.currentDir)
    }

    @Test fun `hidden network roots do not affect the other pane`() {
        Prefs.setRootItemVisible(app, 0, "lan", false)
        val left = PaneViewModel(app).apply { io = dispatcher }
        val right = PaneViewModel(app).apply { io = dispatcher; paneIndex = 1 }
        left.bootstrap()
        right.bootstrap()
        assertFalse(left.state.value.rows.any { it.key == "g:lan" })
        assertTrue(right.state.value.rows.any { it.key == "g:lan" })
        assertTrue(RootItems.all.filter { it.id !in setOf("private", "lan") }.all { Prefs.rootItemVisible(app, 0, it.id) })
    }

    @Test fun `navigation hints reach only this apps data and do not invent children inside it`() {
        val own = File(app.applicationInfo.dataDir)
        assertEquals(own.path, PrivateSpace.navigationChild(app, own.parent!!)!!.path)
        assertNull(PrivateSpace.navigationChild(app, own.path))
        assertNull(PrivateSpace.navigationChild(app, "/unrelated"))
    }

    @Test fun `data directory alias is offered only when it resolves to this users own data`() {
        val own = File(app.applicationInfo.dataDir)
        val parent = File(app.cacheDir, "alias-test").apply { mkdirs() }
        val alias = File(parent, "twig")
        try {
            java.nio.file.Files.createSymbolicLink(alias.toPath(), own.toPath())
            assertEquals(alias.path, PrivateSpace.navigationChild(own, parent.path, alias)?.path)
            java.nio.file.Files.delete(alias.toPath())
            val otherUser = File(parent, "another-user").apply { mkdirs() }
            java.nio.file.Files.createSymbolicLink(alias.toPath(), otherUser.toPath())
            assertNull(PrivateSpace.navigationChild(own, parent.path, alias))
        } finally {
            java.nio.file.Files.deleteIfExists(alias.toPath())
            parent.deleteRecursively()
        }
    }

    @Test fun `locating a hidden source pins it above favorites without changing visibility`() = runTest(dispatcher) {
        Prefs.setRootItemVisible(app, 0, "root", false)
        Prefs.setRootItemVisible(app, 1, "root", false)
        val vm = PaneViewModel(app).apply { io = dispatcher }
        vm.bootstrap()
        val own = XFile("file", app.applicationInfo.dataDir, true)
        vm.revealPath(own)
        advanceUntilIdle()
        assertFalse(Prefs.rootItemVisible(app, 0, "root"))
        assertFalse(Prefs.rootItemVisible(app, 1, "root"))
        assertEquals(own.path, vm.currentDir?.path)
        assertTrue(vm.state.value.rows.any { it.key == vm.state.value.currentKey })
        val pinned = vm.state.value.rows.first() as PaneViewModel.FileNode
        assertEquals(own.path, pinned.file.path)
        assertTrue(pinned.key.startsWith(PaneViewModel.EXTERNAL_KEY_PREFIX))
        assertEquals("g:fav", vm.state.value.rows.drop(1).first { it.depth == 0 }.key)
        assertTrue(vm.unmountPinnedDirectory())
        assertFalse(vm.state.value.rows.any { it.key.startsWith(PaneViewModel.EXTERNAL_KEY_PREFIX) })
        assertFalse(Prefs.rootItemVisible(app, 0, "root"))
    }

    @Test
    @Config(sdk = [28])
    fun `root dialog shows icons and keeps checkbox changes until confirmed`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            MainActivity::class.java.getDeclaredMethod("showRootItemsDialog").apply { isAccessible = true }
                .invoke(controller.get())
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val list = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(com.twig.app.R.id.items)!!
            layoutRootList(list)
            val row = list.findViewWithTag<android.view.View>("private")
            assertNotNull(row.findViewById<android.widget.ImageView>(com.twig.app.R.id.icon).drawable)
            val left = row.findViewById<android.widget.CheckBox>(com.twig.app.R.id.left)
            val right = row.findViewById<android.widget.CheckBox>(com.twig.app.R.id.right)
            assertTrue(left.isChecked)
            assertTrue(right.isChecked)
            row.findViewById<android.view.View>(com.twig.app.R.id.left_cell).performClick()
            assertTrue(dialog.isShowing)
            assertFalse(left.isChecked)
            assertTrue(right.isChecked)
            assertTrue(Prefs.rootItemVisible(app, 0, "private"))
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(Prefs.rootItemVisible(app, 0, "private"))
            assertTrue(Prefs.rootItemVisible(app, 1, "private"))
        } finally {
            controller.stop().destroy()
        }
    }

    private fun layoutRootList(list: androidx.recyclerview.widget.RecyclerView) {
        val exact = android.view.View.MeasureSpec.EXACTLY
        list.measure(android.view.View.MeasureSpec.makeMeasureSpec(900, exact),
            android.view.View.MeasureSpec.makeMeasureSpec(1400, exact))
        list.layout(0, 0, 900, 1400)
    }

    @Test fun `saved order discards stale duplicates and includes new roots once`() {
        app.getSharedPreferences("twig_prefs", 0).edit()
            .putString("root_item_order", "apps,removed,apps,private").commit()
        val ids = Prefs.rootItemOrder(app).map { it.id }
        assertEquals(listOf("apps", "private"), ids.take(2))
        assertEquals(RootItems.all.map { it.id }.toSet(), ids.toSet())
        assertEquals(RootItems.all.size, ids.size)
    }

    @Test fun `reordering keeps expanded subtrees selection and fixed roots in place`() = runTest(dispatcher) {
        val file = File(PrivateSpace.directory(app).apply { mkdirs() }, "reorder.txt").apply { writeText("test") }
        val vm = PaneViewModel(app).apply { io = dispatcher }
        vm.bootstrap()
        val privateRow = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == file.parent }
        vm.toggle(privateRow)
        advanceUntilIdle()
        val selected = vm.currentDir
        val before = vm.state.value.rows.filter { it.depth == 0 }.map { it.key }
        Prefs.setRootItemOrder(app, listOf("private", "apps", "fav"))
        vm.refreshRootItems()
        val rows = vm.state.value.rows
        assertEquals(privateRow.key, rows.first().key)
        assertEquals(file.path, (rows[1] as PaneViewModel.FileNode).file.path)
        assertEquals(selected, vm.currentDir)
        assertTrue((rows.first() as PaneViewModel.FileNode).expanded)
        val after = rows.filter { it.depth == 0 }.map { it.key }
        val internal = before.first { it.startsWith("f:file:") && it != privateRow.key && it != "f:file:/" }
        assertEquals(before.indexOf(internal), after.indexOf(internal))
        assertEquals("g:fav", after[before.indexOf(privateRow.key)])
    }

    @Test
    @Config(sdk = [28])
    fun `dialog drag saves by identity only on confirmation and cancel leaves preferences alone`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            fun moveAndToggle(dialog: androidx.appcompat.app.AlertDialog) {
                val list = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(com.twig.app.R.id.items)!!
                layoutRootList(list)
                val source = list.findViewHolderForAdapterPosition(0)!!
                val target = list.findViewHolderForAdapterPosition(2)!!
                source.itemView.findViewById<android.widget.CheckBox>(com.twig.app.R.id.left).performClick()
                val helper = (0 until list.itemDecorationCount).map { list.getItemDecorationAt(it) }
                    .filterIsInstance<androidx.recyclerview.widget.ItemTouchHelper>().single()
                val callback = androidx.recyclerview.widget.ItemTouchHelper::class.java.getDeclaredField("mCallback")
                    .apply { isAccessible = true }.get(helper) as androidx.recyclerview.widget.ItemTouchHelper.Callback
                assertTrue(callback.onMove(list, source, target))
                layoutRootList(list)
                assertEquals("fav", list.findViewHolderForAdapterPosition(2)!!.itemView.tag)
            }
            val canceled = RootItemsDialog.show(controller.get()) { fail("Cancel must not save") }
            moveAndToggle(canceled)
            canceled.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(RootItems.all, Prefs.rootItemOrder(app))
            assertTrue(Prefs.rootItemVisible(app, 0, "fav"))
            var saved = false
            val confirmed = RootItemsDialog.show(controller.get()) { saved = true }
            moveAndToggle(confirmed)
            confirmed.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(saved)
            assertEquals(listOf("root", "private", "fav"), Prefs.rootItemOrder(app).take(3).map { it.id })
            assertFalse(Prefs.rootItemVisible(app, 0, "fav"))
            assertTrue(Prefs.rootItemVisible(app, 1, "fav"))
            assertTrue(Prefs.rootItemVisible(app, 0, "private"))
            val reopened = RootItemsDialog.show(controller.get()) { }
            val list = reopened.findViewById<androidx.recyclerview.widget.RecyclerView>(com.twig.app.R.id.items)!!
            layoutRootList(list)
            val fav = list.findViewHolderForAdapterPosition(2)!!.itemView
            assertEquals("fav", fav.tag)
            assertFalse(fav.findViewById<android.widget.CheckBox>(com.twig.app.R.id.left).isChecked)
            assertTrue(fav.findViewById<android.widget.CheckBox>(com.twig.app.R.id.right).isChecked)
            reopened.dismiss()
        } finally {
            controller.stop().destroy()
        }
    }

    @Test fun `root browsing reaches own data when ancestors cannot be listed without changing filesystem errors`() = runTest(dispatcher) {
        val local = LocalFileSystem()
        val own = File(app.applicationInfo.dataDir)
        val ancestors = generateSequence(own.parentFile) { it.parentFile }.map { it.path }.toSet()
        val fs = object : FileSystem by local {
            override fun list(dir: XFile): List<XFile> {
                if (dir.path in ancestors) throw FsException("Listing denied")
                return local.list(dir)
            }
        }
        FsRegistry.register(fs)
        val vm = PaneViewModel(app).apply { io = dispatcher }
        vm.bootstrap()
        var path = "/"
        while (true) {
            val row = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first { it.file.path == path }
            vm.toggle(row)
            advanceUntilIdle()
            if (path == own.path) break
            path = PrivateSpace.navigationChild(app, path)!!.path
            assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.path == path })
        }
        assertEquals(own.path, vm.currentDir?.path)
        assertThrows(FsException::class.java) { fs.list(XFile("file", "/", true)) }
    }
}
