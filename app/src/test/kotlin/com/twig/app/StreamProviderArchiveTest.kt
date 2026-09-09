package com.twig.app

import android.app.Application
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.ZipFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ★ Handing another app a **compressed** archive entry through [StreamProvider] must copy it
 * out first, not proxy it.
 *
 * A DEFLATE entry has no positional read: `openRandom` falls back to `FileSystem`'s
 * "reopen and skip", which re-runs the whole decompression from byte 0 on every out-of-order
 * seek. A reader that seeks — a PDF reader reads the trailer at the tail before anything else —
 * turns that into an unbounded number of full decompressions, blocking the thread that called
 * `openFileDescriptor()` for all of it, which is how "no app can open this PDF" happened. A
 * STORED entry can be sliced in place and still streams. See docs/lessons/archives.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamProviderArchiveTest {

    private lateinit var app: Application
    private lateinit var provider: StreamProvider
    private lateinit var zip: File

    /** Compresses well, so a DEFLATE entry really is stored compressed rather than falling back to STORED. */
    private val payload = ByteArray(256 * 1024) { (it % 61).toByte() }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        FsRegistry.register(ZipFileSystem())
        zip = File(app.cacheDir, "box.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("deflated.pdf")) // default method is DEFLATE
            z.write(payload)
            z.closeEntry()
            z.putNextEntry(
                ZipEntry("stored.pdf").apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    compressedSize = payload.size.toLong()
                    crc = java.util.zip.CRC32().apply { update(payload) }.value
                },
            )
            z.write(payload)
            z.closeEntry()
        }
        provider = StreamProvider().apply {
            attachInfo(app, ProviderInfo().apply { authority = "${app.packageName}.stream" })
        }
    }

    private fun entry(name: String) = XFile(
        scheme = ZipFileSystem.SCHEME,
        path = "${zip.path}${ArchiveFileSystem.SEP}$name",
        isDir = false,
        size = payload.size.toLong(),
    )

    @Test
    fun `a compressed entry is copied out whole, not handed over as a proxy fd`() {
        val pfd = provider.openFile(StreamProvider.uriFor(app, entry("deflated.pdf")), "r")
        assertNotNull("the caller must get a real fd back", pfd)
        // Not `pfd.close()`: Robolectric's ParcelFileDescriptor shadow reflects into
        // java.io.FileDescriptor, which JDK 17+ refuses without --add-opens. Nothing under test.
        runCatching { pfd.close() }

        // The copy route leaves the entry in the cache directory; the proxy route touches no
        // file at all, so an empty directory here would mean reopen-and-skip is back.
        val cached = CacheDirs.dir(app, CacheDirs.OPEN).listFiles().orEmpty()
            .filter { it.name.endsWith("deflated.pdf") }
        assertEquals("exactly one materialized copy: ${cached.map { it.name }}", 1, cached.size)
        assertArrayEquals("the reader must see the entry's real bytes", payload, cached[0].readBytes())
    }

    /**
     * The decision itself, on both entry kinds. The STORED half cannot go through [provider]
     * here — that path ends in `StorageManager.openProxyFileDescriptor`, which needs a real
     * device — so this asserts the input that path is chosen by.
     */
    @Test
    fun `only the compressed entry claims to need copying`() {
        val zipFs = FsRegistry.of(ZipFileSystem.SCHEME) as ArchiveFileSystem
        org.junit.Assert.assertFalse(
            "a DEFLATE entry has no positional read — it must not be proxied",
            zipFs.fastRandom(entry("deflated.pdf")),
        )
        org.junit.Assert.assertTrue(
            "a STORED entry is sliceable in place and must keep streaming, no copy",
            zipFs.fastRandom(entry("stored.pdf")),
        )
    }
}
