package com.twig.app.ui

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.lifecycleScope
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.TrashStore
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class DeleteProgressTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val host = Robolectric.buildActivity(TrashUiTest.Host::class.java)
    private lateinit var directory: File
    private val release = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    private val deleted = mutableListOf<String>()

    @Before fun setup() {
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Prefs.setTrashEnabled(app, false)
        directory = File(app.cacheDir, "delete-progress").apply { mkdirs() }
        FsRegistry.register(LocalFileSystem())
        Transfers.attach(null)
        Transfers.consumeFinished()
        host.setup()
    }

    @After fun cleanup() {
        release.countDown()
        waitFor { Transfers.active == null }
        Transfers.attach(null)
        Transfers.consumeFinished()
        if (!host.get().isDestroyed) host.pause().stop().destroy()
        directory.deleteRecursively()
        FsRegistry.unregister("delete-test")
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("operation timed out", condition())
    }

    private fun blockingBackend(failFirst: Boolean = false) {
        FsRegistry.register(object : com.twig.core.FileSystem by LocalFileSystem() {
            override val scheme = "delete-test"
            override fun delete(file: XFile) {
                if (file.name == "first") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    if (failFirst) throw java.io.IOException("first failed")
                }
                synchronized(deleted) { deleted.add(file.name) }
            }
        })
    }

    private fun session(vararg names: String) = Transfers.Session(
        Transfers.Work.Delete(names.map { TrashStore.Target(XFile("delete-test", "/$it", false), null) }, false),
        R.string.strip_delete, "batch", R.drawable.ic_delete, null,
    )

    @Test fun `deletion survives its starting screen and notification restores the progress dialog`() {
        blockingBackend()
        val files = listOf("first", "second").map { XFile("delete-test", "/$it", false) }
        var callbacks = 0
        val previous = ShadowDialog.getLatestDialog()
        DeleteUi.confirm(host.get(), host.get(), files) { callbacks++ }
        waitFor { ShadowDialog.getLatestDialog() !== previous }
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor { Transfers.active != null }
        val session = Transfers.active!!
        val controller = Robolectric.buildService(TransferService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(null, 0, 1)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            val progress = ShadowDialog.getLatestDialog()!!
            assertEquals("first", progress.findViewById<TextView>(R.id.tv_file).text.toString())
            assertTrue(progress.findViewById<ProgressBar>(R.id.bar_file).isIndeterminate)
            assertEquals(android.view.View.GONE, progress.findViewById<TextView>(R.id.tv_total_size).visibility)
            val notification = shadowOf(service).lastForegroundNotification!!
            assertNotNull(notification.contentIntent)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            val intent = shadowOf(notification.contentIntent).savedIntent
            assertEquals(com.twig.app.MainActivity::class.java.name, intent.component!!.className)
            assertTrue(intent.getBooleanExtra("show_transfer", false))
            host.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(Transfers.ui)
            assertSame(session, Transfers.active)
            assertFalse(session.cancelled.get())
            val replacement = Robolectric.buildActivity(TrashUiTest.Host::class.java).setup()
            try {
                var finished = false
                TransferBox(replacement.get(), replacement.get().layoutInflater, session, { true }, {}, {}, { finished = true })
                assertEquals("first", ShadowDialog.getLatestDialog()!!.findViewById<TextView>(R.id.tv_file).text.toString())
                release.countDown()
                waitFor { shadowOf(service).isStoppedBySelf }
                assertTrue(finished)
                assertTrue(session.finished!!.isSuccess)
                assertEquals(listOf("first", "second"), deleted)
                assertEquals(1000, session.totalProgress)
                assertEquals(0, callbacks)
                assertFalse(shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications.any {
                    it.flags and Notification.FLAG_ONGOING_EVENT != 0
                })
            } finally { replacement.pause().stop().destroy() }
        } finally { release.countDown(); controller.destroy() }
    }

    @Test fun `hidden deletion still refreshes its living host and a second task cannot replace it`() {
        blockingBackend()
        val work = session("first", "second").work
        var finished: Boolean? = null
        DeleteUi.runConfirmed(host.get(), host.get().lifecycleScope, work) { finished = it }
        waitFor { Transfers.active != null }
        val running = Transfers.active!!
        val progress = ShadowDialog.getLatestDialog()!!
        progress.findViewById<android.view.View>(R.id.btn_background).performClick()
        assertNull(Transfers.ui)
        assertFalse(progress.isShowing)
        DeleteUi.runConfirmed(host.get(), host.get().lifecycleScope, work) { fail("busy task must not run") }
        assertSame(running, Transfers.active)
        release.countDown()
        Thread { Transfers.execute(app, running) {} }.start()
        waitFor { finished != null }
        assertEquals(true, finished)
        assertEquals(listOf("first", "second"), deleted)
    }

    @Test fun `cancel finishes the current backend operation and preserves later items`() {
        blockingBackend()
        val session = session("first", "second")
        assertTrue(Transfers.start(app, session))
        Thread { Transfers.execute(app, session) {} }.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        session.cancel()
        release.countDown()
        waitFor { Transfers.active == null }
        assertEquals(listOf("first"), deleted)
        assertEquals(500, session.totalProgress)
        assertEquals(1, session.filesLeft)
        assertTrue(session.completion.isCompleted)
    }

    @Test fun `failed items do not prevent the rest of a batch from being deleted`() {
        blockingBackend(failFirst = true)
        val session = session("first", "second")
        assertTrue(Transfers.start(app, session))
        release.countDown()
        Thread { Transfers.execute(app, session) {} }.start()
        waitFor { Transfers.active == null }
        assertTrue(session.finished!!.isFailure)
        assertTrue(session.finished!!.exceptionOrNull()!!.message!!.contains("first failed"))
        assertEquals(listOf("second"), deleted)
        assertEquals(1000, session.totalProgress)
    }

    @Test fun `deleting a directory delegates recursion to the backend and keeps sibling files`() {
        val folder = File(directory, "remove").apply { mkdirs() }
        File(folder, "nested").mkdirs()
        File(folder, "nested/data").writeText("delete")
        val keep = File(directory, "keep").apply { writeText("keep") }
        val target = TrashStore.Target(XFile("file", folder.path, true), null)
        val session = Transfers.Session(Transfers.Work.Delete(listOf(target), false), R.string.strip_delete, "", R.drawable.ic_delete, null)
        assertTrue(Transfers.start(app, session))
        Thread { Transfers.execute(app, session) {} }.start()
        waitFor { Transfers.active == null }
        assertTrue(session.finished?.exceptionOrNull()?.stackTraceToString(), session.finished!!.isSuccess)
        assertFalse(folder.exists())
        assertEquals("keep", keep.readText())
        assertEquals(0, session.dirsLeft)
        val notification = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications.single()
        assertEquals(app.getString(R.string.strip_delete), notification.extras.getString("android.title"))
        assertEquals(app.getString(R.string.msg_done), notification.extras.getString("android.text"))
    }
}
