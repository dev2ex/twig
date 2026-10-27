package com.twig.app

import android.app.Application
import android.content.pm.ProviderInfo
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class SharePreparationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val contents = mutableMapOf<String, ByteArray>()
    private var reads = 0
    private var failAt: String? = null

    @Before fun setup() {
        File(app.cacheDir, "share").deleteRecursively()
        reads = 0
        contents.clear()
        FsRegistry.register(object : FileSystem {
            override val scheme = "share-test"
            override val displayName = "Share test"
            override fun exists(file: XFile) = true
            override fun root() = XFile(scheme, "/", true)
            override fun resolve(path: String) = XFile(scheme, path, false)
            override fun list(dir: XFile) = emptyList<XFile>()
            override fun openInput(file: XFile): InputStream {
                reads++
                if (file.path == failAt) throw IOException("Read failed")
                return ByteArrayInputStream(contents.getValue(file.path))
            }
            override fun openOutput(file: XFile, append: Boolean) = error("unused")
            override fun mkdir(parent: XFile, name: String) = error("unused")
            override fun delete(file: XFile) = error("unused")
            override fun rename(file: XFile, newName: String) = error("unused")
        })
    }

    private fun source(path: String, data: ByteArray, known: Boolean = true): XFile {
        contents[path] = data
        return XFile("share-test", path, false, if (known) data.size.toLong() else 0, lastModified = 1234)
    }

    @Test fun `mixed sources preserve order names and bytes without recopying local files`() {
        val local = File(app.cacheDir, "local.txt").apply { writeText("local") }
        val files = listOf(source("/a/same.txt", "one".toByteArray()),
            XFile("file", local.path, false, local.length()), source("/b/same.txt", "two".toByteArray()))
        val progress = mutableListOf<SharePreparation.Progress>()
        val ready = SharePreparation.prepare(app, files, { false }, progress::add)
        assertEquals(listOf("same.txt", "local.txt", "same.txt"), ready.files.map { it.name })
        assertEquals(files[1], ready.files[1])
        assertNotEquals(ready.files[0].path, ready.files[2].path)
        assertEquals(listOf("one", "local", "two"), ready.files.map { File(it.path).readText() })
        assertEquals(2, reads)
        assertEquals(6L, progress.last().done)
        assertEquals(6L, progress.last().total)
        assertEquals(2, progress.last().count)
        val provider = Robolectric.buildContentProvider(StreamProvider::class.java).create(ProviderInfo().apply {
            authority = "${app.packageName}.stream"
        }).get()
        val uri = StreamProvider.uriFor(app, ready.files.first())
        provider.query(uri, null, null, null, null).use {
            assertTrue(it.moveToFirst())
            assertEquals("same.txt", it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
        }
        val descriptor = provider.openFile(uri, "r")
        assertEquals(3L, descriptor.statSize)
        // As in StreamProviderArchiveTest, JDK 17 blocks Robolectric reflection during close.
        runCatching { descriptor.close() }
        ready.release()
    }

    @Test fun `unknown size reports bytes without pretending a total is known`() {
        val reports = mutableListOf<SharePreparation.Progress>()
        SharePreparation.prepare(app, listOf(source("/unknown.bin", ByteArray(12345), false)),
            { false }, reports::add).release()
        assertEquals(12345L, reports.last().done)
        assertEquals(0L, reports.last().total)
    }

    @Test fun `cancel during copying removes all partial files`() {
        var cancelled = false
        try {
            SharePreparation.prepare(app, listOf(source("/large", ByteArray(3 shl 20))), { cancelled }) {
                if (it.done > 0) cancelled = true
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(File(app.cacheDir, "share").listFiles()!!.isEmpty())
    }

    @Test fun `failure in later file keeps complete entries but removes partial files`() {
        val files = listOf(source("/one", byteArrayOf(1)), source("/two", byteArrayOf(2)))
        failAt = "/two"
        try {
            SharePreparation.prepare(app, files, { false }) {}
            fail("Expected failure")
        } catch (_: IOException) { }
        val complete = File(app.cacheDir, "share").listFiles()!!.single()
        assertEquals(listOf<Byte>(1), complete.readBytes().toList())
        val reused = SharePreparation.prepare(app, files.take(1), { false }) {}
        assertEquals(2, reads)
        reused.release()
    }

    @Test fun `incomplete source is not offered for sharing`() {
        val file = source("/truncated", byteArrayOf(1)).copy(size = 10)
        try {
            SharePreparation.prepare(app, listOf(file), { false }) {}
            fail("Expected incomplete file failure")
        } catch (_: IOException) { }
        assertTrue(File(app.cacheDir, "share").listFiles()!!.isEmpty())
    }

    @Test fun `new batches retain recent shares and only remove expired cache`() {
        val files = listOf(source("/one", byteArrayOf(1)))
        val first = SharePreparation.prepare(app, files, { false }) {}
        val recent = File(first.files.single().path)
        first.release()
        val old = File(app.cacheDir, "share/expired").apply { writeText("old") }
        old.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        val empty = File(app.cacheDir, "share/expired-empty").apply {
            writeText("")
            setLastModified(old.lastModified())
        }
        SharePreparation.prepare(app, files, { false }) {}.release()
        assertTrue(recent.exists())
        assertFalse(old.exists())
        assertFalse(empty.exists())
    }

    @Test fun `same metadata reuses complete bytes and refreshes last access`() {
        val file = source("/reuse", "cached".toByteArray())
        val first = SharePreparation.prepare(app, listOf(file), { false }) {}
        val path = first.files.single().path
        first.release()
        val cached = File(path)
        val previous = System.currentTimeMillis() - 60 * 60 * 1000L
        cached.setLastModified(previous)
        val second = SharePreparation.prepare(app, listOf(file), { false }) {
            fail("A cache hit must not copy data")
        }
        assertFalse(first.cacheOnly)
        assertTrue(second.cacheOnly)
        assertEquals(path, second.files.single().path)
        assertEquals(1, reads)
        assertTrue(cached.lastModified() > previous)
        second.release()
    }

    @Test fun `size time and source path changes each prepare a separate version`() {
        val file = source("/a/same", byteArrayOf(1))
        val first = SharePreparation.prepare(app, listOf(file), { false }) {}
        val changedTime = SharePreparation.prepare(app, listOf(file.copy(lastModified = 5678)), { false }) {}
        val otherPath = SharePreparation.prepare(app, listOf(source("/b/same", byteArrayOf(2))), { false }) {}
        contents[file.path] = byteArrayOf(3, 4)
        val changedSize = SharePreparation.prepare(app, listOf(file.copy(size = 2)), { false }) {}
        val results = listOf(first, changedTime, otherPath, changedSize)
        assertEquals(4, results.map { it.files.single().path }.distinct().size)
        assertEquals(4, reads)
        assertEquals(listOf<Byte>(1), File(first.files.single().path).readBytes().toList())
        results.forEach { it.release() }
    }

    @Test fun `unknown metadata is not reused`() {
        val file = source("/unknown-time", byteArrayOf(1)).copy(lastModified = 0)
        val first = SharePreparation.prepare(app, listOf(file), { false }) {}
        val second = SharePreparation.prepare(app, listOf(file), { false }) {}
        assertNotEquals(first.files.single().path, second.files.single().path)
        assertEquals(2, reads)
        first.release(); second.release()
    }

    @Test fun `truncated cache is replaced rather than reused`() {
        val file = source("/repair", byteArrayOf(1, 2, 3))
        val first = SharePreparation.prepare(app, listOf(file), { false }) {}
        first.release()
        File(first.files.single().path).writeBytes(byteArrayOf(1))
        val second = SharePreparation.prepare(app, listOf(file), { false }) {}
        assertEquals(listOf<Byte>(1, 2, 3), File(second.files.single().path).readBytes().toList())
        assertEquals(2, reads)
        second.release()
    }

    @Test fun `expired idle entries are prepared again while held entries survive pruning`() {
        val file = source("/expiry", byteArrayOf(1))
        val first = SharePreparation.prepare(app, listOf(file), { false }) {}
        val held = File(first.files.single().path)
        held.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        SharePreparation.prepare(app, listOf(source("/other", byteArrayOf(2))), { false }) {}.release()
        assertTrue(held.exists())
        first.release()
        held.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        SharePreparation.prepare(app, listOf(file), { false }) {}.release()
        assertEquals(3, reads)
    }

    @Test fun `duplicate selections are read once and preserve their positions`() {
        val file = source("/duplicate", byteArrayOf(1, 2))
        val reports = mutableListOf<SharePreparation.Progress>()
        val prepared = SharePreparation.prepare(app, listOf(file, file), { false }, reports::add)
        assertEquals(1, reads)
        assertEquals(2, prepared.files.size)
        assertEquals(prepared.files[0], prepared.files[1])
        assertEquals(2L, reports.last().total)
        assertEquals(2L, reports.last().done)
        prepared.release()
    }

    @Test fun `same path on different backends cannot reuse another sources bytes`() {
        val file = source("/same-server-path", byteArrayOf(1))
        val first = SharePreparation.prepare(app, listOf(file), { false }) {}
        val original = FsRegistry.of(file)
        FsRegistry.register(object : FileSystem by original { override val scheme = "share-other" })
        contents[file.path] = byteArrayOf(2)
        val second = SharePreparation.prepare(app, listOf(file.copy(scheme = "share-other")), { false }) {}
        assertNotEquals(first.files.single().path, second.files.single().path)
        assertEquals(listOf<Byte>(1), File(first.files.single().path).readBytes().toList())
        assertEquals(listOf<Byte>(2), File(second.files.single().path).readBytes().toList())
        assertEquals(2, reads)
        first.release(); second.release()
    }

    @Test fun `ordinary capacity trimming still protects the callers current file`() {
        val directory = File(app.cacheDir, "trim-test").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val current = File(directory, "current").apply { writeBytes(ByteArray(60)); setLastModified(1000) }
        val older = File(directory, "older").apply { writeBytes(ByteArray(50)); setLastModified(2000) }
        CacheDirs.trim(directory, keep = current, cap = 100)
        assertTrue(current.exists())
        assertFalse(older.exists())
        directory.deleteRecursively()
    }
}
