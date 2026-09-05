package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The tree must **never show two rows with the same key**.
 *
 * A row's key is what DiffUtil uses for identity; a duplicate makes rendering go wrong —
 * the symptom is "the same archive is expanded fine in one place but empty in the other".
 * The easiest way to hit it: another app "opens with Twig" an archive (mounted at the top
 * of the tree via [PaneViewModel.mountExternal]) while that same archive **already lives**
 * inside an already-expanded directory, so the same key shows up once at the top and once
 * at its original spot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelDuplicateRowTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var ext: File
    private lateinit var archive: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        ext = Environment.getExternalStorageDirectory()
        archive = File(ext, "dup.zip")
        ZipOutputStream(archive.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("a.txt"))
            z.write("aaa".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("b.txt"))
            z.write("bbb".toByteArray())
            z.closeEntry()
        }
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        archive.delete()
    }

    private fun keys() = vm.state.value.rows.map { it.key }

    private fun dupes() = keys().groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun archiveX() = XFile(
        "file", archive.absolutePath, isDir = false,
        size = archive.length(), lastModified = archive.lastModified(),
    )

    @Test
    fun `externally opened archive does not collide with its original spot in the tree`() = runTest(dispatcher) {
        // External storage is already expanded -- that zip is already visible in this tree
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        assertTrue("precondition: the archive should already be in the tree", keys().any { it.contains(archive.absolutePath) })

        // Another app "opens with Twig" the same archive: mounted at the top of the tree
        vm.mountExternal(archiveX())
        advanceUntilIdle()

        assertEquals("the same key showed up twice, DiffUtil will mis-identify the rows", emptySet<String>(), dupes())
    }

    /**
     * Real-world scenario: after the external open mounts it at the top of the tree, the
     * user **also expands the directory the archive originally lived in** (the accordion
     * would have collapsed it on mountExternal, so the previous test case cannot hit this).
     * At that point the same key appears once at the top and once at its original spot.
     */
    @Test
    fun `expanding the archive's original directory after an external open still must not collide keys`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        vm.mountExternal(archiveX())
        advanceUntilIdle()

        // Re-expand external storage (the accordion just collapsed it)
        val storage = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == ext.path }
        if (!storage.expanded) {
            vm.toggle(storage)
            advanceUntilIdle()
        }

        val zipRows = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .filter { it.file.path == archive.absolutePath }
        assertTrue("precondition: there should be one row at the top and one at the original spot", zipRows.size >= 2)
        assertEquals("the two rows collided on key -- DiffUtil will mis-identify them, showing as one empty expansion", emptySet<String>(), dupes())
    }

    /**
     * Two panes = two ViewModels, but the `ZipFileSystem` inside `FsRegistry` is **the same
     * instance** (the mount-host table and various caches all live on it). Opening the same
     * archive on one side and then the other must not list as empty just because shared
     * state was mutated by the earlier open.
     */
    @Test
    fun `expanding the same archive on both panes in turn, both show content`() = runTest(dispatcher) {
        val other = PaneViewModel(app).apply { io = dispatcher }

        vm.bootstrap(listOf("file\t${ext.path}"))
        other.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        fun PaneViewModel.zipNode() = state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == archive.absolutePath }
        fun PaneViewModel.innerNames() = state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .filter { it.file.scheme == "zip" }.map { it.file.name }

        vm.toggle(vm.zipNode())
        advanceUntilIdle()
        assertEquals(listOf("a.txt", "b.txt"), vm.innerNames())

        other.toggle(other.zipNode())
        advanceUntilIdle()
        assertEquals("the same archive expanded on the other pane came up empty", listOf("a.txt", "b.txt"), other.innerNames())

        // Refresh again the other way: the side expanded first must not go empty after a refresh
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a.txt", "b.txt"), vm.innerNames())
    }

    @Test
    fun `externally opened archive still expands its content`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        vm.mountExternal(archiveX())
        advanceUntilIdle()

        val names = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .filter { it.file.scheme == "zip" }.map { it.file.name }
        assertTrue("no entries came out of the archive: $names", names.containsAll(listOf("a.txt", "b.txt")))
    }
}
