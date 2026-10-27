package com.twig.app.ui

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import com.twig.app.PlaylistTrack
import com.twig.app.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaSession
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class,
    shadows = [MusicNotificationExitTest.RecordingSession::class])
class MusicNotificationExitTest {
    @Implements(MediaSession::class)
    class RecordingSession : ShadowMediaSession() {
        var state: PlaybackState? = null
        var callback: MediaSession.Callback? = null
        @Implementation fun setPlaybackState(value: PlaybackState?) { state = value }
        @Implementation fun setCallback(value: MediaSession.Callback?, handler: Handler?) { callback = value }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var released = false
    private val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java)) { _, method, _ ->
        when (method.name) {
            "isPlaying" -> true
            "release" -> { released = true; null }
            "getDuration" -> 300_000L
            "getCurrentPosition" -> 30_000L
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as ExoPlayer
    private var closed = false
    private val finisher: () -> Unit = { closed = true }

    @Before fun setup() {
        MusicEngine.shutdown()
        manager.cancelAll()
        ReflectionHelpers.setField(MusicEngine, "player", player)
        ReflectionHelpers.setField(MusicEngine, "tracks", listOf(PlaylistTrack("local", "/music/test.mp3")))
        MusicUi.register(finisher)
    }

    @After fun cleanup() {
        MusicUi.unregister(finisher)
        MusicEngine.shutdown()
        manager.cancelAll()
    }

    private fun session(service: MusicService): RecordingSession = Shadow.extract(
        ReflectionHelpers.getField<MediaSession>(service, "session"),
    )

    private fun verifyExited(service: MusicService) {
        assertTrue(closed)
        assertTrue(released)
        assertFalse(MusicEngine.isPlaying())
        assertFalse(MusicEngine.hasQueue())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(generateSequence { shadowOf(app).nextStoppedService }.any {
            it.component?.className == MusicService::class.java.name
        })
        // A delayed state/cover callback cannot bring the notification back before destruction.
        ReflectionHelpers.callInstanceMethod<Unit>(service, "pushNotification")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun `notification Exit follows Like and closes the music player without restarting foreground`() {
        val controller = Robolectric.buildService(MusicService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(null, 0, 1)
            val notification = shadowOf(manager).allNotifications.single()
            assertEquals(5, notification.actions.size)
            assertEquals(app.getString(R.string.music_add_fav), notification.actions[3].title)
            assertEquals(R.drawable.ic_heart_notif, notification.actions[3].getIcon().resId)
            assertEquals(app.getString(R.string.music_exit), notification.actions[4].title)
            val exit = shadowOf(notification.actions[4].actionIntent).savedIntent
            assertEquals("com.twig.app.music.EXIT", exit.action)
            assertEquals(MusicService::class.java.name, exit.component!!.className)
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(exit, 0, 2))
            verifyExited(service)
        } finally { controller.destroy() }
    }

    @Test fun `system media session offers Exit after Like and routes it through the same full exit`() {
        val controller = Robolectric.buildService(MusicService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(null, 0, 1)
            val recorded = session(service)
            val actions = requireNotNull(recorded.state).customActions
            assertEquals(listOf("com.twig.app.music.FAV", "com.twig.app.music.EXIT"), actions.map { it.action })
            assertEquals(R.drawable.ic_close_notif, actions.last().icon)
            requireNotNull(recorded.callback).onCustomAction(actions.last().action, null)
            verifyExited(service)
        } finally { controller.destroy() }
    }

    @Test fun `temporary tracks keep Exit available without exposing Like`() {
        ReflectionHelpers.setField(MusicEngine, "tracks", listOf(PlaylistTrack("temporary", "/archive/song.mp3")))
        val controller = Robolectric.buildService(MusicService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(null, 0, 1)
            val notification = shadowOf(manager).allNotifications.single()
            assertEquals(4, notification.actions.size)
            assertEquals(app.getString(R.string.music_exit), notification.actions.last().title)
            assertEquals(listOf("com.twig.app.music.EXIT"), requireNotNull(session(service).state).customActions.map { it.action })
        } finally { controller.destroy() }
    }
}
