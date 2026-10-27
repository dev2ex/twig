package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.Favorite
import com.twig.app.SavedConnection
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
@Config(sdk = [34])
class PaneDirectoryRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var fs: FakeFileSystem
    private lateinit var conn: SavedConnection
    private val dirs = mutableMapOf("/" to listOf("dir"), "/dir" to listOf("first.txt"))
    private val files = mutableMapOf("/dir/first.txt" to "first")
    private val failing = mutableSetOf<String>()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        conn = SavedConnection("sftp", "refresh.test", user = "test")
        ConnectionStore.save(app, conn)
        fs = FakeFileSystem(Connections.schemeOf(conn), dirs, files, failing = failing)
        FsRegistry.register(fs)
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After fun tearDown() {
        FsRegistry.unregister(fs.scheme)
        Dispatchers.resetMain()
    }

    private fun dirRow() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
        .single { it.file.path == "/dir" && it.file.scheme == fs.scheme }
    private fun serverRow() = vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>()
        .single { it.conn.label() == conn.label() }
    private fun names() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
        .filter { it.file.scheme == fs.scheme && it.file.parentPath == "/dir" }.map { it.file.name }
    private fun replaceFile() {
        dirs["/dir"] = listOf("second.txt")
        files.remove("/dir/first.txt")
        files["/dir/second.txt"] = "second"
    }

    @Test fun `removed current directory selects its visible parent instead of a stale transfer target`() = runTest(dispatcher) {
        vm.revealPath(XFile(fs.scheme, "/dir", true)); advanceUntilIdle()
        val parent = serverRow()
        dirs["/"] = emptyList()
        dirs.remove("/dir")
        vm.refresh(); advanceUntilIdle()
        assertEquals(fs.root(), vm.currentDir)
        assertEquals(parent.key, vm.state.value.currentKey)
        assertEquals(vm.currentDir, vm.state.value.currentDir)
        assertTrue(vm.state.value.rows.any { it.key == vm.state.value.currentKey })
    }

    @Test fun `removing a selected descendant falls back to the nearest surviving ancestor`() = runTest(dispatcher) {
        dirs["/dir"] = listOf("nested")
        dirs["/dir/nested"] = emptyList()
        vm.revealPath(XFile(fs.scheme, "/dir/nested", true)); advanceUntilIdle()
        val parent = dirRow()
        dirs["/dir"] = emptyList()
        dirs.remove("/dir/nested")
        vm.refresh(); advanceUntilIdle()
        assertEquals(parent.file, vm.currentDir)
        assertEquals(parent.key, vm.state.value.currentKey)
        // Removing that parent as well must not leave the previous fallback as a target.
        dirs["/"] = emptyList()
        dirs.remove("/dir")
        vm.refresh(); advanceUntilIdle()
        assertEquals(fs.root(), vm.currentDir)
        assertEquals(serverRow().key, vm.state.value.currentKey)
    }

    @Test fun `removing a whole ancestor subtree selects its surviving parent directly`() = runTest(dispatcher) {
        dirs["/dir"] = listOf("nested")
        dirs["/dir/nested"] = emptyList()
        vm.revealPath(XFile(fs.scheme, "/dir/nested", true)); advanceUntilIdle()
        dirs["/"] = emptyList()
        dirs.remove("/dir")
        dirs.remove("/dir/nested")
        vm.refresh(); advanceUntilIdle()
        assertEquals(fs.root(), vm.currentDir)
        assertEquals(serverRow().key, vm.state.value.currentKey)
    }

    @Test fun `deleting a favorite child selects the favorite root rather than another tree alias`() = runTest(dispatcher) {
        dirs["/dir"] = listOf("nested")
        dirs["/dir/nested"] = emptyList()
        val favorite = Favorite("Dir", "conn", "/dir", connLabel = conn.label())
        vm.addFavorite(favorite)
        val parent = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>()
            .single { it.fav.id == favorite.id }
        vm.toggleFavorite(parent, null) { ok, _ -> assertTrue(ok) }; advanceUntilIdle()
        val child = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .single { it.file.scheme == fs.scheme && it.file.path == "/dir/nested" }
        vm.toggle(child); advanceUntilIdle()
        dirs["/dir"] = emptyList()
        dirs.remove("/dir/nested")
        vm.refresh(); advanceUntilIdle()
        assertEquals(XFile(fs.scheme, "/dir", true), vm.currentDir)
        assertEquals(parent.key, vm.state.value.currentKey)
    }

    @Test fun `failed ancestor listing does not imply the selected directory was removed`() = runTest(dispatcher) {
        vm.revealPath(XFile(fs.scheme, "/dir", true)); advanceUntilIdle()
        val before = vm.currentDir
        val key = vm.state.value.currentKey
        failing.add("/")
        vm.refresh(); advanceUntilIdle()
        assertEquals(before, vm.currentDir)
        assertEquals(key, vm.state.value.currentKey)
    }

    @Test fun `refresh does not replace a new selection made while its listing was pending`() = runTest(dispatcher) {
        dirs["/"] = listOf("dir", "other")
        dirs["/other"] = emptyList()
        vm.revealPath(XFile(fs.scheme, "/dir", true)); advanceUntilIdle()
        dirs["/"] = listOf("other")
        dirs.remove("/dir")
        vm.refresh()
        val other = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .single { it.file.scheme == fs.scheme && it.file.path == "/other" }
        vm.toggle(other)
        advanceUntilIdle()
        assertEquals(other.file, vm.currentDir)
        assertEquals(other.key, vm.state.value.currentKey)
    }

    @Test fun `cached remote directory is re-read on expansion`() = runTest(dispatcher) {
        vm.revealPath(XFile(fs.scheme, "/dir", true)); advanceUntilIdle()
        vm.toggle(dirRow()); advanceUntilIdle() // collapse
        replaceFile()
        val reads = fs.listed.count { it == "/dir" }
        vm.toggle(dirRow()); advanceUntilIdle()
        assertEquals(reads + 1, fs.listed.count { it == "/dir" })
        assertEquals(listOf("second.txt"), names())
    }

    @Test fun `cached server root is re-read on expansion`() = runTest(dispatcher) {
        vm.revealPath(fs.root()); advanceUntilIdle()
        vm.toggle(serverRow()); advanceUntilIdle()
        dirs["/"] = listOf("new")
        dirs["/new"] = emptyList()
        vm.toggle(serverRow()); advanceUntilIdle()
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.path == "/new" })
        assertFalse(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.path == "/dir" })
    }

    @Test fun `cached favorite directory is re-read on expansion`() = runTest(dispatcher) {
        val fav = Favorite("Dir", "conn", "/dir", connLabel = conn.label())
        vm.addFavorite(fav)
        fun row() = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().single { it.fav.id == fav.id }
        vm.toggleFavorite(row(), null) { ok, _ -> assertTrue(ok) }; advanceUntilIdle()
        vm.toggleFavorite(row(), null) { ok, _ -> assertTrue(ok) }; advanceUntilIdle()
        replaceFile()
        vm.toggleFavorite(row(), null) { ok, _ -> assertTrue(ok) }; advanceUntilIdle()
        assertEquals(listOf("second.txt"), names())
    }

    @Test fun `resume re-reads only the current remote directory and preserves navigation`() = runTest(dispatcher) {
        vm.revealPath(XFile(fs.scheme, "/dir", true)); advanceUntilIdle()
        val dir = vm.currentDir
        val key = vm.state.value.currentKey
        fs.listed.clear()
        replaceFile()
        vm.refreshOnResume(); advanceUntilIdle()
        assertEquals(listOf("/dir"), fs.listed)
        assertEquals(listOf("second.txt"), names())
        assertEquals(dir, vm.currentDir)
        assertEquals(key, vm.state.value.currentKey)
    }

    @Test fun `unchanged resume publishes no new state and failed listing preserves visible files`() = runTest(dispatcher) {
        vm.revealPath(XFile(fs.scheme, "/dir", true)); advanceUntilIdle()
        val before = vm.state.value
        val reads = fs.listed.size
        vm.refreshOnResume(); advanceUntilIdle()
        assertTrue(fs.listed.size > reads)
        assertSame("unchanged rows must not trigger a render", before, vm.state.value)
        failing.add("/dir")
        vm.refreshOnResume(); advanceUntilIdle()
        assertSame("failure is not an empty directory", before, vm.state.value)
        assertEquals(listOf("first.txt"), names())
    }

    @Test fun `resume detects metadata changes even when names do not change`() = runTest(dispatcher) {
        vm.revealPath(XFile(fs.scheme, "/dir", true)); advanceUntilIdle()
        files["/dir/first.txt"] = "larger file contents"
        vm.refreshOnResume(); advanceUntilIdle()
        val file = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .single { it.file.path == "/dir/first.txt" }.file
        assertEquals(files.getValue(file.path).length.toLong(), file.size)
    }

    @Test fun `resume refreshes a collapsed current directory without reopening it`() = runTest(dispatcher) {
        vm.revealPath(XFile(fs.scheme, "/dir", true)); advanceUntilIdle()
        vm.toggle(dirRow()); advanceUntilIdle()
        val before = vm.state.value
        replaceFile()
        fs.listed.clear()
        vm.refreshOnResume(); advanceUntilIdle()
        assertEquals(listOf("/dir"), fs.listed)
        assertSame(before, vm.state.value)
        assertFalse(dirRow().expanded)
        vm.toggle(dirRow())
        assertEquals("resume updated the cache before any expansion request completed", listOf("second.txt"), names())
        advanceUntilIdle()
    }
}
