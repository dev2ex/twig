package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.SingleFileSystem
import com.twig.fs.archive.TarFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Regression: expanding a tar nested inside a single-file compression layer (e.g. the
 * `.tar` inside a `.tar.xz`) always needs [PaneViewModel.localArchive] to materialize it
 * locally first, since `SingleFileSystem.fastRandom()` is never overridden. Without a
 * gate, tapping such a row silently starts downloading/decompressing it in the
 * background regardless of size -- surprising for a large or remote one. This exercises
 * [PaneViewModel.State.confirmMaterialize]: a large (or unknown-size) nested entry asks
 * first instead of expanding immediately, declining leaves it alone, and confirming is
 * remembered so re-tapping the same row doesn't ask again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveMaterializeConfirmTest {

    /**
     * Stands in for `SingleFileSystem`'s xz/gz/bz2/zst mount: exposes exactly one entry
     * ("archive.tar") of the test-chosen size, and `fastRandom()` stays at the
     * `ArchiveFileSystem` default (`false`), so that entry always needs local
     * materialization.
     */
    private class FakeOuterHost(private val entrySize: Long) : ArchiveFileSystem() {
        override val scheme: String = SingleFileSystem.XZ_SCHEME
        override val displayName: String = "Fake xz"
        override fun readEntries(archivePath: String): List<ArchiveEntry> =
            listOf(ArchiveEntry(name = "archive.tar", isDir = false, size = entrySize, time = 0L))
        override fun openEntry(archivePath: String, inner: String): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var ext: File
    private lateinit var outer: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        ext = Environment.getExternalStorageDirectory()
        outer = File(ext, "archive.tar.xz").apply { writeBytes(ByteArray(4)) }
        // Only ever looked up as the mount target once a materialize is confirmed; the
        // declined/still-asking scenarios never get far enough to actually read through it.
        FsRegistry.register(TarFileSystem())
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        outer.delete()
    }

    private fun nodeFor(path: String) = vm.state.value.rows
        .filterIsInstance<PaneViewModel.FileNode>().first { it.file.path == path }

    /** Expands the outer (real, local) `.tar.xz` layer and returns the synthetic "archive.tar" row hanging under it. */
    private suspend fun TestScope.expandOuter(entrySize: Long): PaneViewModel.FileNode {
        FsRegistry.register(FakeOuterHost(entrySize))
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        vm.toggle(nodeFor(outer.path))
        advanceUntilIdle()
        return vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.scheme == SingleFileSystem.XZ_SCHEME && it.file.name == "archive.tar" }
    }

    @Test
    fun `a large nested tar asks for confirmation instead of expanding immediately`() = runTest(dispatcher) {
        val node = expandOuter(entrySize = 500_000_000L) // well above the threshold

        vm.toggle(node)
        advanceUntilIdle()

        assertEquals("must surface the confirm dialog for this row", node.file.path, vm.state.value.confirmMaterialize?.path)
        val row = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first { it.key == node.key }
        assertFalse("must not start loading before the user confirms", row.loading)
        assertFalse("must not expand before the user confirms", row.expanded)
    }

    @Test
    fun `declining leaves the row untouched and asks again next time`() = runTest(dispatcher) {
        val node = expandOuter(entrySize = 500_000_000L)
        vm.toggle(node)
        advanceUntilIdle()

        vm.answerMaterializeConfirm(node.file, proceed = false)
        advanceUntilIdle()

        assertNull("dialog must be dismissed", vm.state.value.confirmMaterialize)
        val row = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first { it.key == node.key }
        assertFalse(row.loading)
        assertFalse(row.expanded)

        // tapping it again must ask again -- declining never gets remembered
        vm.toggle(node)
        advanceUntilIdle()
        assertEquals(node.file.path, vm.state.value.confirmMaterialize?.path)
    }

    @Test
    fun `confirming is remembered, so re-tapping the same row never asks again`() = runTest(dispatcher) {
        val node = expandOuter(entrySize = 500_000_000L)
        vm.toggle(node)
        advanceUntilIdle()

        vm.answerMaterializeConfirm(node.file, proceed = true)
        advanceUntilIdle()

        assertNull("dialog must be dismissed as soon as the user answers", vm.state.value.confirmMaterialize)

        // whatever became of that first (confirmed) attempt, tapping the row again must
        // never re-show the dialog -- the row was already confirmed once this session.
        vm.toggle(node)
        advanceUntilIdle()
        assertNull("must not ask again once confirmed", vm.state.value.confirmMaterialize)
    }

    @Test
    fun `a small nested tar expands straight away, no confirmation asked`() = runTest(dispatcher) {
        val node = expandOuter(entrySize = 10L) // well under the threshold

        vm.toggle(node)
        advanceUntilIdle()

        assertNull("small archives must never trigger the confirm dialog", vm.state.value.confirmMaterialize)
    }
}
