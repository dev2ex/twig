package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.Prefs
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tapping a row's **icon** is the "keep this branch" gesture: the branch it opens
 * survives the accordion — expanding another node must not fold it away.
 *
 * The row itself stays an ordinary toggle, so the accordion is unchanged for everybody
 * else; only the icon tap buys a branch that sticks around.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeptOpenBranchTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A server with three directories; "alpha" holds a nested directory "deep". */
    private fun server(host: String): XFile {
        val conn = SavedConnection(type = "sftp", host = host, user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(
            FakeFileSystem(
                scheme,
                dirs = mapOf(
                    "/" to listOf("alpha", "beta", "gamma"),
                    "/alpha" to listOf("deep"),
                    "/alpha/deep" to listOf("d.txt"),
                    "/beta" to listOf("b.txt"),
                    "/gamma" to listOf("g.txt"),
                ),
                files = mapOf("/alpha/deep/d.txt" to "x", "/beta/b.txt" to "x", "/gamma/g.txt" to "x"),
            ),
        )
        return XFile(scheme, "/", isDir = true)
    }

    private fun row(name: String) =
        vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.name == name }

    @Test
    fun `an icon-opened branch survives a later expansion`() = runTest(dispatcher) {
        assertFalse("precondition: the accordion is the default", Prefs.allowMultipleExpandedNodes(app))
        vm.revealPath(server("kept.test"))
        advanceUntilIdle()

        vm.toggleKeepingOpen(row("alpha"))
        advanceUntilIdle()
        assertTrue("precondition: the icon opened the branch", row("alpha").expanded)

        vm.toggle(row("beta"))
        advanceUntilIdle()

        assertTrue("the icon-opened branch must not be folded away", row("alpha").expanded)
        assertTrue(row("alpha").keptOpen)
        assertFalse(row("beta").keptOpen)
        assertTrue(row("beta").expanded)
    }

    @Test
    fun `a row-opened branch is still folded by the accordion`() = runTest(dispatcher) {
        vm.revealPath(server("accordion.test"))
        advanceUntilIdle()

        vm.toggle(row("alpha"))
        advanceUntilIdle()
        vm.toggle(row("beta"))
        advanceUntilIdle()

        assertFalse(row("alpha").expanded)
        assertTrue(row("beta").expanded)
    }

    @Test
    fun `collapsing the icon-opened branch gives up the protection`() = runTest(dispatcher) {
        vm.revealPath(server("collapse.test"))
        advanceUntilIdle()

        vm.toggleKeepingOpen(row("alpha"))
        advanceUntilIdle()
        assertTrue(row("alpha").expanded)

        vm.toggle(row("alpha")) // Collapsing it drops the protection.
        advanceUntilIdle()
        assertFalse(row("alpha").expanded)

        vm.toggle(row("beta"))
        advanceUntilIdle()
        vm.toggle(row("alpha")) // Reopened with a plain row tap: an ordinary branch again.
        advanceUntilIdle()
        vm.toggle(row("gamma"))
        advanceUntilIdle()

        assertFalse("a reopened branch is an ordinary one again", row("alpha").expanded)
        assertTrue(row("gamma").expanded)
    }

    @Test
    fun `the ancestor chain of an icon-opened branch stays open`() = runTest(dispatcher) {
        vm.revealPath(server("nested.test"))
        advanceUntilIdle()

        vm.toggle(row("alpha")) // The parent is a plain row tap ...
        advanceUntilIdle()
        vm.toggleKeepingOpen(row("deep")) // ... the nested one is opened from its icon.
        advanceUntilIdle()
        assertTrue(row("deep").expanded)

        vm.toggle(row("beta"))
        advanceUntilIdle()

        assertTrue("the icon-opened node stays open", row("deep").expanded)
        assertTrue(row("deep").keptOpen)
        assertFalse("ancestors must not inherit the badge", row("alpha").keptOpen)
        assertTrue("and so does the parent it hangs under", row("alpha").expanded)
    }

    private fun assertAncestorCollapse(multiple: Boolean) = runTest(dispatcher) {
        Prefs.setAllowMultipleExpandedNodes(app, multiple)
        vm.revealPath(server("ancestor-collapse.test"))
        advanceUntilIdle()
        vm.toggle(row("alpha"))
        advanceUntilIdle()
        vm.toggleKeepingOpen(row("deep"))
        advanceUntilIdle()
        assertTrue(row("deep").expanded)

        vm.toggle(row("alpha"))
        advanceUntilIdle()
        vm.toggle(row("alpha"))
        assertFalse("cached children return synchronously, with descendants folded", row("deep").expanded)
        assertFalse(row("deep").keptOpen)
        advanceUntilIdle()
        vm.toggle(row("beta"))
        advanceUntilIdle()
        assertTrue(row("beta").expanded)
        org.junit.Assert.assertEquals("only multi-branch mode retains the reopened parent", multiple, row("alpha").expanded)
    }

    @Test
    fun `ancestor collapse clears child expansion and protection in accordion mode`() = assertAncestorCollapse(false)

    @Test
    fun `ancestor collapse clears child expansion and protection in multiple branch mode`() = assertAncestorCollapse(true)

    @Test
    fun `collapsing a server clears expansion below already hidden directories`() = runTest(dispatcher) {
        vm.revealPath(server("hidden-collapse.test"))
        advanceUntilIdle()
        vm.toggle(row("alpha"))
        advanceUntilIdle()
        vm.toggle(row("deep"))
        advanceUntilIdle()
        vm.toggle(row("beta")) // Accordion hides alpha and deep before the explicit server collapse.
        advanceUntilIdle()
        fun serverRow() = vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>().single()
        vm.toggle(serverRow())
        vm.toggle(serverRow())
        vm.toggle(row("alpha"))
        assertFalse(row("deep").expanded)
        advanceUntilIdle()
    }

    @Test
    fun `up clears descendants in multiple branch mode`() = runTest(dispatcher) {
        Prefs.setAllowMultipleExpandedNodes(app, true)
        vm.revealPath(server("up-collapse.test"))
        advanceUntilIdle()
        vm.toggle(row("alpha"))
        advanceUntilIdle()
        vm.toggleKeepingOpen(row("deep"))
        advanceUntilIdle()
        vm.highlightNode(row("alpha"))
        vm.up()
        vm.toggle(row("alpha"))
        assertFalse(row("deep").expanded)
        advanceUntilIdle()
    }

    @Test
    fun `a pinned favorite child keeps its favorite ancestors when another alias is opened`() = runTest(dispatcher) {
        Prefs.setRootItemOrder(app, listOf("sftp", "fav"))
        val root = server("favorite-alias.test")
        vm.revealPath(root)
        advanceUntilIdle()
        val favorite = vm.favoriteFrom(root)!!
        vm.collapseAll()
        vm.addFavorite(favorite)
        vm.toggleFavorite(vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().single(), null) { _, _ -> }
        advanceUntilIdle()
        vm.toggleKeepingOpen(row("alpha"))
        advanceUntilIdle()
        vm.revealPath(root.copy(path = "/beta"))
        advanceUntilIdle()
        // The source is rendered before Favorites, so the same alpha key now has another visible parent chain.
        vm.toggle(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first { it.file.name == "gamma" })
        advanceUntilIdle()
        assertTrue("the favorite containing the pinned branch must stay expanded",
            vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().singleOrNull()?.expanded == true)
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.GroupNode>().single { it.id == "fav" }.expanded)
    }

    @Test
    fun `pinning an already duplicated row uses the clicked favorite occurrence and clears on folding it`() = runTest(dispatcher) {
        Prefs.setRootItemOrder(app, listOf("sftp", "fav"))
        val root = server("clicked-favorite-alias.test")
        vm.revealPath(root)
        advanceUntilIdle()
        Prefs.setAllowMultipleExpandedNodes(app, true)
        vm.addFavorite(vm.favoriteFrom(root)!!)
        vm.toggleFavorite(vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().single(), null) { _, _ -> }
        advanceUntilIdle()
        Prefs.setAllowMultipleExpandedNodes(app, false)
        val aliases = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().filter { it.file.name == "alpha" }
        org.junit.Assert.assertEquals(2, aliases.size)
        vm.toggleKeepingOpen(aliases.last())
        advanceUntilIdle()
        assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().singleOrNull()?.expanded == true)
        vm.toggleFavorite(vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().single(), null) { _, _ -> }
        advanceUntilIdle()
        assertTrue("folding the favorite clears the pinned descendant even when its other alias remains visible",
            vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().filter { it.file.name == "alpha" }
                .all { !it.keptOpen && !it.expanded })
        vm.toggle(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first { it.file.name == "beta" })
        advanceUntilIdle()
        assertFalse(vm.state.value.rows.filterIsInstance<PaneViewModel.GroupNode>().single { it.id == "fav" }.expanded)
    }
}
