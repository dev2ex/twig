package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The **recursive stats** for the directory Properties card ([scanDirStat] plus
 * `PaneViewModel`'s dirScan lifecycle).
 *
 * Two things must be covered: the numbers are right (recurses all the way down,
 * directories themselves do not count toward size), and **the scan really stops when the
 * card is closed / the containing directory is collapsed** — the latter is this feature's
 * main risk: scanning a large directory tree (especially over a network source) is
 * expensive, and if it keeps running after the card is no longer even visible, that is
 * pure wasted traffic and battery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirScanTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var ext: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        ext = Environment.getExternalStorageDirectory()
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun local(f: File) = XFile("file", f.path, isDir = f.isDirectory, size = f.length())

    /** 3 levels deep: 2 files at the root + 1 file in the subdirectory + 1 file in the grandchild directory, 4 files / 2 directories total. */
    private fun tree(name: String): File {
        val root = File(ext, name).apply { mkdirs() }
        File(root, "a.txt").writeText("12345") // 5B
        File(root, "b.txt").writeText("123") // 3B
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "c.txt").writeText("1234567890") // 10B
        val deep = File(sub, "deep").apply { mkdirs() }
        File(deep, "d.bin").writeText("1234567") // 7B
        return root
    }

    // ---- the scan itself ----

    @Test
    fun `recursive stats reach all the way down - file count, dir count and total bytes are all correct`() = runTest(dispatcher) {
        val root = tree("stats")

        val stats = scanDirStat(local(root), dispatcher).toList()

        val last = stats.last()
        assertEquals(4, last.files)
        assertEquals(2, last.dirs)
        // a directory entry itself does not count toward size, only file bytes accumulate
        assertEquals(25L, last.bytes)
    }

    @Test
    fun `an empty directory's stats are all zero`() = runTest(dispatcher) {
        val root = File(ext, "empty").apply { mkdirs() }

        assertEquals(DirStat(), scanDirStat(local(root), dispatcher).toList().last())
    }

    // ---- card lifecycle ----

    private fun infoNode(dir: File) = vm.state.value.rows
        .filterIsInstance<PaneViewModel.InfoNode>()
        .firstOrNull { it.file.path == dir.path }

    @Test
    fun `opening the directory Properties card immediately shows recursive stats - the spinner stops once the scan finishes`() = runTest(dispatcher) {
        val root = tree("card")
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        vm.toggleInfo(local(root))
        advanceUntilIdle()

        val n = infoNode(root)
        assertNotNull("the Properties card should be attached right below the directory row", n)
        assertEquals(4, n!!.dirStat?.files)
        assertEquals(2, n.dirStat?.dirs)
        assertEquals(25L, n.dirStat?.bytes)
        assertFalse("once the scan finishes, the spinner should not keep spinning", n.scanning)
    }

    /**
     * Regression: **the spinner must really be visible**. The first version, per user
     * feedback on 2026-08-04, "never showed a spinner" — for two reasons: a small local
     * directory scans in tens of milliseconds (this is backstopped by
     * [DIR_SCAN_MIN_SPIN_MS], which is exactly what this test verifies), and the spinner
     * used to sit on the tab-bar row and get pushed past the pane's right edge (see
     * `the on-screen spinner lands within the card's visible area`).
     */
    @Test
    fun `even a very fast scan keeps the spinner up for a minimum duration`() = runTest(dispatcher) {
        val root = tree("min-duration")
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        vm.toggleInfo(local(root))
        advanceTimeBy(DIR_SCAN_MIN_SPIN_MS / 2) // the tree is tiny, so it has already finished scanning by now

        val mid = infoNode(root)!!
        assertEquals("the numbers should already be final", 4, mid.dirStat?.files)
        assertTrue("even after the scan finishes it must keep spinning a little longer, otherwise the user never sees it at all", mid.scanning)

        advanceUntilIdle()
        assertFalse("it stops once the minimum duration has passed", infoNode(root)!!.scanning)
    }

    /**
     * Closing the card (X / toggling "Properties" again) stops the scan immediately.
     *
     * Deliberately closes it **without** calling `advanceUntilIdle()` first — that way the
     * scan task is still running (still active) at the moment it is closed, so if the
     * cancellation is missed, [PaneViewModel.activeDirScans] would not be 0 and the
     * assertion would actually mean something.
     */
    @Test
    fun `closing the card cancels the scan`() = runTest(dispatcher) {
        val root = tree("close-card")
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        vm.toggleInfo(local(root)) // rebuild is synchronous, the card row exists immediately
        assertNotNull(infoNode(root))
        assertEquals(1, vm.activeDirScans())

        vm.toggleInfo(local(root)) // tap again = close
        assertEquals("once the card is closed there should be no scan still running", 0, vm.activeDirScans())

        advanceUntilIdle()
        assertTrue("the card row should be gone", infoNode(root) == null)
    }

    /**
     * **Collapsing the containing directory must stop the scan.** The card row is no
     * longer built (the user cannot see it either), and leaving the task running at that
     * point is just scanning a large tree for nothing in the background —
     * [PaneViewModel.rebuild]'s cleanup is responsible for cancelling it.
     */
    @Test
    fun `after the containing directory collapses, the card disappears and the scan is cancelled`() = runTest(dispatcher) {
        val root = tree("collapse")
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        val extNode = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == ext.path }
        vm.toggleInfo(local(root))
        assertNotNull(infoNode(root))
        assertEquals(1, vm.activeDirScans())

        vm.toggle(extNode) // collapse the external storage row -> the directory row disappears from the tree along with its card
        assertEquals("once the card is invisible there should be no scan still running", 0, vm.activeDirScans())

        advanceUntilIdle()
        assertTrue("the card row should disappear along with its containing directory collapsing", infoNode(root) == null)
        // expanding it back should not "automatically resume" either -- the scan is discarded together with the card, and reopening Properties is required to get it again
        vm.toggle(extNode.copy(expanded = false))
        advanceUntilIdle()
        assertTrue(infoNode(root) == null)
    }
}
