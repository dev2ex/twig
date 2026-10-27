package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RootDirectoryBrowsingTest {
    private val dispatcher = StandardTestDispatcher()
    private val root = XFile("file", "/", true)
    private val dirs = mutableMapOf(
        "/" to emptyList<String>(), "/system" to listOf("etc", "bin"),
        "/system/etc" to listOf("hosts"), "/system/bin" to emptyList(), "/custom" to emptyList(),
    )
    private val failing = mutableSetOf("/", "/system/bin")
    private val metadata = mutableMapOf(
        "/system" to XFile("file", "/system", true, canWrite = false),
        "/vendor" to XFile("file", "/vendor", true, canRead = false),
        "/proc" to XFile("file", "/proc", false),
    )
    private val probed = mutableListOf<String>()
    private lateinit var fs: FileSystem
    private lateinit var vm: PaneViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val backend = FakeFileSystem("file", dirs, mapOf("/system/etc/hosts" to "127.0.0.1 localhost"), failing = failing)
        fs = object : FileSystem by backend {
            override fun stat(path: String): XFile? {
                probed += path
                return metadata[path]
            }
        }
        FsRegistry.register(fs)
        vm = PaneViewModel(ApplicationProvider.getApplicationContext()).apply {
            io = dispatcher
            lockRoot = root
        }
        vm.refreshTree()
    }

    @After fun cleanup() {
        FsRegistry.unregister("file")
        Dispatchers.resetMain()
    }

    private fun row(path: String) = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
        .single { it.file.path == path }
    private fun paths() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().map { it.file.path }

    @Test fun `local observer refresh selects the parent when the current directory disappears`() = runTest(dispatcher) {
        failing.remove("/")
        dirs["/"] = listOf("custom")
        dirs["/custom"] = listOf("nested")
        dirs["/custom/nested"] = emptyList()
        vm.toggle(row("/")); advanceUntilIdle()
        vm.toggle(row("/custom")); advanceUntilIdle()
        vm.toggle(row("/custom/nested")); advanceUntilIdle()
        val parent = row("/custom")
        dirs["/custom"] = emptyList()
        dirs.remove("/custom/nested")
        vm.refreshLocal(); advanceUntilIdle()
        assertEquals(parent.file, vm.currentDir)
        assertEquals(parent.key, vm.state.value.currentKey)
        assertEquals(vm.currentDir, vm.state.value.currentDir)
    }

    @Test fun `unreadable root exposes real readable directories and their contents`() = runTest(dispatcher) {
        vm.toggle(row("/")); advanceUntilIdle()
        assertEquals(listOf("/", "/system"), paths())
        assertFalse(row("/system").file.canWrite)
        assertNull(vm.state.value.error)
        vm.toggle(row("/system")); advanceUntilIdle()
        vm.toggle(row("/system/etc")); advanceUntilIdle()
        assertEquals("127.0.0.1 localhost", fs.openInput(row("/system/etc/hosts").file).bufferedReader().use { it.readText() })
        // Navigation shortcuts must never turn an incomplete root into a successful filesystem listing.
        try { fs.list(root); fail("the real root listing must still fail") } catch (_: FsException) { }
    }

    @Test fun `readable root retains its complete listing without probing known paths`() = runTest(dispatcher) {
        failing.remove("/")
        dirs["/"] = listOf("custom")
        vm.toggle(row("/")); advanceUntilIdle()
        assertEquals(listOf("/", "/custom"), paths())
        // The app-owned path may be checked for its navigation hint, but no
        // generic root shortcuts are probed when the root has a real listing.
        val own = ApplicationProvider.getApplicationContext<Application>().applicationInfo.dataDir
        assertTrue(probed.all { it == own })
    }

    @Test fun `empty root listing also permits direct navigation to known readable directories`() = runTest(dispatcher) {
        failing.remove("/")
        vm.toggle(row("/")); advanceUntilIdle()
        assertEquals(listOf("/", "/system"), paths())
    }

    @Test fun `unreadable child does not receive root shortcuts`() = runTest(dispatcher) {
        vm.toggle(row("/")); advanceUntilIdle()
        vm.toggle(row("/system")); advanceUntilIdle()
        probed.clear()
        vm.toggle(row("/system/bin")); advanceUntilIdle()
        assertEquals("listing failed: /system/bin", vm.state.value.error)
        assertTrue(probed.isEmpty())
        assertFalse(row("/system/bin").expanded)
    }

    @Test fun `root failure remains an error when no readable known directories exist`() = runTest(dispatcher) {
        metadata.clear()
        vm.toggle(row("/")); advanceUntilIdle()
        assertEquals(listOf("/"), paths())
        assertEquals("listing failed: /", vm.state.value.error)
        assertFalse(row("/").expanded)
    }
}
