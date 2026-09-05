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
 * A transient list like "Now Playing" takes its title from **the directory's display name
 * in the tree**, not the last path segment.
 *
 * A media server's last path segment is the item id (Emby's are plain numbers) -- use that
 * as the title and the playlist ends up called "40". The tree row's `XFile` already carries
 * a `displayName` (the album name).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistTitleTest {

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

    /**
     * Builds a media server: the album directory's last path segment is an id (`al40`),
     * while its display name is "夏夜" (Chinese, chosen deliberately as a non-ASCII display
     * name) -- exactly the "path is unreadable" shape a media server has.
     */
    private fun server(host: String): String {
        val conn = SavedConnection(type = "jellyfin", host = host, user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(
            NamedFakeFs(
                scheme,
                dirs = mapOf(
                    "/" to listOf("music"),
                    "/music" to listOf("al40"),
                    "/music/al40" to listOf("t38", "t39"),
                ),
                files = mapOf("/music/al40/t38" to "x", "/music/al40/t39" to "x"),
                names = mapOf(
                    "/music/al40" to "夏夜",
                    "/music/al40/t38" to "第1首 夜航.mp3",
                    "/music/al40/t39" to "第2首 夜航.mp3",
                ),
            ),
        )
        return scheme
    }

    @Test
    fun `the transient list's title is the album name, not the directory id`() = runTest(dispatcher) {
        val scheme = server("http://jf-title.test")
        val album = XFile(scheme, "/music/al40", isDir = true)
        vm.revealPath(album)
        advanceUntilIdle()

        val track = XFile(scheme, "/music/al40/t38", isDir = false, displayName = "第1首 夜航.mp3")
        assertEquals("夏夜", vm.parentLabel(track))
    }

    @Test
    fun `falls back to the last path segment when the directory was never expanded, rather than having no name`() = runTest(dispatcher) {
        val scheme = server("http://jf-title2.test")
        val track = XFile(scheme, "/music/al40/t38", isDir = false)
        assertEquals("al40", vm.parentLabel(track))
    }
}

/** A variant of [FakeFileSystem] that can give an entry its own display name (path differs from name, like a media server). */
private class NamedFakeFs(
    override val scheme: String,
    private val dirs: Map<String, List<String>>,
    private val files: Map<String, String>,
    private val names: Map<String, String>,
) : com.twig.core.FileSystem {
    override val displayName = "NamedFake($scheme)"
    override fun root() = XFile(scheme, "/", isDir = true)
    override fun resolve(path: String): XFile = when {
        dirs.containsKey(path) -> XFile(scheme, path, isDir = true, displayName = names[path])
        files.containsKey(path) -> XFile(
            scheme, path, isDir = false,
            size = files.getValue(path).length.toLong(), displayName = names[path],
        )
        else -> throw com.twig.core.FsException("no such path: $path")
    }
    override fun list(dir: XFile): List<XFile> {
        val kids = dirs[dir.path] ?: throw com.twig.core.FsException("no such dir: ${dir.path}")
        val prefix = if (dir.path.endsWith("/")) dir.path else "${dir.path}/"
        return kids.map { resolve("$prefix$it") }
    }
    override fun openInput(file: XFile) = java.io.ByteArrayInputStream(ByteArray(0))
    override fun exists(file: XFile) = dirs.containsKey(file.path) || files.containsKey(file.path)
    override fun writable() = false
    override fun openOutput(file: XFile, append: Boolean): java.io.OutputStream =
        throw com.twig.core.FsException("read-only")
    override fun mkdir(parent: XFile, name: String): XFile = throw com.twig.core.FsException("read-only")
    override fun delete(file: XFile) = throw com.twig.core.FsException("read-only")
    override fun rename(file: XFile, newName: String): XFile = throw com.twig.core.FsException("read-only")
}
