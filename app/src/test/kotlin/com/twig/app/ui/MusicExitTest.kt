package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class MusicExitTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun `quitting Twig stops the music service and closes music screens`() {
        MusicEngine.shutdown()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var closed = false
        val finisher: () -> Unit = { closed = true }
        MusicUi.register(finisher)
        try {
            AppExit.quit(activity.get())
            assertTrue(closed)
            val stopped = generateSequence { shadowOf(app).nextStoppedService }.toList()
            assertTrue(stopped.any { it.component?.className == MusicService::class.java.name })
            assertFalse(MusicEngine.hasQueue())
        } finally {
            MusicUi.unregister(finisher)
            activity.pause().stop().destroy()
        }
    }

    @Test fun `destroyed music service removes its notification and ignores a late update`() {
        MusicEngine.shutdown()
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        val controller = Robolectric.buildService(MusicService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(null, 0, 1)
            assertEquals(1, shadowOf(manager).allNotifications.size)
        } finally { controller.destroy() }
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        // A cover worker can finish after destruction and post back to the main thread.
        Handler(Looper.getMainLooper()).post {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "pushNotification")
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
