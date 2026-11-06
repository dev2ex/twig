package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class PathBarTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: PaneViewModel
    private val uri = "content://provider/tree/primary%3ADCIM/document/primary%3ADCIM"
    private fun dir(suffix: String, label: String) =
        XFile("path-test", uri + suffix, isDir = true, displayName = label)
    private val root = dir("", "DCIM")
    private val sub = dir("%2Fphotos", "Photos")
    private val leaf = dir("%2Fphotos%2F2026", "2026")
    private val other = dir("%2Fother", "Other")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        FsRegistry.register(object : FileSystem by FakeFileSystem("path-test", emptyMap()) {
            override val scheme = "path-test"
            override fun parentOf(file: XFile): XFile? = null
            override fun list(dir: XFile): List<XFile> = when (dir.path) {
                root.path -> listOf(sub, other)
                sub.path -> listOf(leaf)
                else -> emptyList()
            }
        })
        vm = PaneViewModel(ApplicationProvider.getApplicationContext<Application>()).apply {
            io = dispatcher
            lockRoot = root
            lockLabel = "Pictures"
            refreshTree()
        }
    }

    @After fun cleanup() {
        FsRegistry.unregister("path-test")
        Dispatchers.resetMain()
    }

    private fun open(file: XFile) {
        vm.toggle(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first { it.file == file })
    }

    @Test fun `parents retain child buttons while all their child directories collapse`() = runTest(dispatcher) {
        open(root); advanceUntilIdle()
        open(sub); advanceUntilIdle()
        open(leaf); advanceUntilIdle()
        val trail = vm.pathSegments()
        assertEquals(listOf("Pictures", "Photos", "2026"), trail.map { it.label })
        vm.selectPathSegment(trail.first().key)
        advanceUntilIdle()
        assertEquals(root.path, vm.state.value.currentDir?.path)
        assertEquals(trail, vm.pathSegments())
        val rootRows = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
        assertEquals(listOf(root.path, other.path, sub.path).toSet(), rootRows.map { it.file.path }.toSet())
        assertTrue(rootRows.first { it.file.path == root.path }.expanded)
        assertTrue(rootRows.filter { it.file.path != root.path }.all { !it.expanded })
        vm.selectPathSegment(trail[1].key)
        advanceUntilIdle()
        val subRows = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
        assertTrue(subRows.first { it.file.path == sub.path }.expanded)
        assertFalse(subRows.first { it.file.path == leaf.path }.expanded)
        assertEquals(trail, vm.pathSegments())
        vm.selectPathSegment(trail.last().key)
        advanceUntilIdle()
        assertEquals(leaf.path, vm.state.value.currentDir?.path)
        assertEquals(trail, vm.pathSegments())
    }

    @Test fun `another branch replaces the retained suffix`() = runTest(dispatcher) {
        open(root); advanceUntilIdle()
        open(sub); advanceUntilIdle()
        open(leaf); advanceUntilIdle()
        val trail = vm.pathSegments()
        vm.selectPathSegment(trail.first().key)
        advanceUntilIdle()
        open(other); advanceUntilIdle()
        assertEquals(listOf("Pictures", "Other"), vm.pathSegments().map { it.label })
        assertEquals(other.path, vm.state.value.currentDir?.path)
    }

    @Test fun `retained opaque child can be selected after Up collapses its ancestors`() = runTest(dispatcher) {
        open(root); advanceUntilIdle()
        open(sub); advanceUntilIdle()
        open(leaf); advanceUntilIdle()
        val trail = vm.pathSegments()
        vm.up()
        assertEquals(trail, vm.pathSegments())
        vm.up()
        assertEquals(trail, vm.pathSegments())
        assertFalse(vm.state.value.rows.any { it.key == trail.last().key })
        vm.selectPathSegment(trail.last().key)
        advanceUntilIdle()
        assertEquals(leaf.path, vm.state.value.currentDir?.path)
        assertEquals(trail, vm.pathSegments())
    }
}
