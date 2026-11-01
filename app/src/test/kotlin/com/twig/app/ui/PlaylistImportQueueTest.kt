package com.twig.app.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.twig.app.PlaylistStore
import com.twig.app.PlaylistTrack
import com.twig.core.XFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaylistImportQueueTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val first = PlaylistTrack("local", "/music/first.mp3", size = 100)
    private val second = PlaylistTrack("local", "/music/second.mp3", size = 200)

    @Before fun setup() {
        MusicEngine.shutdown()
        app.getSharedPreferences("twig_playlists", 0).edit().clear().commit()
    }
    @After fun cleanup() { MusicEngine.shutdown(); drain() }
    private fun field(name: String, value: Any) {
        MusicEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(MusicEngine, value)
    }
    private fun drain() {
        val executor = MusicEngine::class.java.getDeclaredField("io").apply { isAccessible = true }.get(MusicEngine) as ExecutorService
        executor.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun queue(): com.twig.app.Playlist {
        val playlist = PlaylistStore.create(app, "Mix", listOf(first))
        val file = XFile("file", first.path, false, size = first.size)
        val player = MusicEngine.ensurePlayer(app)
        field("queueId", playlist.id)
        field("tracks", listOf(first))
        field("files", listOf(file))
        player.setMediaSources(listOf(MediaSources.lazy(file)), 0, 1234)
        player.playWhenReady = false
        return playlist
    }

    @Test fun `append retains current track position pause state and original source`() {
        val playlist = queue()
        val player = MusicEngine.ensurePlayer(app)
        val original = player.getMediaItemAt(0)
        PlaylistStore.addTracks(app, playlist.id, listOf(second, second))
        MusicEngine.appendToQueue(app, playlist.id)
        drain()
        assertEquals(2, player.mediaItemCount)
        assertSame(original, player.getMediaItemAt(0))
        assertEquals(first.id, MusicEngine.currentTrack()!!.id)
        assertEquals(1234L, player.currentPosition)
        assertFalse(player.playWhenReady)
        MusicEngine.appendToQueue(app, playlist.id)
        drain()
        assertEquals(2, player.mediaItemCount)
    }

    @Test fun `pending append cannot recreate playback after exit`() {
        val playlist = queue()
        PlaylistStore.addTracks(app, playlist.id, listOf(second))
        MusicEngine.appendToQueue(app, playlist.id)
        MusicEngine.shutdown()
        drain()
        assertFalse(MusicEngine.hasQueue())
        assertEquals("", MusicEngine.queueId)
    }

    @Test fun `import during queue switch keeps tracks shared with previous queue`() {
        queue()
        val next = PlaylistStore.create(app, "Next", listOf(second))
        val executor = MusicEngine::class.java.getDeclaredField("io").apply { isAccessible = true }
            .get(MusicEngine) as ExecutorService
        val gate = java.util.concurrent.CountDownLatch(1)
        executor.execute { check(gate.await(5, TimeUnit.SECONDS)) }
        try {
            MusicEngine.play(app, next, 0, autoPlay = false)
            PlaylistStore.addTracks(app, next.id, listOf(first))
            MusicEngine.appendToQueue(app, next.id)
        } finally { gate.countDown() }
        drain()
        assertEquals(next.id, MusicEngine.queueId)
        assertEquals(2, MusicEngine.trackCount())
        assertEquals(listOf(second.id, first.id), (0 until MusicEngine.trackCount()).map { MusicEngine.trackAt(it)!!.id })
        assertFalse(MusicEngine.ensurePlayer(app).playWhenReady)
    }

    @Test fun `append cannot reintroduce a track removed while resolving`() {
        val playlist = queue()
        val player = MusicEngine.ensurePlayer(app)
        PlaylistStore.addTracks(app, playlist.id, listOf(second))
        MusicEngine.appendToQueue(app, playlist.id)
        PlaylistStore.removeTrack(app, playlist.id, second.id)
        drain()
        assertEquals(1, player.mediaItemCount)
    }
}
