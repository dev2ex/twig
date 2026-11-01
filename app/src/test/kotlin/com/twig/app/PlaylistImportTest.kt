package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ui.PlaylistImportModel
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.io.File
import java.io.IOException
import java.nio.file.Files
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
@Config(application = Application::class, sdk = [34])
class PlaylistImportTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val schemes = HashSet<String>()
    private class Source(override val scheme: String, val dirs: Map<String, List<XFile>>, val text: String = "") : FileSystem by LocalFileSystem() {
        val listed = ArrayList<String>()
        var opened = 0
        var onList: (() -> Unit)? = null
        override fun list(dir: XFile): List<XFile> {
            listed += dir.path
            onList?.invoke()
            return dirs[dir.path] ?: throw IOException("unreadable ${dir.path}")
        }
        override fun openInput(file: XFile): java.io.InputStream { opened++; return text.byteInputStream() }
    }
    private fun register(fs: FileSystem) { FsRegistry.register(fs); schemes += fs.scheme }

    @Before fun setup() {
        app.getSharedPreferences("twig_playlists", 0).edit().clear().commit()
        app.getSharedPreferences(ConnectionStore.FILE, 0).edit().clear().commit()
        PlaylistStore.clearTemporary()
        Dispatchers.setMain(dispatcher)
    }
    @After fun cleanup() { schemes.forEach(FsRegistry::unregister); Dispatchers.resetMain() }

    @Test fun `recursive import keeps audio and listing metadata without opening audio files`() = runTest(dispatcher) {
        val album = XFile("file", "/music/album", true)
        val song = XFile("file", "/music/album/01.FLAC", false, size = 123, lastModified = 456)
        val rootSong = XFile("file", "/music/02.mp3", false, size = 789)
        val fs = Source("file", mapOf("/music" to listOf(album, rootSong, XFile("file", "/music/notes.txt", false)),
            album.path to listOf(song)))
        register(fs)
        val imported = PlaylistImport.directory(app, XFile("file", "/music", true)) { _, _ -> }
        assertEquals(listOf(song.path, rootSong.path), imported.map { it.path })
        assertEquals(123L, imported.first().size)
        assertEquals(456L, imported.first().lastModified)
        assertEquals(listOf("/music", album.path), fs.listed)
        assertEquals(0, fs.opened)
    }

    @Test fun `saved server and opaque SAF names remain restorable`() = runTest(dispatcher) {
        val conn = SavedConnection("smb", "import.test", share = "Music")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        val song = XFile(scheme, "/album/song", false, size = 100, lastModified = 200, displayName = "歌曲.flac")
        register(Source(scheme, mapOf("/" to listOf(song))))
        val track = PlaylistImport.directory(app, XFile(scheme, "/", true)) { _, _ -> }.single()
        assertEquals("conn", track.kind)
        assertEquals(conn.label(), track.connLabel)
        assertEquals("歌曲.flac", track.name)
        assertEquals(100L, track.size)
        register(Source("saf", mapOf("content://docs/root" to listOf(song.copy(scheme = "saf", path = "content://docs/42")))))
        val saf = PlaylistImport.directory(app, XFile("saf", "content://docs/root", true)) { _, _ -> }.single()
        assertEquals("saf", saf.kind)
        assertEquals("content://docs/42", saf.path)
        assertEquals("歌曲.flac", saf.name)
    }

    @Test fun `local directory symlink cycle is visited only once`() = runTest(dispatcher) {
        val root = Files.createTempDirectory("twig-import").toFile()
        try {
            File(root, "song.mp3").writeBytes(byteArrayOf(1))
            Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
            register(LocalFileSystem())
            val result = PlaylistImport.directory(app, XFile("file", root.path, true)) { _, _ -> }
            assertEquals(listOf("song.mp3"), result.map { it.name })
        } finally { File(root, "loop").delete(); root.deleteRecursively() }
    }

    @Test fun `playlist file import resolves relative entries and removes repeated tracks`() = runTest(dispatcher) {
        val song = XFile("file", "/lists/song.flac", false, size = 111, lastModified = 222)
        val fs = Source("file", mapOf("/lists" to listOf(song)), "\uFEFF#EXTM3U\nsong.flac\n./song.flac\n")
        register(fs)
        val result = PlaylistImport.playlist(app, XFile("file", "/lists/mix.m3u8", false))
        assertEquals(1, result.size)
        assertEquals(song.path, result.single().path)
        assertEquals(song.size, result.single().size)
        assertEquals(1, fs.opened)
        assertEquals(listOf("/lists"), fs.listed)
    }

    @Test fun `append deduplicates within the import and retains existing metadata and resume`() {
        val first = PlaylistTrack("local", "/first.mp3", title = "Original")
        val second = PlaylistTrack("local", "/second.mp3")
        val list = PlaylistStore.create(app, "Mix", listOf(first)).copy(lastPosMs = 1234)
        PlaylistStore.save(app, list)
        val updated = PlaylistStore.addTracks(app, list.id, listOf(first.copy(title = "Changed"), second, second))!!
        assertEquals(listOf(first, second), updated.tracks)
        assertEquals(1234L, updated.lastPosMs)
    }

    @Test fun `concurrent import and background metadata resume saves retain every added track`() {
        val first = PlaylistTrack("local", "/first.mp3")
        val playlist = PlaylistStore.create(app, "Mix", listOf(first))
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val importing = workers.submit {
                start.await()
                for (i in 1..40) PlaylistStore.addTracks(app, playlist.id, listOf(PlaylistTrack("local", "/$i.mp3")))
            }
            val metadata = workers.submit {
                start.await()
                for (i in 1..40) {
                    PlaylistStore.updateTrackMeta(app, playlist.id, first.copy(title = "Title $i"))
                    PlaylistStore.saveResume(app, playlist.id, first.id, i.toLong())
                }
            }
            start.countDown()
            importing.get(5, java.util.concurrent.TimeUnit.SECONDS)
            metadata.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val updated = PlaylistStore.get(app, playlist.id)!!
            assertEquals(41, updated.tracks.size)
            assertEquals("Title 40", updated.tracks.first().title)
            assertEquals(40L, updated.lastPosMs)
        } finally { workers.shutdownNow() }
    }

    @Test fun `failed recursive listing does not commit a partial import`() = runTest(dispatcher) {
        register(Source("file", mapOf("/music" to listOf(XFile("file", "/music/song.mp3", false), XFile("file", "/music/denied", true)))))
        val saved = PlaylistStore.create(app, "Mix")
        val model = PlaylistImportModel(app).apply { ioDispatcher = dispatcher }
        model.start(saved.id, XFile("file", "/music", true), true)
        advanceUntilIdle()
        assertNotNull(model.state.value!!.error)
        assertTrue(model.state.value!!.error!!.contains("denied"))
        assertEquals(saved, PlaylistStore.get(app, saved.id))
    }

    @Test fun `cancelled scan never commits tracks and can be retried`() = runTest(dispatcher) {
        val fs = Source("file", mapOf("/music" to listOf(XFile("file", "/music/song.mp3", false))))
        register(fs)
        val saved = PlaylistStore.create(app, "Mix")
        val model = PlaylistImportModel(app).apply { ioDispatcher = dispatcher }
        fs.onList = { model.cancel() }
        model.start(saved.id, XFile("file", "/music", true), true)
        advanceUntilIdle()
        assertEquals(saved, PlaylistStore.get(app, saved.id))
        fs.onList = null
        model.start(saved.id, XFile("file", "/music", true), true)
        advanceUntilIdle()
        assertEquals(1, model.state.value!!.added)
        assertEquals("song.mp3", PlaylistStore.get(app, saved.id)!!.tracks.single().name)
    }

    @Test fun `temporary targets and unsupported directory sources are rejected`() = runTest(dispatcher) {
        register(Source("file", mapOf("/music" to listOf(XFile("file", "/music/song.mp3", false)))))
        val temporary = PlaylistStore.setNow(app, "Archive", listOf(PlaylistTrack("temporary", "/song.flac", sessionScheme = "zip")))
        val model = PlaylistImportModel(app).apply { ioDispatcher = dispatcher }
        model.start(temporary.id, XFile("file", "/music", true), true)
        advanceUntilIdle()
        assertNotNull(model.state.value!!.error)
        assertEquals(temporary, PlaylistStore.get(app, temporary.id))
        try {
            PlaylistImport.directory(app, XFile("zip", "/", true)) { _, _ -> }
            fail("Mounted sources cannot be persisted")
        } catch (_: IllegalArgumentException) { }
    }
}
