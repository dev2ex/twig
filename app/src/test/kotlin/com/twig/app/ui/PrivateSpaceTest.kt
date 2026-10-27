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
        Prefs.setRootItemVisible(app, 0, "nfs", false)
        val left = PaneViewModel(app).apply { io = dispatcher }
        val right = PaneViewModel(app).apply { io = dispatcher; paneIndex = 1 }
        left.bootstrap()
        right.bootstrap()
        assertFalse(left.state.value.rows.any { it.key == "g:lan" })
        assertTrue(right.state.value.rows.any { it.key == "g:lan" })
        assertTrue(RootItems.all.any { it.id == "nfs" })
        assertFalse(left.state.value.rows.any { it.key == "g:nfs" })
        assertTrue(right.state.value.rows.any { it.key == "g:nfs" })
        assertTrue(RootItems.all.filter { it.id !in setOf("private", "lan", "nfs") }.all { Prefs.rootItemVisible(app, 0, it.id) })
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
            val row = dialog.findViewById<android.view.ViewGroup>(com.twig.app.R.id.items)!!
                .findViewWithTag<android.view.View>("private")
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
