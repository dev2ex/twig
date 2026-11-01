package com.twig.app.ui

import android.app.Application
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Playlist
import com.twig.app.PlaylistTrack
import com.twig.core.FsRegistry
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MusicQuickPlayTest {
    @Test fun `play request during paused queue restoration survives async resolution`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        MusicEngine.shutdown()
        var playWhenReady = false
        var playCalls = 0
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "setPlayWhenReady" -> { playWhenReady = args!![0] as Boolean; null }
                "play" -> { playWhenReady = true; playCalls++; null }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as ExoPlayer
        ReflectionHelpers.setField(MusicEngine, "player", player)
        val file = File(app.cacheDir, "quick-play.mp3").apply { writeBytes(byteArrayOf(0)) }
        FsRegistry.register(LocalFileSystem())
        try {
            val queue = Playlist(Playlist.NOW, "Queue", listOf(PlaylistTrack("local", file.path)))
            MusicEngine.play(app, queue, 0, autoPlay = false)
            assertTrue(MusicEngine.loadingQueue)
            MusicEngine.resumePlayback(app)
            assertEquals(0, playCalls)
            val executor = ReflectionHelpers.getField<ExecutorService>(MusicEngine, "io")
            executor.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(MusicEngine.hasQueue())
            assertTrue(playWhenReady)
            MusicEngine.resumePlayback(app)
            assertTrue(playWhenReady)
            assertEquals(1, playCalls)
        } finally {
            MusicEngine.shutdown()
            file.delete()
        }
    }
}
