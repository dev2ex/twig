package com.twig.app.ui

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.MainActivity
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveWriter
import com.twig.fs.archive.ZipFileSystem
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowVibrator
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class OperationFeedbackTest {
    class Host : AppCompatActivity() {
        override fun onCreate(state: Bundle?) { setTheme(R.style.Theme_Twig); super.onCreate(state) }
    }

    private lateinit var app: Application
    private lateinit var host: ActivityController<Host>
    private lateinit var folder: File
    private var paused = false
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val vibrator get() = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
        app.registerActivityLifecycleCallbacks(OperationFeedback)
        shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(false)
        shadowOf(vibrator).setHasVibrator(true)
        vibrator.cancel()
        manager.cancelAll()
        ShadowToast.reset()
        Transfers.attach(null)
        Transfers.consumeFinished()
        FsRegistry.register(LocalFileSystem())
        folder = File(app.cacheDir, "operation-feedback").apply { mkdirs() }
        host = Robolectric.buildActivity(Host::class.java).setup()
    }

    @After fun cleanup() {
        Transfers.active?.let { it.cancel(); Transfers.execute(app, it) {} }
        shadowOf(Looper.getMainLooper()).idle()
        Transfers.attach(null)
        Transfers.consumeFinished()
        if (!paused) host.pause()
        host.stop().destroy()
        app.unregisterActivityLifecycleCallbacks(OperationFeedback)
        folder.deleteRecursively()
        FsRegistry.unregister("file")
    }

    private fun feedback(cancelled: Boolean = false) = OperationFeedback.finished(
        app, "Completed", "Done", "Destination", cancelled = cancelled,
    )

    private fun session(move: Boolean = false): Transfers.Session {
        val source = File(folder, "source.txt").apply { writeText("real bytes") }
        val dest = File(folder, "destination").apply { mkdirs() }
        val file = FsRegistry.of("file").stat(source.path)!!
        return Transfers.Session(
            Transfers.Work.Copy(listOf(file), XFile("file", dest.path, true), move, false),
            R.string.progress_copy, dest.path, R.drawable.ic_copy, null,
        )
    }

    @Test fun `foreground defaults to one toast without notification or vibration`() {
        feedback()
        assertEquals("Done", ShadowToast.getTextOfLatestToast())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test fun `foreground notification and vibration are independent opt ins`() {
        Prefs.setForegroundCompletionNotifications(app, true)
        feedback()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertFalse(shadowOf(vibrator).isVibrating)
        manager.cancelAll()
        Prefs.setForegroundCompletionNotifications(app, false)
        Prefs.setVibrateOnCompletion(app, true)
        feedback()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(80, shadowOf(vibrator).milliseconds)
    }

    @Test fun `leaving Twig shows a toast and retains a completion notification`() {
        host.pause(); paused = true
        feedback()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Done", ShadowToast.getTextOfLatestToast())
    }

    @org.robolectric.annotation.Implements(className = "android.os.SystemVibrator", isInAndroidSdk = false)
    class RecordingVibrator : org.robolectric.shadows.ShadowSystemVibrator() {
        var legacyAudio: android.media.AudioAttributes? = null

        @org.robolectric.annotation.Implementation(maxSdk = 25)
        override fun vibrate(uid: Int, packageName: String?, milliseconds: Long, audio: android.media.AudioAttributes?) {
            legacyAudio = audio
            super.vibrate(uid, packageName, milliseconds, audio)
        }
    }

    @Test
    @Config(shadows = [RecordingVibrator::class])
    fun `enabled completion vibration uses alert policy in silent mode in foreground and background`() {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audio.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
        Prefs.setVibrateOnCompletion(app, true)
        fun assertCompletionVibration() {
            vibrator.cancel()
            feedback()
            val recorded = shadowOf(vibrator)
            assertTrue(recorded.isVibrating)
            assertEquals(80, recorded.milliseconds)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                assertEquals(android.os.VibrationAttributes.USAGE_ALARM,
                    (recorded.vibrationAttributesFromLastVibration as android.os.VibrationAttributes).usage)
            } else {
                assertEquals(android.media.AudioAttributes.USAGE_ALARM,
                    requireNotNull((recorded as RecordingVibrator).legacyAudio).usage)
            }
        }
        assertCompletionVibration()
        host.pause(); paused = true
        assertCompletionVibration()
    }

    @Test fun `screen off counts as background even before the Activity pauses`() {
        shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(false)
        Prefs.setVibrateOnCompletion(app, true)
        feedback()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Done", ShadowToast.getTextOfLatestToast())
        assertEquals(80, shadowOf(vibrator).milliseconds)
    }

    @Test fun `lock screen counts as background with the screen still on`() {
        shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(true)
        feedback()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Done", ShadowToast.getTextOfLatestToast())
    }

    @Test fun `another Twig screen remains foreground after the first screen pauses`() {
        host.pause(); paused = true
        val second = Robolectric.buildActivity(Host::class.java).setup()
        try {
            feedback()
            assertEquals("Done", ShadowToast.getTextOfLatestToast())
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
        } finally { second.pause().stop().destroy() }
    }

    @Test fun `a new operation clears the preceding retained completion`() {
        Prefs.setForegroundCompletionNotifications(app, true)
        feedback()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        OperationFeedback.clear(app)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun `cancellation never vibrates or retains a completion notification`() {
        Prefs.setForegroundCompletionNotifications(app, true)
        Prefs.setVibrateOnCompletion(app, true)
        feedback(cancelled = true)
        assertEquals("Done", ShadowToast.getTextOfLatestToast())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test fun `open copy dialog finishing cannot race into a completion notification`() {
        val session = session()
        assertTrue(Transfers.start(app, session))
        var finished = 0
        TransferBox(host.get(), host.get().layoutInflater, session, { true }, {}, {}, { finished++ })
        Transfers.execute(app, session) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, finished)
        assertNull(Transfers.ui)
        assertTrue(session.finished!!.isSuccess)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("real bytes", File(folder, "destination/source.txt").readText())
    }

    @Test fun `a hidden copy dialog still gets a foreground toast and one vibration`() {
        Prefs.setVibrateOnCompletion(app, true)
        val session = session(move = true)
        assertTrue(Transfers.start(app, session))
        Transfers.attach(null)
        Transfers.execute(app, session) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.finished!!.isSuccess)
        assertEquals(1, ShadowToast.shownToastCount())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(80, shadowOf(vibrator).milliseconds)
        assertFalse(File(folder, "source.txt").exists())
        assertSame(session, Transfers.consumeFinished())
    }

    @Test fun `packing an archive uses the same completion vibration`() {
        Prefs.setVibrateOnCompletion(app, true)
        val copy = session().work as Transfers.Work.Copy
        val target = copy.dest.copy(path = copy.dest.path + "/packed.zip", isDir = false)
        val session = Transfers.Session(
            Transfers.Work.Compress(copy.items, copy.dest, target, ArchiveWriter.Format.ZIP, false),
            R.string.progress_compress, copy.dest.path, R.drawable.ic_compress, null,
        )
        Transfers.execute(app, session) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.finished?.exceptionOrNull()?.stackTraceToString(), session.finished!!.isSuccess)
        assertTrue(File(target.path).length() > 0)
        assertEquals(80, shadowOf(vibrator).milliseconds)
    }

    @Test fun `failed foreground transfer notifies even with completion notifications disabled`() {
        Prefs.setForegroundCompletionNotifications(app, false)
        val session = session()
        File(folder, "source.txt").delete()
        assertTrue(Transfers.start(app, session))
        var finished = 0
        TransferBox(host.get(), host.get().layoutInflater, session, { true }, {}, {}, { finished++ })
        Transfers.execute(app, session) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.finished!!.isFailure)
        assertEquals(1, finished)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(app.getString(R.string.transfer_failed), notification.extras.getString("android.title"))
        assertFalse(notification.extras.getString("android.text").isNullOrBlank())
        assertNotNull(notification.contentIntent)
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test fun `cancellation suppresses failure notifications too`() {
        OperationFeedback.finished(app, "Failed", "Cancelled", cancelled = true, failed = true)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test fun `failed background transfer reports its error instead of a success result`() {
        val session = session()
        File(folder, "source.txt").delete()
        host.pause(); paused = true
        Transfers.execute(app, session) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.finished!!.isFailure)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(app.getString(R.string.transfer_failed), notification.extras.getString("android.title"))
        assertFalse(notification.extras.getString("android.text").isNullOrBlank())
        assertFalse(ShadowToast.getTextOfLatestToast().isNullOrBlank())
    }

    @Test fun `extracting an archive also uses the completion vibration`() {
        val copy = session().work as Transfers.Work.Copy
        val archive = File(folder, "source.zip")
        java.util.zip.ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(java.util.zip.ZipEntry("unpacked.txt"))
            it.write("archive bytes".toByteArray())
            it.closeEntry()
        }
        val zip = ZipFileSystem()
        FsRegistry.register(zip)
        try {
            val root = zip.rootOf(FsRegistry.of("file").stat(archive.path)!!)
            Prefs.setVibrateOnCompletion(app, true)
            ShadowVibrator.reset()
            shadowOf(vibrator).setHasVibrator(true)
            val work = Transfers.Work.Copy(zip.list(root), copy.dest, false, false)
            val session = Transfers.Session(work, R.string.progress_copy, copy.dest.path, R.drawable.ic_copy, null)
            Transfers.execute(app, session) {}
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(session.finished?.exceptionOrNull()?.stackTraceToString(), session.finished!!.isSuccess)
            assertEquals("archive bytes", File(copy.dest.path, "unpacked.txt").readText())
            assertEquals(80, shadowOf(vibrator).milliseconds)
        } finally { FsRegistry.unregister(zip.scheme) }
    }

    private fun serviceCompletion(background: Boolean) {
        if (background) { host.pause(); paused = true }
        val session = session()
        assertTrue(Transfers.start(app, session))
        val controller = Robolectric.buildService(TransferService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(null, 0, 1)
            val progress = shadowOf(service).lastForegroundNotification!!
            assertNotNull(progress.contentIntent)
            assertTrue(progress.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            val deadline = System.nanoTime() + 5_000_000_000
            while (!shadowOf(service).isStoppedBySelf && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(session.finished!!.isSuccess)
            assertEquals(if (background) 1 else 0, shadowOf(manager).allNotifications.size)
            assertFalse(shadowOf(manager).allNotifications.any {
                it.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
            })
        } finally { controller.destroy() }
    }

    @Test fun `foreground service keeps progress available and removes it on foreground completion`() = serviceCompletion(false)

    @Test fun `background service replaces progress with the retained result`() = serviceCompletion(true)

    @Test
    @Config(sdk = [28])
    fun `main file view consumes hidden-dialog completion immediately without a second toast`() {
        host.pause(); paused = true
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE, "com.twig.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        Prefs.setRememberLocation(app, false)
        val main = Robolectric.buildActivity(MainActivity::class.java)
        try {
            main.setup()
            val session = session()
            assertTrue(Transfers.start(app, session))
            Transfers.execute(app, session) {}
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(session.finished!!.isSuccess)
            assertNull(Transfers.consumeFinished())
            assertEquals(1, ShadowToast.shownToastCount())
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
        } finally { main.pause().stop().destroy() }
    }
}
