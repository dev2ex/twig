package com.twig.app.ui

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.twig.app.OpenFiles
import com.twig.app.R
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class ShareTransfersTest {
    private val payload = ByteArray(512 * 1024 + 1) { (it % 251).toByte() }
    private fun waitUntil(check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for preparation")
    }

    private fun withPendingShare(size: Long = payload.size.toLong(), test: (org.robolectric.android.controller.ActivityController<SettingsActivity>,
                                       CountDownLatch, AtomicInteger, CountDownLatch) -> Unit) {
        Transfers.attach(null)
        while (Transfers.hasPendingShare()) Transfers.consumeShare()?.preparedShare?.release()
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions("com.twig.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", android.Manifest.permission.POST_NOTIFICATIONS)
        File(app.cacheDir, "share").deleteRecursively()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        shadowOf(activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(false)
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val opens = AtomicInteger()
        val closed = CountDownLatch(1)
        FsRegistry.register(object : FileSystem {
            override val scheme = "share-dialog-test"
            override val displayName = "Share test"
            override fun exists(file: XFile) = true
            override fun root() = XFile(scheme, "/", true)
            override fun resolve(path: String) = XFile(scheme, path, false)
            override fun list(dir: XFile) = emptyList<XFile>()
            override fun openInput(file: XFile): ByteArrayInputStream {
                opens.incrementAndGet()
                entered.countDown()
                check(gate.await(10, TimeUnit.SECONDS))
                return object : ByteArrayInputStream(payload) {
                    override fun close() { super.close(); closed.countDown() }
                }
            }
            override fun openOutput(file: XFile, append: Boolean) = error("unused")
            override fun mkdir(parent: XFile, name: String) = error("unused")
            override fun delete(file: XFile) = error("unused")
            override fun rename(file: XFile, newName: String) = error("unused")
        })
        val service = Robolectric.buildService(TransferService::class.java).create()
        try {
            OpenFiles.share(activity, XFile("share-dialog-test", "/example.txt", false, size, lastModified = 1234))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Transfers.active!!.isShare)
            assertTrue(Transfers.ui is TransferBox)
            service.get().onStartCommand(null, 0, 1)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertNull(shadowOf(activity).nextStartedActivity)
            val notifications = shadowOf(activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications
            assertTrue(notifications.any { it.extras.getString(android.app.Notification.EXTRA_TITLE) == activity.getString(R.string.share_preparing) })
            test(controller, gate, opens, closed)
        } finally {
            Transfers.active?.cancel()
            gate.countDown()
            waitUntil { Transfers.active == null }
            runCatching { controller.pause().stop().destroy() }
            service.destroy()
            while (Transfers.hasPendingShare()) Transfers.consumeShare()?.preparedShare?.release()
            Transfers.attach(null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun `shared service survives rotation and waits for resume before handing off`() = withPendingShare { controller, gate, opens, _ ->
        val session = Transfers.active!!
        assertEquals(controller.get().getString(R.string.share_preparing),
            (ShadowDialog.getLatestDialog() as AlertDialog).findViewById<TextView>(R.id.tv_title)!!.text.toString())
        controller.recreate()
        assertSame(session, Transfers.active)
        assertEquals(1, opens.get())
        controller.pause()
        assertNull(Transfers.ui)
        gate.countDown()
        waitUntil { Transfers.hasPendingShare() }
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.resume()
        var chooser: Intent? = null
        waitUntil { chooser = shadowOf(controller.get()).nextStartedActivity; chooser != null }
        val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        val token = String(android.util.Base64.decode(uri.pathSegments[0], android.util.Base64.URL_SAFE))
        assertTrue(token.startsWith("file\n${payload.size}\nexample.txt\n"))
        val cached = File(token.split("\n", limit = 4)[3])
        assertArrayEquals(payload, cached.readBytes())
        assertEquals(1, opens.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertFalse(Transfers.hasPendingShare())
        assertNull(session.preparedShare)
        assertTrue(cached.exists())
    }

    @Test fun `shared cancel prevents handoff and removes partial files`() = withPendingShare { controller, gate, _, closed ->
        val session = Transfers.active!!
        val cache = File(controller.get().cacheDir, "share")
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.cancelled.get())
        gate.countDown()
        assertTrue(closed.await(10, TimeUnit.SECONDS))
        waitUntil { Transfers.active == null }
        assertTrue(cache.listFiles().orEmpty().none { it.name.startsWith(".partial-") })
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertNull(session.preparedShare)
    }

    @Test fun `background button detaches shared box and allows notification reopening`() = withPendingShare { controller, gate, _, _ ->
        val activity = controller.get()
        (ShadowDialog.getLatestDialog() as AlertDialog).findViewById<android.view.View>(R.id.btn_background)!!.performClick()
        assertNull(Transfers.ui)
        assertNotNull(Transfers.active)
        ShareTransfers.attach(activity, reveal = true)
        assertTrue(Transfers.ui is TransferBox)
        gate.countDown()
        waitUntil { Transfers.active == null }
        assertNotNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun `another transfer cannot start while preparing a share`() = withPendingShare { controller, gate, _, _ ->
        val current = Transfers.active!!
        val other = Transfers.Session(Transfers.Work.Share(emptyList()), R.string.share_preparing, "", R.drawable.ic_share, null)
        assertFalse(Transfers.start(controller.get(), other))
        assertSame(current, Transfers.active)
        gate.countDown()
        waitUntil { Transfers.active == null }
    }

    @Test fun `completed share survives an ordinary operation finishing before return`() = withPendingShare { controller, gate, _, _ ->
        controller.pause()
        gate.countDown()
        waitUntil { Transfers.hasPendingShare() }
        val copy = Transfers.Session(Transfers.Work.Copy(emptyList(), XFile("file", controller.get().cacheDir.path, true), false, false),
            R.string.progress_copy, "", R.drawable.ic_copy, null)
        Transfers.execute(controller.get(), copy) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(copy.finished!!.isSuccess)
        assertSame(copy, Transfers.consumeFinished())
        assertTrue(Transfers.hasPendingShare())
        controller.resume()
        waitUntil { shadowOf(controller.get()).nextStartedActivity != null }
        assertFalse(Transfers.hasPendingShare())
    }

    @Test fun `locked completion waits and can be handed off on another resumed screen`() = withPendingShare { controller, gate, _, _ ->
        val keyguard = controller.get().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setKeyguardLocked(true)
        gate.countDown()
        waitUntil { Transfers.hasPendingShare() }
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop()
        val replacement = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            shadowOf(keyguard).setKeyguardLocked(false)
            ShareTransfers.attach(replacement.get(), reveal = true)
            assertNotNull(shadowOf(replacement.get()).nextStartedActivity)
            assertFalse(Transfers.hasPendingShare())
        } finally { replacement.pause().stop().destroy(); controller.start().resume() }
    }

    @Test fun `share preparation suppresses completion toast while preserving background notification and vibration`() = withPendingShare { controller, gate, _, _ ->
        val activity = controller.get()
        val foregroundNotifications = com.twig.app.Prefs.foregroundCompletionNotifications(activity)
        val vibrate = com.twig.app.Prefs.vibrateOnCompletion(activity)
        val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        shadowOf(vibrator).setHasVibrator(true)
        vibrator.cancel()
        com.twig.app.Prefs.setForegroundCompletionNotifications(activity, true)
        com.twig.app.Prefs.setVibrateOnCompletion(activity, true)
        try {
            controller.pause()
            org.robolectric.shadows.ShadowToast.reset()
            gate.countDown()
            waitUntil { Transfers.active == null }
            assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertTrue(shadowOf(vibrator).isVibrating)
            assertEquals(1, shadowOf(activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications.size)
        } finally {
            com.twig.app.Prefs.setForegroundCompletionNotifications(activity, foregroundNotifications)
            com.twig.app.Prefs.setVibrateOnCompletion(activity, vibrate)
            controller.resume()
        }
    }

    @Test fun `repeated sharing uses cache without vibrating or displaying completion toast`() = withPendingShare { controller, gate, opens, _ ->
        val activity = controller.get()
        gate.countDown()
        waitUntil { Transfers.active == null }
        assertNotNull(shadowOf(activity).nextStartedActivity)
        val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        shadowOf(vibrator).setHasVibrator(true)
        vibrator.cancel()
        val previous = com.twig.app.Prefs.vibrateOnCompletion(activity)
        com.twig.app.Prefs.setVibrateOnCompletion(activity, true)
        org.robolectric.shadows.ShadowToast.reset()
        val service = Robolectric.buildService(TransferService::class.java).create()
        try {
            OpenFiles.share(activity, XFile("share-dialog-test", "/example.txt", false, payload.size.toLong(), lastModified = 1234))
            shadowOf(Looper.getMainLooper()).idle()
            service.get().onStartCommand(null, 0, 2)
            waitUntil { Transfers.active == null }
            assertEquals(1, opens.get())
            assertFalse(shadowOf(vibrator).isVibrating)
            assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertNotNull(shadowOf(activity).nextStartedActivity)
        } finally {
            service.destroy()
            com.twig.app.Prefs.setVibrateOnCompletion(activity, previous)
        }
    }

    @Test fun `unknown size keeps the preparation flow`() = withPendingShare(size = 0) { controller, gate, _, _ ->
        assertTrue(Transfers.active!!.isShare)
        gate.countDown()
        waitUntil { Transfers.active == null }
        assertNotNull(shadowOf(controller.get()).nextStartedActivity)
    }
}
