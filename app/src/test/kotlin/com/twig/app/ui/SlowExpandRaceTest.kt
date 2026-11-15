package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.Favorite
import com.twig.app.Prefs
import com.twig.app.SavedConnection
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/**
 * ★ "Open a slow directory, get impatient and open another one" -- the one clicked later
 * must stay expanded, and the earlier slow one must give up its own expansion once it
 * finally lands.
 *
 * Expansion is asynchronous, and landing calls `accordionExpand`, which only keeps one
 * expansion chain -- so the slow one landing collapses the directory the user just opened,
 * and what shows on screen is "the directory I just opened closed itself, and the earlier
 * one popped back up".
 *
 * Reproducing this requires **the earlier click to land later**: on a plain
 * `StandardTestDispatcher` two clicks run to completion in order, so the earlier one lands
 * first -- neither steps on the other, and the test stays green on the buggy version. So
 * the slow directory's click gets its own [HoldDispatcher], holding its IO back until the
 * quick one has run all the way through, and only then releasing it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlowExpandRaceTest {

    /** Holds back whatever is dispatched onto it, running it in place only when [release] is called. */
    private class HoldDispatcher : CoroutineDispatcher() {
        private val held = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { held += block }
        fun release() { while (held.isNotEmpty()) held.removeFirst().run() }
    }

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private val dispatcher = StandardTestDispatcher()
    private val hold = HoldDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Builds a server: two directories at the root, each with one file. */
    private fun server(host: String): XFile {
        val conn = SavedConnection(type = "sftp", host = host, user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(
            FakeFileSystem(
                scheme,
                dirs = mapOf(
                    "/" to listOf("slow", "quick"),
                    "/slow" to listOf("s.txt"),
                    "/quick" to listOf("q.txt"),
                ),
                files = mapOf("/slow/s.txt" to "x", "/quick/q.txt" to "x"),
            ),
        )
        return XFile(scheme, "/", isDir = true)
    }

    private fun row(name: String) =
        vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.name == name }


    private fun failingFavorite(): PaneViewModel.FavoriteNode {
        val conn = SavedConnection(type = "sftp", host = "favorite-failure.test", user = "u")
        ConnectionStore.save(app, conn)
        FsRegistry.register(FakeFileSystem(
            Connections.schemeOf(conn), dirs = mapOf("/broken" to emptyList()),
            failing = setOf("/broken"),
        ))
        val fav = Favorite("Broken", "conn", "/broken", connLabel = conn.label())
        vm.addFavorite(fav)
        return vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().single { it.fav.id == fav.id }
    }

    @Test
    fun `an active favorite failure still reports its actual error`() = runTest(dispatcher) {
        val favorite = failingFavorite()
        val errors = mutableListOf<String?>()
        vm.toggleFavorite(favorite, null) { ok, err -> if (!ok) errors += err }
        advanceUntilIdle()

        org.junit.Assert.assertEquals(listOf("listing failed: /broken"), errors)
    }

    @Test
    fun `a favorite failure is silent after opening another directory`() = runTest(dispatcher) {
        vm.revealPath(server("favorite-directory.test"))
        advanceUntilIdle()
        val favorite = failingFavorite()
        val errors = mutableListOf<String?>()
        vm.io = hold
        vm.toggleFavorite(favorite, null) { ok, err -> if (!ok) errors += err }
        advanceUntilIdle()

        vm.io = dispatcher
        vm.toggle(row("quick"))
        advanceUntilIdle()
        hold.release()
        advanceUntilIdle()

        assertTrue("abandoned favorite must not report a failure", errors.isEmpty())
        assertTrue("the later directory stays expanded", row("quick").expanded)
    }

    @Test
    fun `a favorite failure is silent after opening another favorite`() = runTest(dispatcher) {
        val root = server("favorite-switch.test")
        val conn = ConnectionStore.all(app).single { Connections.schemeOf(it) == root.scheme }
        val quick = Favorite("Quick", "conn", "/quick", connLabel = conn.label())
        vm.addFavorite(quick)
        val favorite = failingFavorite()
        val errors = mutableListOf<String?>()
        vm.io = hold
        vm.toggleFavorite(favorite, null) { ok, err -> if (!ok) errors += err }
        advanceUntilIdle()

        vm.io = dispatcher
        val quickRow = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().single { it.fav.id == quick.id }
        vm.toggleFavorite(quickRow, null) { ok, err -> if (!ok) errors += err }
        advanceUntilIdle()
        hold.release()
        advanceUntilIdle()

        assertTrue("abandoned favorite must not report a failure", errors.isEmpty())
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().single { it.fav.id == quick.id }.expanded)
    }

    /**
     * ★ An abandoned row must **stop spinning immediately**. The request cannot be
     * canceled, so it is still in flight -- but the user is no longer looking at it, and a
     * spinner still turning with the server row still saying "connecting" only makes it
     * look like the UI has frozen (most obvious on a slow-connecting source like SFTP).
     * "Still in flight" is still useful, though: clicking the row again merely reclaims it,
     * without firing a second connection.
     */
    @Test
    fun `a slow-connecting server stops spinning immediately once abandoned, and clicking it again does not reconnect`() = runTest(dispatcher) {
        val conn = SavedConnection(type = "sftp", host = "slowssh.test", user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        val fs = FakeFileSystem(scheme, dirs = mapOf("/" to listOf("a.txt")), files = mapOf("/a.txt" to "x"))
        FsRegistry.register(fs)
        val root = server("other.test") // another already-connected server, used as "opening something else"

        vm.revealPath(root)
        advanceUntilIdle()

        val serverRow = { vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>().single { it.conn.label() == conn.label() } }
        vm.io = hold
        vm.toggle(serverRow())
        advanceUntilIdle()
        assertTrue("precondition: connecting, spinner turning", serverRow().connecting)

        vm.io = dispatcher
        vm.toggle(row("quick"))
        advanceUntilIdle()
        assertFalse("must stop spinning and stop saying \"connecting\" immediately once abandoned", serverRow().connecting)

        // click the row again: spinner comes back, but must not fire another connection
        val before = fs.listed.size
        vm.toggle(serverRow())
        advanceUntilIdle()
        assertTrue("clicking it again should start the spinner again", serverRow().connecting)
        org.junit.Assert.assertEquals("the connection is still in flight, should not connect again", before, fs.listed.size)

        // when that connection lands, it is claimed by this row, so it expands as usual
        hold.release()
        advanceUntilIdle()
        assertTrue("having reclaimed it, connecting should expand it", serverRow().expanded)
        assertFalse("spinner stops", serverRow().connecting)
    }

    /**
     * ★ After being abandoned, **it must not expand itself even once connected**. Skipping
     * `accordionExpand` only means "don't steal that chain", but the `expanded.add` line in
     * `applyRestored` bypasses it -- that server would expand itself into a second,
     * unrelated open path in the tree alongside the directory the user already opened.
     */
    @Test
    fun `an abandoned server must not expand itself once connected`() = runTest(dispatcher) {
        val conn = SavedConnection(type = "sftp", host = "slowssh2.test", user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(FakeFileSystem(scheme, dirs = mapOf("/" to listOf("a.txt")), files = mapOf("/a.txt" to "x")))
        val root = server("other2.test")

        vm.revealPath(root)
        advanceUntilIdle()

        val serverRow = { vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>().single { it.conn.label() == conn.label() } }
        vm.io = hold
        vm.toggle(serverRow())
        advanceUntilIdle()

        vm.io = dispatcher
        vm.toggle(row("quick"))
        advanceUntilIdle()

        hold.release() // only now does that server connect
        advanceUntilIdle()

        assertFalse("the abandoned one must not expand itself", serverRow().expanded)
        assertTrue("the directory the user opened is still open", row("quick").expanded)
    }

    @Test
    fun `opening another directory while a slow one is still expanding leaves the later click open`() = runTest(dispatcher) {
        val root = server("race.test")
        vm.revealPath(root)
        advanceUntilIdle()

        // open slow: its IO is held back, so its expansion cannot land
        vm.io = hold
        vm.toggle(row("slow"))
        advanceUntilIdle()
        assertFalse("precondition: slow has not expanded yet", row("slow").expanded)

        // impatient, switch to quick instead (on the normal dispatcher, expands immediately)
        vm.io = dispatcher
        vm.toggle(row("quick"))
        advanceUntilIdle()
        assertTrue("precondition: quick has expanded", row("quick").expanded)

        // only now does the slow one finish
        hold.release()
        advanceUntilIdle()

        assertTrue("the later-clicked quick must still be open, not displaced by the slow one", row("quick").expanded)
        assertFalse("the slow one has already been abandoned, must not expand itself", row("slow").expanded)
    }

    /**
     * Abandoning only means "don't expand" -- the children it pulled back are still
     * cached, so clicking it again expands instantly without waiting through that slow
     * directory a second time.
     */
    @Test
    fun `an abandoned expansion still caches its children, so clicking again expands instantly`() = runTest(dispatcher) {
        val root = server("race2.test")
        vm.revealPath(root)
        advanceUntilIdle()

        vm.io = hold
        vm.toggle(row("slow"))
        advanceUntilIdle()
        vm.io = dispatcher
        vm.toggle(row("quick"))
        advanceUntilIdle()
        hold.release()
        advanceUntilIdle()

        // Cached children appear synchronously; a fresh listing runs behind them.
        val fs = FsRegistry.of(root) as FakeFileSystem
        val before = fs.listed.count { it == "/slow" }
        vm.toggle(row("slow"))
        assertTrue("clicking it again should expand it", row("slow").expanded)
        assertFalse("quick yields and collapses", row("quick").expanded)
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.path == "/slow/s.txt" })
        org.junit.Assert.assertEquals("cached expansion must not wait for IO", before, fs.listed.count { it == "/slow" })
        advanceUntilIdle()
        org.junit.Assert.assertEquals("every expansion checks for external changes", before + 1, fs.listed.count { it == "/slow" })
    }

    @Test
    fun `multiple branches remain expanded and disabling restores accordion on next expansion`() = runTest(dispatcher) {
        assertFalse(Prefs.allowMultipleExpandedNodes(app))
        val signature = Prefs.uiSignature(app)
        Prefs.setAllowMultipleExpandedNodes(app, true)
        assertTrue(signature != Prefs.uiSignature(app))
        vm.revealPath(server("multiple.test"))
        advanceUntilIdle()
        vm.toggle(row("slow"))
        advanceUntilIdle()
        vm.toggle(row("quick"))
        advanceUntilIdle()
        assertTrue(row("slow").expanded)
        assertTrue(row("quick").expanded)

        vm.toggle(row("slow"))
        assertFalse(row("slow").expanded)
        assertTrue(row("quick").expanded)
        Prefs.setAllowMultipleExpandedNodes(app, false)
        vm.toggle(row("slow"))
        advanceUntilIdle()
        assertTrue(row("slow").expanded)
        assertFalse(row("quick").expanded)
    }

    @Test
    fun `independent slow loads finish without changing the latest selection`() = runTest(dispatcher) {
        Prefs.setAllowMultipleExpandedNodes(app, true)
        vm.revealPath(server("multiple-race.test"))
        advanceUntilIdle()
        vm.io = hold
        vm.toggle(row("slow"))
        advanceUntilIdle()
        vm.io = dispatcher
        vm.toggle(row("quick"))
        advanceUntilIdle()
        hold.release()
        advanceUntilIdle()
        assertTrue(row("slow").expanded)
        assertTrue(row("quick").expanded)
        org.junit.Assert.assertEquals("/quick", vm.currentDir?.path)
        org.junit.Assert.assertEquals(row("quick").key, vm.state.value.currentKey)
    }

    @Test
    fun `slow server completion expands its branch without replacing destination`() = runTest(dispatcher) {
        Prefs.setAllowMultipleExpandedNodes(app, true)
        val slow = server("multiple-server-slow.test")
        vm.revealPath(server("multiple-server-quick.test"))
        advanceUntilIdle()
        val serverRow = vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>()
            .single { Connections.schemeOf(it.conn) == slow.scheme }
        vm.io = hold
        vm.toggle(serverRow)
        advanceUntilIdle()
        vm.io = dispatcher
        vm.toggle(row("quick"))
        advanceUntilIdle()
        val selected = vm.currentDir
        hold.release()
        advanceUntilIdle()
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>()
            .single { it.key == serverRow.key }.expanded)
        org.junit.Assert.assertEquals(selected, vm.currentDir)
    }

    @Test
    fun `folding ancestor prevents independent pending child from reopening`() = runTest(dispatcher) {
        Prefs.setAllowMultipleExpandedNodes(app, true)
        vm.revealPath(server("multiple-fold.test"))
        advanceUntilIdle()
        vm.io = hold
        vm.toggle(row("slow"))
        advanceUntilIdle()
        val parent = vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>().single()
        vm.toggle(parent)
        assertFalse(vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>().single().expanded)
        // Reopening the parent must not revive the earlier child request.
        vm.io = dispatcher
        vm.toggle(vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>().single())
        advanceUntilIdle()
        hold.release()
        advanceUntilIdle()
        assertFalse(row("slow").expanded)
        assertFalse(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.path == "/slow/s.txt" })
    }

    @Test
    fun `collapse all abandons every independent pending load`() = runTest(dispatcher) {
        Prefs.setAllowMultipleExpandedNodes(app, true)
        vm.revealPath(server("multiple-collapse-all.test"))
        advanceUntilIdle()
        vm.io = hold
        vm.toggle(row("slow"))
        vm.toggle(row("quick"))
        advanceUntilIdle()
        vm.collapseAll()
        hold.release()
        advanceUntilIdle()
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().none { it.file.path in setOf("/slow", "/quick", "/slow/s.txt", "/quick/q.txt") })
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>().none { it.expanded })
    }

    @Test
    fun `reveal retains other branches and path buttons still fold descendants`() = runTest(dispatcher) {
        Prefs.setAllowMultipleExpandedNodes(app, true)
        val root = server("multiple-reveal.test")
        vm.revealPath(root.copy(path = "/slow"))
        advanceUntilIdle()
        vm.revealPath(root.copy(path = "/quick"))
        advanceUntilIdle()
        assertTrue(row("slow").expanded)
        assertTrue(row("quick").expanded)
        val parent = vm.pathSegments().first { it.key.startsWith("s:") }
        vm.selectPathSegment(parent.key)
        advanceUntilIdle()
        assertFalse(row("slow").expanded)
        assertFalse(row("quick").expanded)
    }

}
