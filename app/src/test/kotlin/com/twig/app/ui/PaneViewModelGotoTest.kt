package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.SavedConnection
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * "Go to path" ([PaneViewModel.revealUnder]): type a path under one root row and land
 * on it, expanding every level on the way.
 *
 * The case worth the whole test file is `descends a backend whose path is not a path`:
 * SAF document URIs and media-server item ids cannot be joined into a target path, so
 * this feature descends **by name**. Build the fixture with slash-shaped paths and that
 * distinction disappears — the test would pass on an implementation that just joins
 * strings and hands the result to [PaneViewModel.revealPath].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelGotoTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var ext: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        ext = Environment.getExternalStorageDirectory()
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun rowKeys() = vm.state.value.rows.map { it.key }

    private fun names() =
        vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().map { it.file.name }

    /** The "Internal storage" row's key — the root these local cases jump from. */
    private fun storageRoot() = "f:file:${ext.path}" to XFile("file", ext.path, isDir = true)

    // ---- local roots ----

    @Test
    fun `a relative path expands every level on the way`() = runTest(dispatcher) {
        File(ext, "docs/notes").apply { mkdirs() }
        File(ext, "docs/notes/todo.txt").writeText("x")
        val (key, root) = storageRoot()

        vm.revealUnder(key, root, "docs/notes")
        advanceUntilIdle()

        assertTrue("f:file:${ext.path}/docs" in rowKeys())
        assertEquals("f:file:${ext.path}/docs/notes", vm.state.value.currentKey)
        assertTrue("the target's own children have to be listed", "todo.txt" in names())
    }

    /** Pasting a full path is the common way to use this, so the root prefix is cut off. */
    @Test
    fun `an absolute path under the root is accepted as pasted`() = runTest(dispatcher) {
        File(ext, "pasted/deep").apply { mkdirs() }
        val (key, root) = storageRoot()

        vm.revealUnder(key, root, "${ext.path}/pasted/deep")
        advanceUntilIdle()

        assertEquals("f:file:${ext.path}/pasted/deep", vm.state.value.currentKey)
        assertNull(vm.state.value.error)
    }

    /** A trailing file: highlight stays on its folder, the list scrolls to the file's row. */
    @Test
    fun `a trailing file scrolls to its row and highlights its folder`() = runTest(dispatcher) {
        val dir = File(ext, "songs").apply { mkdirs() }
        val f = File(dir, "track.mp3").apply { writeText("m") }
        val (key, root) = storageRoot()

        vm.revealUnder(key, root, "songs/track.mp3")
        advanceUntilIdle()

        assertEquals("f:file:${dir.path}", vm.state.value.currentKey)
        assertEquals("f:file:${f.path}", vm.state.value.scrollKey)
    }

    /** The error has to name the segment that failed — it is the only clue the user gets. */
    @Test
    fun `a missing segment reports which one`() = runTest(dispatcher) {
        File(ext, "here").apply { mkdirs() }
        val (key, root) = storageRoot()

        vm.revealUnder(key, root, "here/nope/deeper")
        advanceUntilIdle()

        val err = vm.state.value.error
        assertNotNull("a missing path must be reported", err)
        assertTrue("should name the failing segment, was: $err", err!!.contains("nope"))
    }

    /** A file in the middle of the path is not a folder to descend into. */
    @Test
    fun `a file in the middle of the path is rejected`() = runTest(dispatcher) {
        File(ext, "mid").apply { mkdirs() }
        File(ext, "mid/file.txt").writeText("x")
        val (key, root) = storageRoot()

        vm.revealUnder(key, root, "mid/file.txt/more")
        advanceUntilIdle()

        assertNotNull(vm.state.value.error)
    }

    // ---- roots whose path is not a path ----

    /**
     * SAF grants (document URIs) and media servers (item ids) both have paths that
     * cannot be joined from names. Descending by name is what makes those roots work,
     * and this fixture only resolves that way.
     */
    @Test
    fun `descends a backend whose path is not a path`() = runTest(dispatcher) {
        val conn = SavedConnection(type = "ftp", host = "opaque-host", port = 21, user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(
            OpaqueFs(
                scheme,
                children = mapOf(
                    "/" to listOf("id1" to "Shows"),
                    "id1" to listOf("id1%2Fid2" to "Season 1"),
                    "id1%2Fid2" to listOf("id1%2Fid2%2Fid3" to "S01E01.mkv"),
                ),
            ),
        )

        vm.revealUnder("s:${conn.label()}", null, "Shows/Season 1")
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertEquals("f:$scheme:id1%2Fid2", vm.state.value.currentKey)
        assertTrue("S01E01.mkv" in names())
    }

    /** A server never opened this session still jumps: it is connected on the way down. */
    @Test
    fun `connects a server that was never opened this session`() = runTest(dispatcher) {
        val conn = SavedConnection(type = "ftp", host = "goto-host", port = 21, user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(
            FakeFileSystem(
                scheme,
                dirs = mapOf(
                    "/" to listOf("pub"),
                    "/pub" to listOf("docs"),
                    "/pub/docs" to listOf("readme.txt"),
                ),
                files = mapOf("/pub/docs/readme.txt" to "hi"),
            ),
        )

        vm.revealUnder("s:${conn.label()}", null, "/pub/docs")
        advanceUntilIdle()

        val keys = rowKeys()
        assertTrue("the group row has to stay open", "g:ftp" in keys)
        assertTrue("s:${conn.label()}" in keys)
        assertEquals("f:$scheme:/pub/docs", vm.state.value.currentKey)
        assertTrue("readme.txt" in names())
    }

    /** Nothing typed after the root: the root itself is opened, highlight on its own row. */
    @Test
    fun `an empty path just opens the root`() = runTest(dispatcher) {
        File(ext, "plain").apply { mkdirs() }
        val (key, root) = storageRoot()

        vm.revealUnder(key, root, "/")
        advanceUntilIdle()

        assertEquals(key, vm.state.value.currentKey)
        assertTrue("plain" in names())
    }
}

/**
 * A backend whose `path` is an opaque id — the shape of SAF and of a media server,
 * where a child's path cannot be derived from its parent's path plus its name.
 */
private class OpaqueFs(
    override val scheme: String,
    /** parent path → its children as (path, display name); a leaf has no entry. */
    private val children: Map<String, List<Pair<String, String>>>,
) : FileSystem {

    override val displayName: String = "Opaque($scheme)"

    override fun root(): XFile = XFile(scheme, "/", isDir = true)

    override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true)

    override fun list(dir: XFile): List<XFile> =
        (children[dir.path] ?: throw FsException("no such directory: ${dir.path}"))
            .map { (p, n) -> XFile(scheme, p, isDir = children.containsKey(p), displayName = n) }

    override fun openInput(file: XFile): InputStream = throw FsException("read-only")
    override fun exists(file: XFile): Boolean = children.containsKey(file.path)
    override fun writable(): Boolean = false
    override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("read-only")
    override fun mkdir(parent: XFile, name: String): XFile = throw FsException("read-only")
    override fun delete(file: XFile) = throw FsException("read-only")
    override fun rename(file: XFile, newName: String): XFile = throw FsException("read-only")
}
