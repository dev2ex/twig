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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A media server's "Continue Watching" / "Latest" **preserves the order the server gave**,
 * and does not apply the user's chosen sort.
 *
 * The server returns these in reverse "recently played" (or "recently added") order, and
 * that order **is the entire reason these directories exist**. Sorting them again by
 * name/size sinks the item you were halfway through into the middle of the list -- the
 * symptom users see is "Continue Watching never updates" (reported on 2026-08-18, initially
 * mistaken for a stale cache).
 *
 * The assertion is "the order matches exactly what the server returned", checked against a
 * plain library directory on the same server as a control -- that directory **should**
 * follow the user's sort, proving that only the intended few directories are being skipped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelServerOrderTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private val dispatcher = StandardTestDispatcher()

    /** The order the server gives: deliberately neither alphabetical nor its reverse, so any sort touching it would change the result. */
    private val served = listOf("c.mkv", "a.mkv", "b.mkv")

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * Builds a media server. Give [host] a different value per test case -- `FsRegistry`
     * is a process-wide singleton, and colliding schemes interfere between cases.
     */
    private fun server(host: String, type: String = "jellyfin"): String {
        val conn = SavedConnection(type = type, host = host, user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(
            FakeFileSystem(
                scheme,
                dirs = mapOf(
                    "/" to listOf("resume", "latest", "folders", "playlists"),
                    "/resume" to served,
                    "/latest" to listOf("lib1"),
                    "/latest/lib1" to served,
                    "/folders" to listOf("lib1"),
                    "/folders/lib1" to served,
                    "/playlists" to listOf("pl1"),
                    "/playlists/pl1" to served,
                ),
                files = served.flatMap { n ->
                    listOf(
                        "/resume/$n" to "x", "/latest/lib1/$n" to "x",
                        "/folders/lib1/$n" to "x", "/playlists/pl1/$n" to "x",
                    )
                }.toMap(),
            ),
        )
        return scheme
    }

    /** Names of the rows under [path] (in tree order) after expanding it. */
    private fun kotlinx.coroutines.test.TestScope.namesUnder(scheme: String, path: String): List<String> {
        vm.revealPath(XFile(scheme, path, isDir = true))
        advanceUntilIdle()
        return vm.state.value.rows
            .filterIsInstance<PaneViewModel.FileNode>()
            .filter { it.file.scheme == scheme && !it.file.isDir && it.file.path.startsWith("$path/") }
            .map { it.file.name }
    }

    @Test
    fun `continue watching preserves server order`() = runTest(dispatcher) {
        val scheme = server("http://jf-resume.test")
        assertEquals(served, namesUnder(scheme, "/resume"))
    }

    @Test
    fun `latest also preserves server order`() = runTest(dispatcher) {
        // A library's "Latest" is returned in reverse added-time order; likewise must not be re-sorted
        val scheme = server("http://jf-latest.test")
        assertEquals(served, namesUnder(scheme, "/latest/lib1"))
    }

    @Test
    fun `a playlist's content preserves its curated order`() = runTest(dispatcher) {
        // The order inside a playlist is the user's curated playback order; sorting by name would destroy the playlist.
        // (The query side must likewise carry no SortBy, see JellyfinFileSystemTest)
        val scheme = server("http://jf-playlist.test")
        assertEquals(served, namesUnder(scheme, "/playlists/pl1"))
    }

    @Test
    fun `a plain directory on the same server still follows the user's sort`() = runTest(dispatcher) {
        // Control group: skipping sort should only happen for those "server already sorted it" directories,
        // otherwise the whole source could never be sorted by name/size
        val scheme = server("http://jf-movies.test")
        assertEquals(served.sorted(), namesUnder(scheme, "/folders/lib1"))
    }

    @Test
    fun `a same-named path on a non-media server still gets sorted`() = runTest(dispatcher) {
        // The check must also look at connection type: another source happening to have a directory named resume should not trigger this rule
        val scheme = server("ftp-resume.test", type = "ftp")
        assertEquals(served.sorted(), namesUnder(scheme, "/resume"))
    }
}
