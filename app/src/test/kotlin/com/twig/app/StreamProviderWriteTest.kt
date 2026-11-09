package com.twig.app

import android.app.Application
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.ZipFileSystem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ★ An editor handed a document must also be able to **save back** (2026-11-09).
 *
 * Opening an `.xlsx` from Nextcloud through Twig into Collabora left it read-only, twice
 * measured: the intent carried `FLAG_GRANT_READ_URI_PERMISSION` only, and `openFile` answered
 * every `"w"` with `SecurityException`. The fix has two halves that must agree — the grant on
 * the intent and what the provider can serve — and [StreamProvider.canWriteBack] is the single
 * place both read from, so they cannot drift apart and promise a write that then fails.
 *
 * What may be written is deliberately narrow: a writable local file (real fd) and an SAF
 * document (delegated to the document's own provider). A streamed source or an archive entry
 * has no writable descriptor at all — the proxy is read-only by design — so it stays view-only
 * instead of being handed a grant it cannot honour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamProviderWriteTest {

    private lateinit var app: Application
    private lateinit var provider: StreamProvider
    private lateinit var writable: File
    private lateinit var readOnly: File
    private lateinit var zip: File

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        FsRegistry.register(ZipFileSystem())
        provider = StreamProvider().apply {
            attachInfo(app, ProviderInfo().apply { authority = "${app.packageName}.stream" })
        }
        val dir = File(app.cacheDir, "write").apply { mkdirs() }
        writable = File(dir, "editable.txt").apply { writeText("before") }
        readOnly = File(dir, "locked.txt").apply {
            writeText("before")
            setWritable(false)
        }
        zip = File(dir, "box.zip").apply {
            ZipOutputStream(outputStream()).use { z ->
                z.putNextEntry(ZipEntry("inside.txt"))
                z.write("before".toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun local(f: File) = XFile(scheme = "file", path = f.absolutePath, isDir = false, size = f.length())

    private fun entry(name: String) = XFile(
        scheme = ZipFileSystem.SCHEME,
        path = "${zip.path}${ArchiveFileSystem.SEP}$name",
        isDir = false,
        size = 6,
    )

    /** Only the two sources that own their bytes may be offered a write grant. */
    @Test
    fun `only a writable local file and an SAF document are announced as writable`() {
        assertTrue("a writable local file owns its bytes", StreamProvider.canWriteBack(local(writable)))
        assertFalse(
            "a 0444 file must not be offered a write the OS would refuse",
            StreamProvider.canWriteBack(local(readOnly)),
        )
        assertFalse(
            "an archive entry has no writable descriptor — it must stay view-only",
            StreamProvider.canWriteBack(entry("inside.txt")),
        )
        assertFalse(
            "a directory is never an editor's target",
            StreamProvider.canWriteBack(XFile("file", app.cacheDir.absolutePath, isDir = true)),
        )
    }

    /**
     * The regression itself: this call used to throw `SecurityException` before the file was
     * even looked at, which is what left every editor read-only. The descriptor itself is not
     * written through here — Robolectric's `ParcelFileDescriptor` shadow reflects into
     * `java.io.FileDescriptor`, which JDK 17+ refuses without `--add-opens`, so the assertion
     * stops at "the provider hands one over".
     */
    @Test
    fun `a writable local file answers a write request with a descriptor`() {
        val pfd = provider.openFile(StreamProvider.uriFor(app, local(writable)), "rw")
        assertNotNull("an editable file must get a descriptor back", pfd)
        // Not `pfd.close()`: see the comment above.
        runCatching { pfd.close() }
    }

    @Test
    fun `a read-only local file still refuses a write`() {
        assertFalse("the fixture must really be read-only", readOnly.canWrite())
        expectRefusal(local(readOnly), "w")
    }

    @Test
    fun `an archive entry still refuses a write`() {
        expectRefusal(entry("inside.txt"), "rwt")
    }

    private fun expectRefusal(file: XFile, mode: String) {
        try {
            provider.openFile(StreamProvider.uriFor(app, file), mode)
            fail("expected a SecurityException for ${file.name} in mode $mode")
        } catch (expected: SecurityException) {
            assertTrue(
                "the message must be the human-readable one: ${expected.message}",
                expected.message == app.getString(R.string.stream_read_only),
            )
        }
    }
}
