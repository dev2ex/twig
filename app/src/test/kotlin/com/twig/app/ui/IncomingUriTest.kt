package com.twig.app.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** What the exported entry points accept from other apps ([IncomingUri]). */
@RunWith(RobolectricTestRunner::class)
class IncomingUriTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** ★ A sender must not be able to make Twig read its own private files for it. */
    @Test
    fun `file URIs into the app's own data are refused, however they are spelled`() {
        val key = File(app.filesDir, "keys/id_rsa").apply { parentFile!!.mkdirs(); writeText("k") }
        assertFalse(IncomingUri.allowed(app, Uri.fromFile(key)))
        val sneaky = File(app.filesDir, "../files/./keys/id_rsa")
        assertFalse(IncomingUri.allowed(app, Uri.parse("file://" + sneaky.path)))
        assertFalse(IncomingUri.allowed(app, Uri.fromFile(app.dataDir)))
    }

    @Test
    fun `other file URIs and content URIs pass, other schemes do not`() {
        val outside = File(System.getProperty("java.io.tmpdir"), "twig-incoming.txt").apply { writeText("x") }
        assertTrue(IncomingUri.allowed(app, Uri.fromFile(outside)))
        assertTrue(IncomingUri.allowed(app, Uri.parse("content://media/external/file/1")))
        assertFalse(IncomingUri.allowed(app, Uri.parse("http://example.com/a.txt")))
        assertFalse(IncomingUri.allowed(app, Uri.parse("file:")))
    }
}
