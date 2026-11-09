package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TreeUsageTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var dir: File
    private lateinit var vm: PaneViewModel
    private lateinit var scan: TreemapEntry

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        dir = File(app.cacheDir, "tree-usage").apply { mkdirs() }
        File(dir, "small").mkdirs()
        File(dir, "small/a.txt").writeText("12")
        File(dir, "large").mkdirs()
        File(dir, "large/z.txt").writeText("12345678")
        File(dir, "middle.txt").writeText("1234")
        vm = PaneViewModel(app).apply {
            io = dispatcher
            lockRoot = XFile("file", dir.path, isDir = true)
        }
        // Build the snapshot explicitly to keep these lifecycle tests independent of local walkers.
        fun entry(file: File, parent: TreemapEntry?): TreemapEntry {
            val node = TreemapEntry(XFile("file", file.path, isDir = file.isDirectory, size = file.length()),
                file.isDirectory, if (file.isFile) file.length() else 0, if (file.isDirectory) arrayListOf() else null, parent)
            file.listFiles()?.forEach { child -> node.children!!.add(entry(child, node)) }
            if (file.isDirectory) node.size = node.children!!.sumOf { it.size }
            return node
        }
        scan = entry(dir, null)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun rows() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
    private fun row(path: String) = rows().first { it.file.path == path }
    private fun TestScope.open() {
        vm.bootstrap()
        vm.toggle(row(dir.path))
        advanceUntilIdle()
        vm.toggle(row(File(dir, "large").path))
        advanceUntilIdle()
    }

    @Test fun `scan preserves expansion and sorts mixed siblings by recursive bytes`() = runTest(dispatcher) {
        open()
        val expanded = rows().filter { it.expanded }.map { it.key }.toSet()
        vm.retainUsage(scan.file, scan)
        assertEquals(expanded, rows().filter { it.expanded }.map { it.key }.toSet())
        assertEquals(listOf("large", "middle.txt", "small"), rows().filter { it.depth == 1 }.map { it.file.name })
        assertEquals(14L, row(dir.path).usage!!.bytes)
        assertEquals(0f, row(dir.path).usage!!.fraction, 0f)
        assertEquals(8L, row(File(dir, "large").path).usage!!.bytes)
        assertEquals(8f / 14f, row(File(dir, "large").path).usage!!.fraction, 0.0001f)
        assertEquals(1f, row(File(dir, "large/z.txt").path).usage!!.fraction, 0f)
        vm.resortAll()
        assertEquals(listOf("large", "middle.txt", "small"), rows().filter { it.depth == 1 }.map { it.file.name })
    }

    @Test fun `folding child clears its contents but retains its total until parent folds`() = runTest(dispatcher) {
        open()
        vm.retainUsage(scan.file, scan)
        val large = File(dir, "large").path
        vm.toggle(row(large))
        advanceUntilIdle()
        assertEquals(8L, row(large).usage!!.bytes)
        vm.toggle(row(large))
        advanceUntilIdle()
        assertNull(row(File(dir, "large/z.txt").path).usage)
        vm.toggle(row(dir.path))
        advanceUntilIdle()
        vm.toggle(row(dir.path))
        advanceUntilIdle()
        assertTrue(rows().filter { it.depth == 1 }.all { it.usage == null })
    }

    @Test fun `accordion collapse clears analysis of the abandoned branch`() = runTest(dispatcher) {
        open()
        val large = File(dir, "large").path
        vm.retainUsage(row(large).file, scan.children!!.first { it.file.path == large })
        vm.toggle(row(File(dir, "small").path))
        advanceUntilIdle()
        vm.toggle(row(large))
        advanceUntilIdle()
        assertNull(row(File(dir, "large/z.txt").path).usage)
        vm.collapseAll()
        assertTrue(rows().all { it.usage == null })
    }
}
