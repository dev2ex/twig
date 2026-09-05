package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.Favorite
import com.twig.app.FavoritesStore
import com.twig.app.HistoryEntry
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `PaneViewModel`'s **locate (revealPath) and favorite resolution** path -- the two
 * remaining ones outside the previous batch (restore/expand), and exactly what plan C2-b
 * touches.
 *
 * The network branch fakes a server via [FakeFileSystem]: registering it ahead of time
 * under the target scheme means `Connections.ensure`'s "reuse if already registered" just
 * returns it, never `new`-ing a real FtpFileSystem, so these cases touch no network and run
 * fast.
 *
 * **Not covered**: the favorite's restic branch. That one needs a real encrypted repository
 * (scrypt derivation + decrypted config) to exercise, which costs far more than it's worth;
 * `ResticRepo` itself already has tests in `:fs-restic`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelRevealTest {

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

    /**
     * Builds a "server": saved into ConnectionStore, with the fake fs registered under its
     * deterministic scheme. Give [host] a different value per test case -- `FsRegistry` is
     * a process-wide singleton shared by every case in this test class, and colliding
     * schemes interfere with each other.
     */
    private fun fakeServer(
        host: String,
        dirs: Map<String, List<String>>,
        files: Map<String, String> = emptyMap(),
    ): Pair<SavedConnection, FakeFileSystem> {
        val conn = SavedConnection(type = "ftp", host = host, port = 21, user = "u")
        ConnectionStore.save(app, conn)
        val fs = FakeFileSystem(Connections.schemeOf(conn), dirs, files)
        FsRegistry.register(fs)
        return conn to fs
    }

    // ---- revealPath: local ----

    @Test
    fun `locating a deep local directory expands every level along the way`() = runTest(dispatcher) {
        val deep = File(ext, "a/b/c").apply { mkdirs() }
        File(deep, "target.txt").writeText("x")

        vm.revealPath(XFile("file", deep.path, isDir = true))
        advanceUntilIdle()

        val keys = rowKeys()
        for (p in listOf("${ext.path}/a", "${ext.path}/a/b", "${ext.path}/a/b/c")) {
            assertTrue("$p should be in the tree", "f:file:$p" in keys)
        }
        assertEquals("f:file:${deep.path}", vm.state.value.currentKey)
        assertTrue("target.txt" in vm.state.value.rows
            .filterIsInstance<PaneViewModel.FileNode>().map { it.file.name })
    }

    /** Accordion: after locating, other branches not on this chain should be collapsed. */
    @Test
    fun `locating collapses other branches not on the chain`() = runTest(dispatcher) {
        val other = File(ext, "side-branch").apply { mkdirs() }
        File(other, "o.txt").writeText("o")
        val target = File(ext, "target/inner").apply { mkdirs() }

        vm.bootstrap(listOf("file\t${ext.path}", "file\t${other.path}"))
        advanceUntilIdle()
        assertTrue("f:file:${other.path}" in rowKeys())

        vm.revealPath(XFile("file", target.path, isDir = true))
        advanceUntilIdle()

        assertTrue("f:file:${target.path}" in rowKeys())
        assertFalse("the side branch's children should no longer be spread out", "o.txt" in vm.state.value.rows
            .filterIsInstance<PaneViewModel.FileNode>().map { it.file.name })
    }

    /** When [focus] is given, the scroll anchor points at that file while the highlight still frames the directory itself. */
    @Test
    fun `locating with focus anchors scroll on the file while the highlight stays on the directory`() = runTest(dispatcher) {
        val d = File(ext, "with-focus").apply { mkdirs() }
        val f = File(d, "song.mp3").apply { writeText("m") }

        vm.revealPath(
            XFile("file", d.path, isDir = true),
            focus = XFile("file", f.path, isDir = false),
        )
        advanceUntilIdle()

        assertEquals("f:file:${d.path}", vm.state.value.currentKey)
        assertEquals("f:file:${f.path}", vm.state.value.scrollKey)
    }

    // ---- revealPath: network ----

    /**
     * When this server has not been expanded yet this session, revealPath must be able to
     * **look up the saved connection by scheme** and connect it on the spot, rather than
     * simply erroring out. Both the group row and the server row must expand, as must every
     * level on the chain.
     */
    @Test
    fun `locating a directory on an unconnected server auto-connects and expands level by level`() = runTest(dispatcher) {
        val (conn, fs) = fakeServer(
            "reveal-host",
            dirs = mapOf(
                "/" to listOf("pub"),
                "/pub" to listOf("docs"),
                "/pub/docs" to listOf("readme.txt"),
            ),
            files = mapOf("/pub/docs/readme.txt" to "hi"),
        )
        val scheme = Connections.schemeOf(conn)

        vm.revealPath(XFile(scheme, "/pub/docs", isDir = true))
        advanceUntilIdle()

        val keys = rowKeys()
        assertTrue("the FTP group should expand", "g:ftp" in keys)
        assertTrue("the server row should expand", "s:${conn.label()}" in keys)
        assertTrue("f:$scheme:/pub" in keys)
        assertTrue("f:$scheme:/pub/docs" in keys)
        assertEquals("f:$scheme:/pub/docs", vm.state.value.currentKey)
        assertTrue("readme.txt" in vm.state.value.rows
            .filterIsInstance<PaneViewModel.FileNode>().map { it.file.name })
        // really listed level by level, not jumped straight to the target
        assertTrue(fs.listed.containsAll(listOf("/", "/pub", "/pub/docs")))
    }

    /** Must not crash when the connection has been deleted, nor leave half-finished state. */
    @Test
    fun `locating a directory on a deleted connection only errors, does not crash`() = runTest(dispatcher) {
        vm.revealPath(XFile("ftpdeadbeef", "/x", isDir = true))
        advanceUntilIdle()
        assertNotNull("should surface an error", vm.state.value.error)
    }

    // ---- recent locations ----

    @Test
    fun `a recent location can jump back to a directory on a server`() = runTest(dispatcher) {
        val (conn, _) = fakeServer(
            "history-host",
            dirs = mapOf("/" to listOf("data"), "/data" to listOf()),
        )
        val scheme = Connections.schemeOf(conn)

        assertTrue(vm.revealHistory(HistoryEntry("dir", "/data", conn.label())))
        advanceUntilIdle()

        assertEquals("f:$scheme:/data", vm.state.value.currentKey)
    }

    @Test
    fun `a recent location pointing at a deleted connection returns false`() = runTest(dispatcher) {
        assertFalse(vm.revealHistory(HistoryEntry("dir", "/x", "ftp://nobody@nowhere:21")))
    }

    // ---- favorites ----

    @Test
    fun `expanding a favorite that points at a server directory connects and lists its children`() = runTest(dispatcher) {
        val (conn, _) = fakeServer(
            "fav-host",
            dirs = mapOf("/" to listOf("share"), "/share" to listOf("f.txt")),
            files = mapOf("/share/f.txt" to "c"),
        )
        val fav = Favorite(label = "remote", kind = "conn", path = "/share", connLabel = conn.label())
        FavoritesStore.add(app, fav)

        vm.bootstrap(listOf("group\tfav"))
        advanceUntilIdle()

        val node = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>()
            .first { it.fav.id == fav.id }
        var ok: Boolean? = null
        vm.toggleFavorite(node, password = null) { r, _ -> ok = r }
        advanceUntilIdle()

        assertEquals(true, ok)
        assertTrue("f.txt" in vm.state.value.rows
            .filterIsInstance<PaneViewModel.FileNode>().map { it.file.name })
        // the favorite row itself is that directory, the current target should sync to it
        assertEquals("/share", vm.state.value.currentDir?.path)
    }

    @Test
    fun `errors instead of crashing when the favorite's connection has been deleted`() = runTest(dispatcher) {
        val fav = Favorite(label = "orphan", kind = "conn", path = "/x", connLabel = "ftp://gone@gone:21")
        FavoritesStore.add(app, fav)

        vm.bootstrap(listOf("group\tfav"))
        advanceUntilIdle()

        val node = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>()
            .first { it.fav.id == fav.id }
        var ok: Boolean? = null
        var err: String? = null
        vm.toggleFavorite(node, password = null) { r, e -> ok = r; err = e }
        advanceUntilIdle()

        assertEquals(false, ok)
        assertNotNull(err)
    }

    // ---- forgetServer ----

    /**
     * ★ Regression (an existing bug fixed in the third batch on 2026-08-03): after editing
     * a server's configuration, its scheme must be unregistered from FsRegistry. The scheme
     * is generated deterministically from the connection label, so changing only the
     * password leaves the label -- and thus the scheme -- unchanged; without unregistering,
     * `Connections.ensure`'s "reuse if already registered" keeps handing back the instance
     * **built from the old config** -- the password change never takes effect until the app
     * restarts.
     */
    @Test
    fun `forgetServer unregisters the scheme from the registry`() = runTest(dispatcher) {
        val (conn, _) = fakeServer("forget-host", dirs = mapOf("/" to listOf()))
        val scheme = Connections.schemeOf(conn)

        vm.revealPath(XFile(scheme, "/", isDir = true))
        advanceUntilIdle()
        assertTrue("should be registered after expanding", FsRegistry.all().any { it.scheme == scheme })

        vm.forgetServer(conn.label())
        advanceUntilIdle()

        assertFalse(
            "must not remain in the registry after unregistering, or the config change never takes effect",
            FsRegistry.all().any { it.scheme == scheme },
        )
    }
}
