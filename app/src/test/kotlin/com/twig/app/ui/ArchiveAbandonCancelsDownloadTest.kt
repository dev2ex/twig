package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.CacheDirs
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.TarFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream

/**
 * Regression: a tar nested inside a single-file compression layer (e.g. `foo.tar.xz`,
 * `foo.tar.gz`, ...) always needs [PaneViewModel.localArchive] to materialize it to the
 * local cache first -- `SingleFileSystem.fastRandom()` is never overridden, so it stays at
 * `ArchiveFileSystem`'s default of `false`. If that tar is huge and the user opens
 * something else before it finishes (collapsing it, or tapping another row), the row's
 * spinner is abandoned immediately -- but before this fix, the copy loop had no way to
 * notice, and kept downloading/decompressing the whole thing to completion in the
 * background regardless. [PaneViewModel.localArchive] now polls `abandoned` between
 * chunks and aborts, cleaning up the partial `.part` cache file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveAbandonCancelsDownloadTest {

    /**
     * Stands in for `SingleFileSystem`'s xz/gz/bz2/zst mount: `fastRandom()` is left at the
     * `ArchiveFileSystem` default (`false`), so any entry read through it needs
     * `localArchive`. [openEntry] hands back whatever stream the test supplies.
     */
    private class FakeHost(override val scheme: String, private val entry: () -> InputStream) : ArchiveFileSystem() {
        override val displayName: String = "Fake($scheme)"
        override fun readEntries(archivePath: String): List<ArchiveEntry> = emptyList()
        override fun openEntry(archivePath: String, inner: String): InputStream = entry()
    }

    /**
     * Serves small fixed-size chunks of a much bigger declared total, and calls
     * [onFirstRead] exactly once, right after handing back the first chunk -- simulating
     * "the row got abandoned while the download was already in flight".
     */
    private class ProbeStream(private val totalBytes: Int, private val onFirstRead: () -> Unit) : InputStream() {
        var reads = 0
        private var sent = 0
        override fun read(): Int = throw UnsupportedOperationException("the 1MB-buffer copy loop only calls read(byte[], Int, Int)")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads++
            if (reads == 1) onFirstRead()
            if (sent >= totalBytes) return -1
            val n = minOf(len, totalBytes - sent, 4096)
            sent += n
            return n
        }
    }

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        vm = PaneViewModel(app).apply { io = dispatcher }
        // Only ever looked up as the mount target (`archiveTarget`'s `afs`); the aborted
        // download never gets far enough to actually read through it.
        FsRegistry.register(TarFileSystem())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `abandoning a still-materializing nested tar stops the download instead of finishing it in the background`() = runTest(dispatcher) {
        val bigTar = XFile(
            scheme = "fakexz1", path = "/net/archive.tar.xz!/archive.tar",
            isDir = false, size = 50_000_000L, lastModified = 1L,
        )
        val probe = ProbeStream(totalBytes = 50_000_000) {
            // "Meanwhile the user opened something else" -- an unrelated, unsupported-extension
            // file is enough: the point is only to reassign `pendingExpand`, which marks
            // `bigTar`'s row abandoned (see PaneViewModel.claimExpand).
            vm.mountExternal(XFile("irrelevant", "/net/other.bin", isDir = false, size = 10, lastModified = 1L))
        }
        FsRegistry.register(FakeHost("fakexz1") { probe })

        vm.mountExternal(bigTar)
        advanceUntilIdle()

        assertEquals(
            "the copy loop must stop right after noticing abandonment, not keep reading a 50MB file",
            1,
            probe.reads,
        )
        val leftovers = CacheDirs.dir(app, CacheDirs.ARCHIVES).listFiles()
            ?.filter { it.name.contains("archive.tar") } ?: emptyList()
        assertTrue("no cached copy and no leftover .part file for the abandoned download", leftovers.isEmpty())
    }
}
