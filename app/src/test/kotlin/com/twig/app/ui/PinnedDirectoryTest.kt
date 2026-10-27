package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PinnedDirectoryTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: PaneViewModel
    private lateinit var root: File
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        FsRegistry.register(LocalFileSystem())
        root = File(app.cacheDir, "pinned-test").apply { mkdirs() }
        File(root, "child.txt").writeText("test")
        vm = PaneViewModel(app).apply { io = dispatcher }
    }
    @After fun cleanup() { Dispatchers.resetMain(); root.deleteRecursively() }

    @Test fun `directory is a separate root with distinct row keys and Back restores the old target`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${root.path}"))
        advanceUntilIdle()
        val previous = vm.state.value.currentDir
        val focus = XFile("file", File(root, "child.txt").path, isDir = false)
        vm.mountPinnedDirectory(XFile("file", root.path, isDir = true), "pinned directory", focus)
        advanceUntilIdle()
        val pinned = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first()
        assertEquals(0, pinned.depth)
        assertEquals(root.path, pinned.file.path)
        assertEquals(root.path, vm.state.value.currentDir?.path)
        assertTrue(pinned.key.startsWith("x:"))
        assertEquals("x:" + PaneViewModel.fileKey(focus), vm.state.value.scrollKey)
        assertEquals(vm.state.value.rows.size, vm.state.value.rows.map { it.key }.distinct().size)
        assertTrue(vm.unmountPinnedDirectory())
        assertEquals(previous, vm.state.value.currentDir)
        assertFalse(vm.unmountPinnedDirectory())
    }

    @Test fun `Back before the listing completes does not resurrect the temporary root`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${root.path}"))
        advanceUntilIdle()
        val previous = vm.state.value.currentDir
        vm.mountPinnedDirectory(XFile("file", root.path, isDir = true), "pinned directory")
        assertTrue(vm.unmountPinnedDirectory())
        advanceUntilIdle()
        assertEquals(previous, vm.state.value.currentDir)
        assertFalse(vm.state.value.rows.any { it.key.startsWith("x:") })
    }
}
