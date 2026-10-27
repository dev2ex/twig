package com.twig.app.ui

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 34], application = Application::class)
class MusicNotificationTimeTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var playing = true
    private var position = 65_000L
    private val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java)) { _, method, _ ->
        when (method.name) {
            "isPlaying" -> playing
            "getCurrentPosition" -> position
            "getDuration" -> 300_000L
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as ExoPlayer

    @Before fun setup() {
        MusicEngine.shutdown()
        manager.cancelAll()
        ReflectionHelpers.setField(MusicEngine, "player", player)
    }

    @After fun cleanup() {
        MusicEngine.shutdown()
        shadowOf(Looper.getMainLooper()).idle()
        manager.cancelAll()
    }

    private fun notification(): Notification = shadowOf(manager).allNotifications.single()

    private fun assertTimer(notification: Notification, expectedPosition: Long) {
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals((System.currentTimeMillis() - expectedPosition).toDouble(), notification.`when`.toDouble(), 100.0)
        assertEquals("android.app.Notification\$MediaStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNotNull(notification.extras.getParcelable<android.media.session.MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION))
    }

    @Test fun `native timer starts at the playback position without periodic notification updates`() {
        val controller = Robolectric.buildService(MusicService::class.java).create()
        try {
            controller.get().onStartCommand(null, 0, 1)
            val original = notification()
            val title = original.extras.getCharSequence(Notification.EXTRA_TITLE)
            assertTimer(original, position)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertSame(original, notification())
            assertEquals(title, notification().extras.getCharSequence(Notification.EXTRA_TITLE))
        } finally { controller.destroy() }
    }

    @Test fun `pause hides the timer and resume or seek rebases it without counting paused time`() {
        val controller = Robolectric.buildService(MusicService::class.java).create()
        val service = controller.get()
        val listener = ReflectionHelpers.getField<MusicEngine.Listener>(service, "listener")
        try {
            service.onStartCommand(null, 0, 1)
            assertTimer(notification(), position)
            playing = false
            listener.onPlayStateChanged(false)
            assertFalse(notification().extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertFalse(notification().extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
            assertEquals(0L, notification().`when`)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            playing = true
            listener.onPlayStateChanged(true)
            assertTimer(notification(), position)
            position = 130_000L
            listener.onPositionChanged()
            assertTimer(notification(), position)
            position = 0L
            listener.onTrackChanged(1, null)
            assertTimer(notification(), 0L)
        } finally { controller.destroy() }
    }

    @Test fun `player seeks notify the service even while paused`() {
        ReflectionHelpers.setField(MusicEngine, "player", null)
        val real = MusicEngine.ensurePlayer(app)
        val controller = Robolectric.buildService(MusicService::class.java).create()
        try {
            real.setMediaItem(MediaItem.fromUri("https://example.invalid/unprepared.mp3"))
            controller.get().onStartCommand(null, 0, 1)
            val before = notification()
            real.seekTo(45_000L)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotSame(before, notification())
            assertEquals(45_000L, MusicEngine.positionMs())
            assertFalse(notification().extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        } finally { controller.destroy() }
    }
}
