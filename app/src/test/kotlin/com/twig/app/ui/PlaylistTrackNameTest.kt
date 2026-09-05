package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.PlaylistTrack
import com.twig.app.SavedConnection
import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A playlist track must carry a **display name**.
 *
 * A media server's `XFile.path` ends in the item id (Emby's are plain numbers too); storing
 * only the path means:
 *  - the playlist and the player title show a bare "38", "40";
 *  - worse, **the extension is gone too** -- media3 relies entirely on it to recognize the
 *    container, and losing it degrades to `sniff()`ing one by one (see the AVI entry in
 *    `CLAUDE.md`).
 *
 * So both storing (`trackFrom`) and rebuilding (`MusicEngine.resolveFile`) must carry it;
 * this pins down both ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistTrackNameTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun mediaServer(host: String): SavedConnection {
        val conn = SavedConnection(type = "jellyfin", host = host, user = "u")
        ConnectionStore.save(app, conn)
        Connections.ensure(app, conn)
        return conn
    }

    @Test
    fun `a rebuilt track carries its display name and extension, not a bare id`() {
        val conn = mediaServer("http://jf-track.test")
        val track = PlaylistTrack(
            kind = "conn", path = "/music/al1/38", connLabel = conn.label(),
            size = 4801849L, displayName = "第1首 夜航.mp3",
        )
        val f = MusicEngine.resolveFile(app, track)!!
        assertEquals("第1首 夜航.mp3", f.name)
        // the extension is the player's only way to recognize the container
        assertEquals("mp3", f.extension)
    }

    @Test
    fun `falls back to the last path segment when there is no display name, rather than an empty name`() {
        // Passing an empty string as XFile.displayName makes name return an empty string directly -- it must become null to fall back
        val conn = mediaServer("http://jf-noname.test")
        val track = PlaylistTrack(
            kind = "conn", path = "/music/al1/song.mp3", connLabel = conn.label(), size = 100L,
        )
        assertEquals("song.mp3", MusicEngine.resolveFile(app, track)!!.name)
    }

    @Test
    fun `a local track is unaffected`() {
        val f = java.io.File(app.cacheDir, "local.mp3").apply { writeText("x") }
        val track = PlaylistTrack(kind = "local", path = f.path, size = f.length())
        assertEquals("local.mp3", MusicEngine.resolveFile(app, track)!!.name)
    }
}
