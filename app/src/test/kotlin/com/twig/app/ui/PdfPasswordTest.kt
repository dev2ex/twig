package com.twig.app.ui

import android.app.Application
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34, 35], shadows = [PdfPasswordTest.RejectingRenderer::class, PdfPasswordTest.TestPage::class])
class PdfPasswordTest {
    @Implements(PdfRenderer::class)
    class RejectingRenderer {
        @Implementation fun __constructor__(fd: ParcelFileDescriptor) {
            attemptedFd = fd
            attempts++
            if (accepted == null || suppliedPassword != accepted) throw SecurityException("password required")
        }
        @Implementation(minSdk = 35) fun __constructor__(fd: ParcelFileDescriptor, params: LoadParams) {
            suppliedPassword = params.password
            __constructor__(fd)
        }
        @Implementation fun getPageCount() = 1
        @Implementation fun openPage(index: Int): PdfRenderer.Page =
            org.robolectric.shadow.api.Shadow.newInstanceOf(PdfRenderer.Page::class.java)
        @Implementation fun close() {}
        companion object {
            var accepted: String? = null
            var attemptedFd: ParcelFileDescriptor? = null
            var suppliedPassword: String? = null
            var attempts = 0
        }
    }

    @Implements(PdfRenderer.Page::class)
    class TestPage {
        @Implementation fun getWidth() = 420
        @Implementation fun getHeight() = 595
        @Implementation fun close() {}
    }

    @org.junit.Before fun resetRenderer() {
        RejectingRenderer.accepted = null
        RejectingRenderer.suppliedPassword = null
        RejectingRenderer.attempts = 0
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
        error("PDF operation did not complete")
    }

    private fun input(view: android.view.View): android.widget.EditText? {
        if (view is android.widget.EditText) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
            input(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test @Config(sdk = [35])
    fun `password is saved only on request after successful open and reused until rejected`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secrets = com.twig.app.secure.Secrets
        var wrapped: ByteArray? = null
        secrets.wrapper = object : com.twig.app.secure.Secrets.Wrapper {
            override fun available() = true
            override fun wrap(dek: ByteArray): String { wrapped = dek.copyOf(); return "test" }
            override fun unwrap(blob: String): ByteArray? = wrapped?.copyOf()
        }
        secrets.reset(app)
        val file = File(app.cacheDir, "remember-test.pdf").apply { writeText("%PDF-test") }
        val key = "file:${file.path}"
        val intent = android.content.Intent(app, PdfViewerActivity::class.java)
            .putExtra("scheme", "file").putExtra("path", file.path).putExtra("name", file.name)
        var host = org.robolectric.Robolectric.buildActivity(PdfViewerActivity::class.java, intent).setup()
        try {
            await { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
            var dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            // Successful manual input without the checkbox must remain temporary.
            RejectingRenderer.accepted = "correct"
            input(dialog.window!!.decorView)!!.setText("correct")
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            await { host.get().findViewById<android.view.View>(com.twig.app.R.id.loading).visibility == android.view.View.GONE }
            assertNull(com.twig.app.Prefs.pdfPassword(app, key))
            host.pause().stop().destroy()
            host = org.robolectric.Robolectric.buildActivity(PdfViewerActivity::class.java, intent).setup()
            await { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
            dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val field = input(dialog.window!!.decorView)!!
            ( (field.parent as android.widget.LinearLayout).getChildAt(1) as android.widget.CheckBox).isChecked = true
            field.setText("correct")
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            await { com.twig.app.Prefs.pdfPassword(app, key) == "correct" }
            val stored = app.getSharedPreferences(com.twig.app.Prefs.FILE_PDF_PW, 0).getString(key, null)!!
            assertTrue(stored.startsWith(secrets.PREFIX))
            host.pause().stop().destroy()
            // Reopening succeeds using the remembered password without showing a prompt.
            host = org.robolectric.Robolectric.buildActivity(PdfViewerActivity::class.java, intent).setup()
            await { host.get().findViewById<android.view.View>(com.twig.app.R.id.loading).visibility == android.view.View.GONE }
            assertFalse(org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing)
            assertEquals("correct", RejectingRenderer.suppliedPassword)
            host.pause().stop().destroy()
            // A changed password must clear the obsolete saved entry and ask again.
            RejectingRenderer.accepted = "changed"
            host = org.robolectric.Robolectric.buildActivity(PdfViewerActivity::class.java, intent).setup()
            await { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
            assertNull(com.twig.app.Prefs.pdfPassword(app, key))
        } finally {
            host.pause().stop().destroy()
            file.delete()
            com.twig.app.Prefs.setPdfPassword(app, key, null)
            secrets.reset(app)
            secrets.wrapper = com.twig.app.secure.Secrets.AndroidKeystore
        }
    }

    @Test fun `encrypted document preserves failure closes fd and does not retry through a cached copy`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "password-test.pdf").apply { writeText("%PDF-test") }
        RejectingRenderer.attempts = 0
        RejectingRenderer.suppliedPassword = null
        try {
            PdfDoc.open(app, XFile("file", file.path, false), "secret")
            fail("encrypted PDFs must report the password requirement")
        } catch (_: PdfDoc.PasswordRequired) {
            assertEquals(1, RejectingRenderer.attempts)
            assertFalse(RejectingRenderer.attemptedFd!!.fileDescriptor.valid())
            assertEquals(Build.VERSION.SDK_INT >= 35, PdfDoc.PASSWORD_SUPPORTED)
            assertEquals(if (Build.VERSION.SDK_INT >= 35) "secret" else null, RejectingRenderer.suppliedPassword)
        } finally { file.delete() }
    }
    @Test fun `password prompt follows platform support retries and cancels`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "prompt-test.pdf").apply { writeText("%PDF-test") }
        val intent = android.content.Intent(app, PdfViewerActivity::class.java)
            .putExtra("scheme", "file").putExtra("path", file.path).putExtra("name", file.name)
        val host = org.robolectric.Robolectric.buildActivity(PdfViewerActivity::class.java, intent).setup()
        fun awaitDialog(previous: android.app.Dialog? = null): androidx.appcompat.app.AlertDialog {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                if (dialog != null && dialog !== previous && dialog.isShowing) return dialog as androidx.appcompat.app.AlertDialog
                Thread.sleep(10)
            }
            error("No PDF password dialog appeared")
        }
        try {
            val dialog = awaitDialog()
            if (Build.VERSION.SDK_INT >= 35) {
                fun input(view: android.view.View): android.widget.EditText? {
                    if (view is android.widget.EditText) return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                        input(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                val field = input(dialog.window!!.decorView)!!
                assertFalse(field.isSaveEnabled)
                assertTrue(field.transformationMethod is android.text.method.PasswordTransformationMethod)
                assertEquals(android.view.View.GONE,
                    dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).visibility)
                val container = field.parent as android.widget.LinearLayout
                assertTrue(container.getChildAt(1) is android.widget.CheckBox)
                val remember = container.getChildAt(1) as android.widget.CheckBox
                assertFalse(remember.isChecked)
                assertEquals(host.get().getString(com.twig.app.R.string.archive_save_pw), remember.text.toString())
                assertEquals((20 * host.get().resources.displayMetrics.density).toInt(), container.paddingLeft)
                remember.isChecked = true
                field.setText("incorrect")
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
                val retry = awaitDialog(dialog)
                assertEquals("incorrect", RejectingRenderer.suppliedPassword)
                assertEquals(host.get().getString(com.twig.app.R.string.pdf_password_retry),
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                val retryInput = input(retry.window!!.decorView)!!
                assertEquals("", retryInput.text.toString())
                assertTrue(((retryInput.parent as android.widget.LinearLayout).getChildAt(1) as android.widget.CheckBox).isChecked)
                assertNull(com.twig.app.Prefs.pdfPassword(app, "file:${file.path}"))
                retry.cancel()
            } else {
                assertEquals(host.get().getString(com.twig.app.R.string.pdf_password_unsupported),
                    dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString())
                assertEquals(android.view.View.GONE,
                    dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).visibility)
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(host.get().isFinishing)
        } finally {
            host.pause().stop().destroy()
            file.delete()
        }
    }

}
