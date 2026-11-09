package com.twig.app

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class OpenFilesEditTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var local: File
    private lateinit var stream: StreamProvider
    private lateinit var documents: Documents
    private val tree = DocumentsContract.buildTreeDocumentUri("edit.test", "folder")
    private val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "folder/sheet.xlsx")
    private val saf get() = XFile("saf", uri.toString(), false, displayName = "sheet.xlsx")
    private val hosts = mutableListOf<org.robolectric.android.controller.ActivityController<Activity>>()

    @Before fun setup() {
        local = File(app.cacheDir, "sheet.xlsx").apply { writeText("original document") }
        stream = StreamProvider().apply {
            attachInfo(app, ProviderInfo().apply { authority = app.packageName + ".stream"; grantUriPermissions = true })
        }
        documents = Documents(local).apply {
            attachInfo(app, ProviderInfo().apply { authority = "edit.test"; grantUriPermissions = true; exported = true })
        }
        ShadowContentResolver.registerProviderInternal("edit.test", documents)
        app.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        shadowOf(android.webkit.MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }
    }

    @After fun cleanup() { hosts.forEach { it.pause().stop().destroy() } }
    private fun file() = XFile("file", local.path, false, local.length())
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().also(hosts::add).get()
    private fun writable(intent: Intent) = intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
    private fun replace(provider: ContentProvider, uri: Uri) {
        val output = ParcelFileDescriptor.AutoCloseOutputStream(provider.openFile(uri, "rwt")!!)
        try {
            output.write("edited".toByteArray())
        } finally {
            // JDK 17 blocks Robolectric's descriptor-close reflection, as in StreamProviderArchiveTest.
            try { output.close() } catch (e: RuntimeException) {
                if (e.javaClass.name != "java.lang.reflect.InaccessibleObjectException") throw e
            }
        }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected read-only access") } catch (_: SecurityException) { }
    }
    private fun handler(intent: Intent, name: String): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply { packageName = "office.test"; this.name = name }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, this)
    }

    @Test fun `local document grants editing and writes the original file`() {
        val intent = OpenFiles.externalIntent(app, file())
        assertEquals(Intent.ACTION_EDIT, intent.action)
        assertTrue(writable(intent))
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        replace(stream, intent.data!!)
        assertEquals("edited", local.readText())
    }

    @Test fun `local text source code and configuration files save back to the original`() {
        for (name in listOf("notes.txt", "data.json", "config.yaml", "script.py", "README.md")) {
            local = File(app.cacheDir, name).apply { writeText("original") }
            val intent = OpenFiles.externalIntent(app, file())
            assertEquals(name, Intent.ACTION_EDIT, intent.action)
            assertTrue(name, writable(intent))
            replace(stream, intent.data!!)
            assertEquals(name, "edited", local.readText())
        }
    }

    @Test fun `SAF text files retain the original URI and content write requirements`() {
        val text = saf.copy(displayName = "notes.txt")
        val intent = OpenFiles.externalIntent(app, text)
        assertEquals(Intent.ACTION_EDIT, intent.action)
        assertEquals(uri, intent.data)
        assertTrue(writable(intent))
        replace(documents, intent.data!!)
        assertEquals("edited", local.readText())
        documents.flags = DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        assertFalse(writable(OpenFiles.externalIntent(app, text)))
    }

    @Test fun `local editing preserves filenames with newlines and percent escapes`() {
        local = File(app.cacheDir, "sheet%20\ncopy.xlsx").apply { writeText("original") }
        val intent = OpenFiles.externalIntent(app, file())
        stream.query(intent.data!!, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null).use {
            assertTrue(it.moveToFirst())
            assertEquals(local.name, it.getString(0))
        }
        replace(stream, intent.data!!)
        assertEquals("edited", local.readText())
    }

    @Test fun `sharing URI cannot be upgraded into an editing URI`() {
        val read = StreamProvider.uriFor(app, file())
        rejected { replace(stream, read) }
        rejected { replace(stream, read.buildUpon().appendQueryParameter("edit", "1").build()) }
        val edit = OpenFiles.externalIntent(app, file()).data!!
        rejected { stream.openFile(edit.buildUpon().clearQuery().build(), "r") }
        assertEquals("original document", local.readText())
    }

    @Test fun `SAF hands over the original document with write permission`() {
        val intent = OpenFiles.externalIntent(app, saf)
        assertEquals(Intent.ACTION_EDIT, intent.action)
        assertEquals(uri, intent.data)
        assertTrue(writable(intent))
        replace(documents, intent.data!!)
        assertEquals("edited", local.readText())
    }

    @Test fun `rename and delete capability does not imply content editing`() {
        documents.flags = DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        assertTrue(SafFileSystem(app).stat(uri.toString())!!.canWrite)
        val intent = OpenFiles.externalIntent(app, saf)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertFalse(writable(intent))
        assertEquals(app.packageName + ".stream", intent.data!!.authority)
    }

    @Test fun `read-only or revoked SAF grants do not offer editing`() {
        app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse(writable(OpenFiles.externalIntent(app, saf)))
        app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(writable(OpenFiles.externalIntent(app, saf)))
    }

    @Test fun `remote documents archives media and missing local files keep read-only handoff`() {
        for (f in listOf(file().copy(scheme = "webdav:test"), file().copy(scheme = "smb:test"),
            file().copy(scheme = "sftp:test"), file().copy(scheme = "zip:test"),
            file().copy(path = local.path + ".missing"), file().copy(displayName = "video.mp4"))) {
            val intent = OpenFiles.externalIntent(app, f)
            assertEquals(f.toString(), Intent.ACTION_VIEW, intent.action)
            assertFalse(writable(intent))
            rejected { replace(stream, intent.data!!) }
        }
    }

    @Test fun `normal and forced chooser openings prefer editing when a handler exists`() {
        val host = activity()
        handler(OpenFiles.externalIntent(host, file()), "Editor")
        assertTrue(OpenFiles.openWith(host, file()))
        assertEquals(Intent.ACTION_EDIT, shadowOf(host).nextStartedActivity.action)
        assertTrue(OpenFiles.openWith(host, file(), forceChooser = true))
        val chooser = shadowOf(host).nextStartedActivity
        assertEquals(Intent.ACTION_EDIT, chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.action)
    }

    @Test fun `apps handling only VIEW can still open writable documents`() {
        val host = activity()
        val target = OpenFiles.externalIntent(host, file())
        handler(Intent(target).setAction(Intent.ACTION_VIEW), "Viewer")
        assertTrue(OpenFiles.openWith(host, file(), forceChooser = true))
        val launched = shadowOf(host).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertTrue(writable(launched))
        assertTrue(OpenFiles.openWithComponent(host, file(), ComponentName("office.test", "Viewer")))
        assertEquals(Intent.ACTION_VIEW, shadowOf(host).nextStartedActivity.action)
    }

    @Test fun `shortcut candidates include editors and viewers once and dispatch the right action`() {
        val host = activity()
        val edit = OpenFiles.externalIntent(host, file())
        handler(edit, "Editor")
        handler(Intent(edit).setAction(Intent.ACTION_VIEW), "Editor")
        handler(Intent(edit).setAction(Intent.ACTION_VIEW), "Viewer")
        assertEquals(listOf("Editor", "Viewer"), OpenFiles.resolveViewers(host, file()).map { it.activityInfo.name })
        assertTrue(OpenFiles.openWithComponent(host, file(), ComponentName("office.test", "Editor")))
        assertEquals(Intent.ACTION_EDIT, shadowOf(host).nextStartedActivity.action)
    }

    private class Documents(private val file: File) : ContentProvider() {
        var flags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
            MatrixCursor(projection!!).apply {
                addRow(projection.map { when (it) {
                    DocumentsContract.Document.COLUMN_FLAGS -> flags
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "sheet.xlsx"
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    DocumentsContract.Document.COLUMN_SIZE -> file.length()
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    else -> null
                } })
            }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        override fun getType(uri: Uri) = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unused")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("unused")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("unused")
    }
}
