package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class M3uPlaylistTest {
    private lateinit var app: Application
    private val registered = ArrayList<String>()

    private class Source(override val scheme: String, val entries: List<XFile>, var text: String = "") : FileSystem by LocalFileSystem() {
        val listed = ArrayList<String>()
        override fun openInput(file: XFile) = text.byteInputStream()
        override fun list(dir: XFile): List<XFile> {
            listed += dir.path
            return entries.filter { it.parentPath == dir.path }
        }
    }

    private fun register(fs: Source) {
        FsRegistry.register(fs)
        registered += fs.scheme
    }

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(ConnectionStore.FILE, 0).edit().clear().commit()
    }

    @After fun cleanup() { registered.forEach { FsRegistry.unregister(it) } }

    @Test fun `exported favorite style path restores SMB outside the pane and internal source URIs still work`() {
        val conn = SavedConnection(type = "smb", host = "playlist-roundtrip.test", share = "Music", user = "u", name = "NAS alias")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        val audio = XFile(scheme, "/album/一 首.flac", false, size = 123456, lastModified = 789)
        val remote = Source(scheme, listOf(audio))
        register(remote)
        val dir = XFile("file", "/exports", true)
        val text = PlaylistExport.render(dir,
            listOf(PlaylistTrack("conn", audio.path, conn.label()) to audio)) { conn }
        assertEquals("smb://playlist-roundtrip.test/Music/album/一 首.flac", text.lines()[2])
        val local = Source("file", emptyList(), text)
        register(local)

        val imported = M3uPlaylist.tracks(app, XFile("file", "/exports/list.m3u8", false)).single()
        assertEquals("conn", imported.kind)
        assertEquals(conn.label(), imported.connLabel)
        assertEquals(audio.path, imported.path)
        assertEquals(audio.size, imported.size)
        assertEquals(audio.lastModified, imported.lastModified)
        assertEquals(listOf("/album"), remote.listed)
        local.text = audio.toUri()
        assertEquals(imported, M3uPlaylist.tracks(app, XFile("file", "/exports/list.m3u8", false)).single())
    }

    @Test fun `mixed sources with identical paths keep separate metadata and batch each directory`() {
        val first = XFile("smb8ceb2f60", "/album/a.mp3", false, size = 100)
        val second = XFile("smb123", "/album/a.mp3", false, size = 200)
        val smbA = Source(first.scheme, listOf(first))
        val smbB = Source(second.scheme, listOf(second))
        register(smbA); register(smbB)
        register(Source("file", emptyList(), "#EXTM3U\n${first.toUri()}\n${second.toUri()}\n${first.toUri()}\n"))
        val files = M3uPlaylist.parse(XFile("file", "/list.m3u8", false))
        assertEquals(listOf(first, second, first), files)
        assertEquals(1, smbA.listed.size)
        assertEquals(1, smbB.listed.size)
    }

    @Test fun `source preparation happens once before listing an unregistered source`() {
        val audio = XFile("smb8ceb2f60", "/a.mp3", false, size = 123)
        register(Source("file", emptyList(), "${audio.toUri()}\n${audio.toUri()}\n"))
        var prepared = 0
        val files = M3uPlaylist.parse(XFile("file", "/list.m3u8", false)) { scheme ->
            prepared++
            assertEquals(audio.scheme, scheme)
            register(Source(scheme, listOf(audio)))
            true
        }
        assertEquals(listOf(audio, audio), files)
        assertEquals(1, prepared)
    }

    @Test fun `relative paths still resolve against the playlist and unknown URLs are skipped`() {
        val audio = XFile("file", "/Music/album/a.mp3", false, size = 123)
        register(Source("file", listOf(audio), "../album/a.mp3\n${audio.toUri()}\nhttp://test/a.mp3\nunknown:///a.mp3\n"))
        assertEquals(listOf(audio, audio), M3uPlaylist.parse(XFile("file", "/Music/lists/list.m3u8", false)))
    }

    @Test fun `raw paths choose the longest mounted root and recent location aliases are accepted`() {
        val root = SavedConnection("smb", "playlist-roots.test", share = "Music")
        val nested = root.copy(share = "Music/Albums", name = "Album NAS")
        ConnectionStore.save(app, root)
        ConnectionStore.save(app, nested)
        val scheme = Connections.schemeOf(nested)
        val audio = XFile(scheme, "/a.mp3", false, size = 123)
        register(Source(scheme, listOf(audio)))
        register(Source("file", emptyList(), "smb://playlist-roots.test/Music/Albums/a.mp3\nsmb://Album NAS/a.mp3\nsmb:/playlist-roots.test/Music/Albums/a.mp3\nsmb:/Album NAS/a.mp3\nsmb://unknown/Music/a.mp3\n"))
        val tracks = M3uPlaylist.tracks(app, XFile("file", "/list.m3u8", false))
        assertEquals(List(2) { nested.label() }, tracks.map { it.connLabel })
        assertEquals(List(2) { "/a.mp3" }, tracks.map { it.path })
    }

    @Test fun `SFTP authority paths resolve and single-slash source paths are skipped`() {
        val conn = SavedConnection("sftp", "playlist-ssh.test", port = 2222, share = "backup")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        val audio = XFile(scheme, "/album/a.mp3", false, size = 123)
        register(Source(scheme, listOf(audio)))
        register(Source("file", emptyList(),
            "sftp://playlist-ssh.test:2222/backup/album/a.mp3\nsftp:/playlist-ssh.test:2222/backup/album/a.mp3\n"))
        val tracks = M3uPlaylist.tracks(app, XFile("file", "/list.m3u8", false))
        assertEquals(listOf(conn.label()), tracks.map { it.connLabel })
        assertEquals(listOf(audio), M3uPlaylist.parse(XFile("file", "/list.m3u8", false),
            resolveLocation = { line -> if (line.startsWith("sftp://")) audio else null }))
        assertEquals(listOf(audio.path), tracks.map { it.path })
    }

    @Test fun `content URIs bypass network location parsing`() {
        val uri = "content://provider/tree/music/document/track-123"
        register(Source("saf", emptyList()))
        register(Source("file", emptyList(), "$uri\nfile:///Music/a.mp3\nsmb://unknown/music/a.mp3\n"))
        val files = M3uPlaylist.parse(XFile("file", "/list.m3u8", false))
        assertEquals(listOf(XFile("saf", uri, false), XFile("file", "/Music/a.mp3", false)), files)
    }

    @Test fun `same source export stays relative when the connection has an alias`() {
        val conn = SavedConnection("smb", "playlist-relative.test", share = "Music", name = "NAS")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        val audio = XFile(scheme, "/album/a.mp3", false, size = 123)
        val text = PlaylistExport.render(XFile(scheme, "/lists", true),
            listOf(PlaylistTrack("conn", audio.path, conn.label()) to audio)) { conn }
        assertEquals("../album/a.mp3", text.lines()[2])
        register(Source(scheme, listOf(audio), text))
        val track = M3uPlaylist.tracks(app, XFile(scheme, "/lists/list.m3u8", false)).single()
        assertEquals(conn.label(), track.connLabel)
        assertEquals(audio.path, track.path)
        assertEquals(audio.size, track.size)
    }
}
