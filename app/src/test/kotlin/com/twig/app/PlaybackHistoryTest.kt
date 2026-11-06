package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackHistoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val file = XFile("file", "/movies/1.mp4", false, size = 123456L)
    private val conn = SavedConnection("webdav", "https://playback-history.invalid")

    @Before fun setup() {
        PlaybackStore.clear(app)
        Prefs.setResumePlayback(app, true)
    }

    @After fun cleanup() {
        PlaybackStore.clear(app)
        ConnectionStore.remove(app, conn)
        Connections.drop(Connections.schemeOf(conn))
        Prefs.setResumePlayback(app, true)
    }

    @Test fun `old progress resumes but is not listed until played again`() {
        val old = JSONObject().apply {
            put("key", PlaybackStore.keyOf(file))
            put("name", file.name)
            put("pos", 30000L)
            put("dur", 180000L)
            put("at", 1L)
        }
        app.getSharedPreferences("twig_playback", 0).edit()
            .putString("list", JSONArray().put(old).toString()).commit()
        assertEquals(30000L, PlaybackStore.positionFor(app, file))
        assertTrue(PlaybackStore.recent(app).isEmpty())
        PlaybackStore.save(app, file, 40000L, 180000L)
        assertEquals(file.path, PlaybackStore.recent(app).single().path)
    }

    @Test fun `matching video across sources shares progress and updates reopen location`() {
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        // Reuse a fake registered filesystem so this test never makes a network call.
        FsRegistry.register(object : FileSystem by LocalFileSystem() {
            override val scheme = scheme
        })
        Connections.ensure(app, conn)
        PlaybackStore.save(app, file, 30000L, 180000L)
        val remote = file.copy(scheme = scheme, path = "/videos/1.mp4")
        assertEquals(30000L, PlaybackStore.positionFor(app, remote))
        PlaybackStore.save(app, remote, 60000L, 180000L)
        val mark = PlaybackStore.recent(app).single()
        assertEquals(remote.path, mark.path)
        assertEquals(conn.label(), mark.connLabel)
        assertEquals(mark, PlaybackMark.fromJson(mark.toJson()))
        assertEquals(60000L, PlaybackStore.positionFor(app, file))
        assertEquals(0L, PlaybackStore.positionFor(app, file.copy(size = file.size + 1)))
        // Offline or deleted connections must not silently remove the saved entry.
        Connections.drop(scheme)
        ConnectionStore.remove(app, conn)
        assertEquals(mark, PlaybackStore.recent(app).single())
        PlaybackStore.save(app, file, 70000L, 180000L)
        assertEquals("", PlaybackStore.recent(app).single().connLabel)
        assertEquals(file.path, PlaybackStore.recent(app).single().path)
    }

    @Test fun `removing and clearing history preserve progress and another play unhides it`() {
        val other = file.copy(path = "/movies/2.mp4")
        PlaybackStore.save(app, file, 30000L, 180000L)
        PlaybackStore.save(app, other, 40000L, 180000L)
        PlaybackStore.hide(app, PlaybackStore.keyOf(file))
        assertEquals(other.name, PlaybackStore.recent(app).single().name)
        assertEquals(30000L, PlaybackStore.positionFor(app, file))
        PlaybackStore.hideRecent(app)
        assertTrue(PlaybackStore.recent(app).isEmpty())
        assertEquals(40000L, PlaybackStore.positionFor(app, other))
        PlaybackStore.save(app, file, 50000L, 180000L)
        assertEquals(file.name, PlaybackStore.recent(app).single().name)
    }

    @Test fun `finished barely started and short videos leave no progress or history`() {
        for ((pos, duration) in listOf(180000L to 180000L, 170000L to 180000L,
            15000L to 80000L, 14000L to 180000L, 3540000L to 3600000L)) {
            PlaybackStore.save(app, file, 30000L, 180000L)
            PlaybackStore.save(app, file, pos, duration)
            assertTrue("$pos / $duration", PlaybackStore.all(app).isEmpty())
            assertTrue(PlaybackStore.recent(app).isEmpty())
        }
    }

    @Test fun `session-only sources retain progress without a reopenable entry`() {
        val temporary = file.copy(scheme = "archive-session")
        PlaybackStore.save(app, temporary, 30000L, 180000L)
        assertEquals(30000L, PlaybackStore.positionFor(app, temporary))
        assertTrue(PlaybackStore.recent(app).isEmpty())
    }

    @Test fun `disabling resume hides entries without deleting saved progress`() {
        PlaybackStore.save(app, file, 30000L, 180000L)
        Prefs.setResumePlayback(app, false)
        assertTrue(PlaybackStore.recent(app).isEmpty())
        assertEquals(0L, PlaybackStore.positionFor(app, file))
        Prefs.setResumePlayback(app, true)
        assertEquals(file.path, PlaybackStore.recent(app).single().path)
        assertEquals(30000L, PlaybackStore.positionFor(app, file))
    }
    private val next = file.copy(path = "/movies/2.mp4", size = 234567L)

    @Test fun `completion persists an unstarted next episode without a resume position`() {
        PlaybackStore.save(app, file, 30000, 180000)
        PlaybackStore.completeEpisode(app, file, next)
        val mark = PlaybackStore.recent(app).single()
        assertEquals(next.path, mark.path)
        assertTrue(mark.pendingNext)
        assertEquals(mark, PlaybackMark.fromJson(mark.toJson()))
        assertEquals(0L, PlaybackStore.positionFor(app, next))
        assertEquals(0L, PlaybackStore.positionFor(app, file))
        // Saving before the position threshold must preserve the destination.
        PlaybackStore.save(app, next, 12000, 180000)
        assertEquals(mark, PlaybackStore.recent(app).single())
        PlaybackStore.save(app, next, 20000, 180000)
        assertFalse(PlaybackStore.recent(app).single().pendingNext)
        assertEquals(20000L, PlaybackStore.positionFor(app, next))
        PlaybackStore.completeEpisode(app, next, null)
        assertTrue(PlaybackStore.recent(app).isEmpty())
    }

    @Test fun `duration metadata fills pending total without saving progress or unhiding history`() {
        PlaybackStore.completeEpisode(app, file, next)
        PlaybackStore.hide(app, PlaybackStore.keyOf(next))
        val before = PlaybackStore.all(app).single()
        PlaybackStore.updatePendingDuration(app, next, 180000)
        assertEquals(before.copy(durMs = 180000), PlaybackStore.all(app).single())
        assertEquals(0L, PlaybackStore.positionFor(app, next))
        PlaybackStore.updatePendingDuration(app, next, 0)
        assertEquals(180000L, PlaybackStore.all(app).single().durMs)
        PlaybackStore.completeEpisode(app, file, next)
        assertEquals(180000L, PlaybackStore.recent(app).single().durMs)
        PlaybackStore.save(app, next, 30000, 180000)
        val progress = PlaybackStore.all(app).single()
        PlaybackStore.updatePendingDuration(app, next, 200000)
        assertEquals(progress, PlaybackStore.all(app).single())
    }

    @Test fun `completion keeps genuine next episode progress and unhides its entry`() {
        PlaybackStore.save(app, next, 40000, 180000)
        PlaybackStore.hide(app, PlaybackStore.keyOf(next))
        PlaybackStore.completeEpisode(app, file, next)
        assertFalse(PlaybackStore.recent(app).single().pendingNext)
        assertEquals(40000L, PlaybackStore.positionFor(app, next))
    }

    @Test fun `pending destinations support hide clear and disabled resume`() {
        PlaybackStore.completeEpisode(app, file, next)
        PlaybackStore.hide(app, PlaybackStore.keyOf(next))
        assertTrue(PlaybackStore.recent(app).isEmpty())
        PlaybackStore.completeEpisode(app, file, next)
        PlaybackStore.hideRecent(app)
        assertTrue(PlaybackStore.recent(app).isEmpty())
        PlaybackStore.clear(app)
        Prefs.setResumePlayback(app, false)
        PlaybackStore.completeEpisode(app, file, next)
        assertTrue(PlaybackStore.all(app).isEmpty())
    }

    @Test fun `temporary mounts cannot create next episode history destinations`() {
        PlaybackStore.completeEpisode(app, file, next.copy(scheme = "archive-session"))
        assertTrue(PlaybackStore.all(app).isEmpty())
    }

}
