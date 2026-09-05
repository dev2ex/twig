package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.SafFileSystem
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Go to the containing folder" in the music player / playlist
 * ([MusicDialogs.canLocate] plus the reveal it triggers).
 *
 * The action is `revealPath(file.parentPath, focus = file)`, and `revealPath` builds the
 * ancestor chain by cutting the path on '/', listing **every level as a row of the tree**.
 * Two sources break that assumption and are therefore left out of the menu rather than
 * offered and then failing:
 * - a **document tree**: a document URI's `parentPath` is half a URI, and SAF has no
 *   "get parent" API;
 * - a **media server**: its first segment can be a synthetic prefix that is not a row at
 *   all — libraries sit directly on the root as `/lib/<libId>`, so cutting a track's path
 *   yields `/lib`, which the backend refuses with "Unknown Jellyfin directory: /lib".
 *
 * ★ The fixtures below use the **real shape** of each backend's paths. An earlier version
 * of this test built the media server as `/music/album1/song1` — every level existing —
 * and passed against a broken implementation (the same trap `SafSiblingsTest` records).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicLocateTest {

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
     * Registers a fake backend on a saved connection's deterministic scheme and puts it
     * in `Connections`' process-wide reverse table the way playing a track would
     * (`ensure` returns early because the scheme is already registered — no real
     * connection is attempted).
     */
    private fun fakeServer(
        type: String,
        host: String,
        dirs: Map<String, List<String>>,
        files: Map<String, String> = emptyMap(),
    ): SavedConnection {
        val conn = SavedConnection(type = type, host = host, port = 0, user = "u")
        ConnectionStore.save(app, conn)
        FsRegistry.register(FakeFileSystem(Connections.schemeOf(conn), dirs, files))
        Connections.ensure(app, conn)
        return conn
    }

    // ---- which sources offer the menu item ----

    @Test
    fun `local files offer it`() {
        assertTrue(MusicDialogs.canLocate(XFile("file", "/sdcard/Music/a.mp3", isDir = false)))
    }

    /** A media server's path is not a chain of rows — see the class doc and the test below. */
    @Test
    fun `a media server does not offer it`() {
        val conn = fakeServer("jellyfin", "media-host", dirs = mapOf("/" to emptyList()))
        val song = XFile(Connections.schemeOf(conn), "/lib/lib1/album1/song1", isDir = false, displayName = "Song.mp3")
        assertFalse(MusicDialogs.canLocate(song))
    }

    @Test
    fun `emby is the same source as jellyfin here`() {
        val conn = fakeServer("emby", "emby-host", dirs = mapOf("/" to emptyList()))
        assertFalse(MusicDialogs.canLocate(XFile(Connections.schemeOf(conn), "/lib/l1/a1/s1", isDir = false)))
    }

    @Test
    fun `an ordinary server offers it`() {
        val conn = fakeServer("smb", "nas-host", dirs = mapOf("/" to emptyList()))
        assertTrue(MusicDialogs.canLocate(XFile(Connections.schemeOf(conn), "/music/a.mp3", isDir = false)))
    }

    /** The whole point: a document tree is left out rather than offered and then failing. */
    @Test
    fun `a document tree does not offer it`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic" +
            "/document/primary%3AMusic%2Fa.mp3"
        assertFalse(MusicDialogs.canLocate(XFile(SafFileSystem.SCHEME, uri, isDir = false, displayName = "a.mp3")))
    }

    /** Sources `revealPath` cannot walk at all are out too (an entry inside an archive). */
    @Test
    fun `an entry inside an archive does not offer it`() {
        assertFalse(MusicDialogs.canLocate(XFile("zip1234", "/inside/a.mp3", isDir = false)))
    }

    // ---- why the media server is out ----

    /**
     * The reason, pinned: with the **real** shape of a media server's paths, walking up by
     * `parentPath` hits `/lib` — a prefix the root listing never contains (libraries are
     * `/lib/<libId>` rows sitting directly on the server root), so listing it fails and the
     * jump reports an error instead of landing anywhere.
     */
    @Test
    fun `walking up a media server path hits a segment that is not a row`() = runTest(dispatcher) {
        val conn = fakeServer(
            "jellyfin", "reveal-media",
            // exactly what rootEntries produces: the library is a row, "/lib" is not
            dirs = mapOf(
                "/" to listOf("lib/lib1"),
                "/lib/lib1" to listOf("album1"),
                "/lib/lib1/album1" to listOf("song1"),
            ),
            files = mapOf("/lib/lib1/album1/song1" to "id3"),
        )
        val scheme = Connections.schemeOf(conn)
        val song = XFile(scheme, "/lib/lib1/album1/song1", isDir = false)

        vm.revealPath(XFile(scheme, song.parentPath, isDir = true), focus = song)
        advanceUntilIdle()

        val err = vm.state.value.error
        assertNotNull("walking up must fail on the synthetic /lib level", err)
        assertTrue("should name the level it could not list, was: $err", err!!.contains("/lib"))
    }
}
