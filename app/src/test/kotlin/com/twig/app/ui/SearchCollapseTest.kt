package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ★ Collapsing a **server row / favorite row** must also remove any search results hanging
 * under it.
 *
 * Attached rows (info cards, search results) render **without regard to expansion state**
 * (see the comment on `addAttachments`), so collapsing does not make them disappear on its
 * own -- `toggleFile` already covers this for plain directories, but `toggleServer` /
 * `toggleFavorite` never did. The symptom: "search from a server's root, click the server
 * row, its children collapse, but a pile of search results is left hanging below with no
 * way to close it".
 *
 * ★ The other half of the fix is a key mismatch: search results are stored under **the
 * directory's `fileKey`**, while the server row's own key is `s:<label>` -- removing by the
 * row's key deletes nothing at all, so "remembering to delete" alone is not enough.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchCollapseTest {

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

    /** Builds a server and expands it; returns (server row key, server root directory). */
    private fun server(host: String): Pair<String, XFile> {
        val conn = SavedConnection(type = "sftp", host = host, user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(
            FakeFileSystem(
                scheme,
                dirs = mapOf("/" to listOf("dir1", "a.txt"), "/dir1" to listOf("b.txt")),
                files = mapOf("/a.txt" to "x", "/dir1/b.txt" to "x"),
            ),
        )
        return "s:${conn.label()}" to XFile(scheme, "/", isDir = true)
    }

    private fun searchRows() = vm.state.value.rows.filterIsInstance<PaneViewModel.SearchNode>()

    @Test
    fun `collapsing the server row removes the search results with it`() = runTest(dispatcher) {
        val (rowKey, root) = server("collapse.test")
        vm.revealPath(root) // connect and expand this server
        advanceUntilIdle()

        vm.startSearch(root, "*.txt")
        advanceUntilIdle()
        assertEquals("precondition: search results are attached", 1, searchRows().size)

        // click the server row = collapse
        val serverRow = vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>()
            .single { it.key == rowKey }
        vm.toggle(serverRow)
        advanceUntilIdle()

        assertTrue("search results must disappear along with collapsing the server", searchRows().isEmpty())
    }

    /**
     * When the server is not expanded and only search results hang below it, clicking the
     * row is treated as "collapse": only close the search, **do not** instead expand the
     * whole server (same branch as in `toggleFile`).
     */
    @Test
    fun `clicking an unexpanded server row only closes the search`() = runTest(dispatcher) {
        val (rowKey, root) = server("collapse2.test")
        vm.revealPath(root)
        advanceUntilIdle()

        vm.startSearch(root, "*.txt")
        advanceUntilIdle()

        // collapse first (this also removes the search), then search again -- the server is now collapsed
        val row = { vm.state.value.rows.filterIsInstance<PaneViewModel.ServerNode>().single { it.key == rowKey } }
        vm.toggle(row())
        advanceUntilIdle()
        assertTrue("precondition: collapsed", !row().expanded)

        vm.startSearch(root, "*.txt")
        advanceUntilIdle()
        assertEquals("precondition: search results can attach even while collapsed", 1, searchRows().size)

        vm.toggle(row())
        advanceUntilIdle()
        assertTrue("should only close the search", searchRows().isEmpty())
        assertTrue("should not also expand the server", !row().expanded)
    }
}
