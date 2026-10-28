package com.twig.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.appcompat.app.AlertDialog
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], shadows = [PickerShareTest.PickerEnvironment::class])
class PickerShareTest {
    // Robolectric 4.14 has no shadow for this API and its real implementation needs a
    // framework-initialized external-directory array. Keep the picker at its permission gate.
    @org.robolectric.annotation.Implements(android.os.Environment::class)
    class PickerEnvironment : org.robolectric.shadows.ShadowEnvironment() {
        companion object {
            @JvmStatic
            @org.robolectric.annotation.Implementation(minSdk = 30)
            fun isExternalStorageManager() = false
        }
    }

    private val payload = ByteArray(512 * 1024 + 1) { (it % 251).toByte() }
    private fun waitUntil(check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for picker preparation")
    }

    private fun withPicker(multiple: Boolean = false, test: (org.robolectric.android.controller.ActivityController<PickerActivity>,
                                 XFile, CountDownLatch) -> Unit) {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions("com.twig.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", android.Manifest.permission.POST_NOTIFICATIONS)
        File(app.cacheDir, "share").deleteRecursively()
        val controller = Robolectric.buildActivity(PickerActivity::class.java,
            Intent(Intent.ACTION_GET_CONTENT).setType("*/*")).setup()
        val activity = controller.get()
        // Android 7 requests storage permission on creation; file selection below is driven directly.
        shadowOf(activity).nextStartedActivity
        shadowOf(activity.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        shadowOf(activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(false)
        val gate = CountDownLatch(1)
        FsRegistry.register(object : FileSystem {
            override val scheme = "picker-share-test"
            override val displayName = "Picker test"
            override fun root() = XFile(scheme, "/", true)
            override fun resolve(path: String) = XFile(scheme, path, false)
            override fun exists(file: XFile) = true
            override fun list(dir: XFile) = emptyList<XFile>()
            override fun openInput(file: XFile): ByteArrayInputStream {
                check(gate.await(10, TimeUnit.SECONDS))
                return ByteArrayInputStream(payload)
            }
            override fun openOutput(file: XFile, append: Boolean) = error("unused")
            override fun mkdir(parent: XFile, name: String) = error("unused")
            override fun delete(file: XFile) = error("unused")
            override fun rename(file: XFile, newName: String) = error("unused")
        })
        val file = XFile("picker-share-test", "/example.txt", false, payload.size.toLong(), lastModified = 1234)
        val service = Robolectric.buildService(TransferService::class.java).create()
        try {
            if (multiple) {
                PickerActivity::class.java.getDeclaredMethod("deliver", List::class.java).apply {
                    isAccessible = true
                }.invoke(activity, listOf(file, file.copy(path = "/second.txt")))
            } else assertTrue(activity.onPickFile(file))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activity.isFinishing)
            assertTrue(Transfers.ui is TransferBox)
            service.get().onStartCommand(null, 0, 1)
            test(controller, file, gate)
        } finally {
            Transfers.active?.cancel()
            gate.countDown()
            waitUntil { Transfers.active == null }
            Transfers.abandonPickerShare(controller.get().sharePickerId)
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
            service.destroy()
            Transfers.attach(null)
        }
    }

    @Test fun `small external selections and internal imports keep streaming directly`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val cases = listOf(
            Intent(Intent.ACTION_GET_CONTENT).setType("*/*") to 123L,
            PickerActivity.intent(app) to payload.size.toLong(),
        )
        for ((intent, size) in cases) {
            val controller = Robolectric.buildActivity(PickerActivity::class.java, intent).setup()
            try {
                val activity = controller.get()
                val file = XFile("sftp-picker-direct", "/direct.txt", false, size)
                assertTrue(activity.onPickFile(file))
                assertTrue(activity.isFinishing)
                assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
                assertEquals(com.twig.app.StreamProvider.uriFor(activity, file), shadowOf(activity).resultIntent.data)
                assertNull(Transfers.active)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun `external picker returns cached bytes to caller after rotation without a share chooser`() = withPicker { controller, _, gate ->
        val id = controller.get().sharePickerId
        controller.recreate()
        shadowOf(controller.get()).nextStartedActivity // storage permission request
        assertEquals(id, controller.get().sharePickerId)
        gate.countDown()
        waitUntil { controller.get().isFinishing }
        val activity = controller.get()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent
        assertTrue(result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val token = String(android.util.Base64.decode(result.data!!.pathSegments[0], android.util.Base64.URL_SAFE))
        assertTrue(token.startsWith("file\n${payload.size}\nexample.txt\n"))
        assertArrayEquals(payload, File(token.split("\n", limit = 4)[3]).readBytes())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun `background picker completion cannot be consumed by another screen`() = withPicker { controller, _, gate ->
        controller.pause()
        gate.countDown()
        waitUntil { Transfers.active == null }
        assertTrue(Transfers.hasPendingShare())
        assertNull(Transfers.consumeShare())
        assertFalse(controller.get().isFinishing)
        controller.resume()
        waitUntil { controller.get().isFinishing }
        assertEquals(Activity.RESULT_OK, shadowOf(controller.get()).resultCode)
    }

    @Test fun `cancel preparation keeps picker open without returning remote URI`() = withPicker { controller, _, gate ->
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        gate.countDown()
        waitUntil { Transfers.active == null }
        assertFalse(controller.get().isFinishing)
        assertNull(shadowOf(controller.get()).resultIntent)
        assertTrue(File(controller.get().cacheDir, "share").listFiles().orEmpty().none { it.name.startsWith(".partial-") })
    }

    @Test fun `leaving picker cancels preparation and discards its pending result`() = withPicker { controller, _, gate ->
        controller.get().finish()
        controller.pause().stop().destroy()
        assertTrue(Transfers.active!!.cancelled.get())
        gate.countDown()
        waitUntil { Transfers.active == null }
        assertNull(Transfers.consumeShare(controller.get().sharePickerId))
    }

    @Test fun `prepared multiple selection retains data and grants for every URI`() = withPicker(multiple = true) { controller, _, gate ->
        gate.countDown()
        waitUntil { controller.get().isFinishing }
        val result = shadowOf(controller.get()).resultIntent
        assertEquals(2, result.clipData!!.itemCount)
        assertEquals(result.clipData!!.getItemAt(0).uri, result.data)
        assertTrue(result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        for (index in 0..1) {
            val uri = result.clipData!!.getItemAt(index).uri
            val token = String(android.util.Base64.decode(uri.pathSegments[0], android.util.Base64.URL_SAFE))
            assertTrue(token.startsWith("file\n"))
            assertArrayEquals(payload, File(token.split("\n", limit = 4)[3]).readBytes())
        }
        assertNull(shadowOf(controller.get()).nextStartedActivity)
    }
}
