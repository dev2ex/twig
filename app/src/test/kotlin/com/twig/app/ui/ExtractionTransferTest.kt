package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ExtractionTransferTest {
    private lateinit var app: Application
    private lateinit var target: File
    private lateinit var local: LocalFileSystem

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        target = File(app.cacheDir, "extraction-transfer").apply { deleteRecursively(); mkdirs() }
        local = LocalFileSystem()
        FsRegistry.register(local)
    }

    @After fun cleanup() {
        target.deleteRecursively()
        FsRegistry.unregister("extract-transfer-source")
        Transfers.consumeFinished()
    }

    private fun execute(session: Transfers.Session) {
        val worker = Thread { Transfers.execute(app, session) {} }
        worker.start()
        worker.join(5000)
        assertFalse(worker.isAlive)
    }

    private fun session(items: List<XFile>) = Transfers.Session(
        Transfers.Work.Copy(items, local.resolve(target.path), false, false, destinationFolder = "bundle"),
        R.string.progress_extract, target.path + "/bundle", R.drawable.ic_folder, null)

    @Test fun retryReusesTheWrapperAndSkipsPreviouslyCompletedFiles() {
        val source = FakeFileSystem("extract-transfer-source", mapOf("/" to listOf("a", "b")),
            mapOf("/a" to "first", "/b" to "second"))
        var fail = true
        FsRegistry.register(object : FileSystem by source {
            override fun openInput(file: XFile): java.io.InputStream {
                if (file.path == "/b" && fail) throw java.io.IOException("Read failed")
                return source.openInput(file)
            }
        })
        val initial = session(source.list(source.root()))
        execute(initial)
        assertTrue(initial.finished!!.isFailure)
        assertEquals("first", File(target, "bundle/a").readText())
        assertEquals(File(target, "bundle").path, initial.checkpoint.failure!!.directory.path)
        fail = false
        val retry = Transfers.retrySession(initial, false)
        execute(retry)
        assertTrue(retry.finished.toString(), retry.finished!!.isSuccess)
        assertEquals(listOf("/a", "/b"), source.opened)
        assertEquals("second", File(target, "bundle/b").readText())
        assertFalse(File(target, "bundle/bundle").exists())
    }

    @Test fun metadataFailuresKeepCompletedContentAndRemainVisibleAfterRetry() {
        val source = FakeFileSystem("extract-transfer-source", mapOf("/" to listOf("data")), mapOf("/data" to "content"))
        FsRegistry.register(source)
        FsRegistry.register(object : FileSystem by local, com.twig.core.UnixMetadataWrite {
            override fun applyMetadata(file: XFile, attributes: com.twig.core.TransferAttributes) = com.twig.core.MetadataResult(false)
            override fun createSymbolicLink(parent: XFile, name: String, target: String): XFile = error("Not needed")
        })
        val work = session(source.list(source.root()).map { it.copy(unixMode = 0x1ED) })
        execute(work)
        assertTrue(work.finished.toString(), work.finished!!.isSuccess)
        assertEquals("content", File(target, "bundle/data").readText())
        assertEquals(1, work.metadataFailures)
        val retry = Transfers.retrySession(work, false)
        execute(retry)
        assertEquals(1, retry.metadataFailures)
        assertEquals(listOf("/data"), source.opened)
    }

    @Test fun anExistingFileAtTheWrapperNameIsPreserved() {
        val source = FakeFileSystem("extract-transfer-source", mapOf("/" to listOf("a")), mapOf("/a" to "first"))
        FsRegistry.register(source)
        File(target, "bundle").writeText("keep")
        val work = session(source.list(source.root()))
        execute(work)
        assertTrue(work.finished!!.isFailure)
        assertEquals("keep", File(target, "bundle").readText())
        assertTrue(source.opened.isEmpty())
    }

    @Test fun unsafeArchiveEntriesAreStillSkippedInsideTheWrapper() {
        val source = FakeFileSystem("extract-transfer-source",
            mapOf("/" to listOf("..", "healthy"), "/.." to listOf("escape")),
            mapOf("/healthy" to "safe", "/../escape" to "unsafe"))
        FsRegistry.register(source)
        val work = session(source.list(source.root()))
        execute(work)
        assertTrue(work.finished.toString(), work.finished!!.isSuccess)
        assertEquals(1, work.refused)
        assertEquals("safe", File(target, "bundle/healthy").readText())
        assertFalse(File(target, "escape").exists())
        assertFalse(File(target, "bundle/escape").exists())
    }
}
