package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Favorite
import com.twig.app.FavoritesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `PaneViewModel`'s **restore last position / expansion** path.
 *
 * This path had never been tested, and it had a real bug: on 2026-08-04, "a position
 * expanded from a favorite has no green highlight after reopening" -- `currentDescriptor()`
 * only stored "which directory", losing the fact that it was reached "via a favorite", so
 * on restore the key pointed at a row that did not exist in the tree at all. That was found
 * by walking through it on a real device, at a time when `TreeKeys`/`SortRules`'s pure
 * function tests were already running: **they cannot cover control flow**. This class fills
 * exactly that gap.
 *
 * Getting it to run relies on two things (see the notes in app/build.gradle.kts):
 * - **Robolectric**: `PaneViewModel` is an `AndroidViewModel`, its constructor needs an
 *   Application, and it has 20-odd `getApplication()` calls plus a pile of SharedPreferences;
 * - **coroutines-test**: `viewModelScope` runs on Main (absent in a unit test), and queued
 *   coroutines need to be driven to completion deterministically before asserting. ★
 *   `PaneViewModel.io` must also be swapped for the same test dispatcher, otherwise those
 *   14 `withContext(io)` calls run on the real thread pool, `advanceUntilIdle()` has no
 *   control over them, and the test becomes a coin flip.
 *
 * Uses a real `LocalFileSystem` + the temporary external storage Robolectric provides,
 * rather than a fake file system -- the restore path should be verified together with
 * real directory listing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelRestoreTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var ext: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        // TwigApp.onCreate has already registered the base sources like LocalFileSystem
        // (Robolectric really instantiates the Application per the manifest's android:name)
        ext = Environment.getExternalStorageDirectory()
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun dir(name: String): File = File(ext, name).apply { mkdirs() }

    private fun rowKeys() = vm.state.value.rows.map { it.key }

    private fun expandedFileNames() = vm.state.value.rows
        .filterIsInstance<PaneViewModel.FileNode>()
        .filter { !it.file.isDir }
        .map { it.file.name }

    // ---- expanding a local directory ----

    @Test
    fun `restores the last expanded local directory and lists its files`() = runTest(dispatcher) {
        val photos = dir("photos")
        File(photos, "a.jpg").writeText("x")
        File(photos, "b.jpg").writeText("y")

        vm.bootstrap(listOf("file\t${ext.path}", "file\t${photos.path}"))
        advanceUntilIdle()

        assertTrue("the photos directory should be expanded in the tree", "f:file:${photos.path}" in rowKeys())
        assertTrue(expandedFileNames().containsAll(listOf("a.jpg", "b.jpg")))
    }

    @Test
    fun `restoring the current directory points the highlight at itself`() = runTest(dispatcher) {
        val docs = dir("docs")
        vm.bootstrap(listOf("file\t${ext.path}", "file\t${docs.path}"), currentDesc = "file\t${docs.path}")
        advanceUntilIdle()

        assertEquals("f:file:${docs.path}", vm.state.value.currentKey)
        assertEquals(docs.path, vm.state.value.currentDir?.path)
    }

    /**
     * ★ Regression: **when we last stopped on a favorite's root directory, the highlight
     * must land on the favorite row after restore**.
     *
     * A favorite's (and a server's) root directory has no FileNode of its own in the tree --
     * its children hang directly off the `fav:` row. But the saved state only stores the
     * currentDir directory itself, so if restore naively used `fileKey(dir)`, the resulting
     * key would match no row in the tree and the highlight would disappear.
     */
    @Test
    fun `restoring from a favorite puts the highlight on the favorite row, not a nonexistent directory row`() = runTest(dispatcher) {
        val backup = dir("backup")
        File(backup, "x.txt").writeText("x")
        val fav = Favorite(label = "backup", kind = "local", path = backup.path)
        FavoritesStore.add(app, fav)

        vm.bootstrap(
            listOf("group\tfav", "fav\t${fav.id}"),
            currentDesc = "file\t${backup.path}",
        )
        advanceUntilIdle()

        assertEquals("fav:${fav.id}", vm.state.value.currentKey)
        assertTrue("the favorite row itself must be in the tree", "fav:${fav.id}" in rowKeys())
        // currentDir is still the real directory (new/paste targets rely on it)
        assertEquals(backup.path, vm.state.value.currentDir?.path)
    }

    /** `restoring` is true during restore and must fall back after -- the UI uses it to decide when to stop re-anchoring scroll. */
    @Test
    fun `restoring falls back to false once restore finishes`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        assertFalse(vm.state.value.restoring)
    }

    /** When a descriptor's directory no longer exists (deleted elsewhere by the user), it must not crash, and everything else restores normally. */
    @Test
    fun `a stale descriptor is skipped without affecting the others`() = runTest(dispatcher) {
        val alive = dir("alive")
        vm.bootstrap(listOf("file\t${ext.path}", "file\t${alive.path}", "file\t${ext.path}/long-gone"))
        advanceUntilIdle()

        assertTrue("f:file:${alive.path}" in rowKeys())
    }

    // ---- expand / collapse ----

    @Test
    fun `expanding a directory then collapsing it removes its children from the rows`() = runTest(dispatcher) {
        val music = dir("music")
        File(music, "s.mp3").writeText("m")

        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        val node = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == music.path }
        vm.toggle(node)
        advanceUntilIdle()
        assertTrue("s.mp3" in expandedFileNames())

        vm.toggle(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == music.path })
        advanceUntilIdle()
        assertFalse("s.mp3" in expandedFileNames())
    }

    /**
     * Accordion: expanding a directory collapses any other branch that is **not on its
     * ancestor chain**. This exercises `accordionExpand` -> `TreeKeys.ancestorKeys`, the
     * seam between the pure-function tests and real tree state -- the pure-function side
     * tests the algorithm, this one tests whether it was wired up correctly.
     */
    @Test
    fun `expanding one directory collapses its sibling directory`() = runTest(dispatcher) {
        val a = dir("a-dir").also { File(it, "1.txt").writeText("1") }
        val b = dir("b-dir").also { File(it, "2.txt").writeText("2") }

        vm.bootstrap(listOf("file\t${ext.path}", "file\t${a.path}"))
        advanceUntilIdle()
        assertTrue("1.txt" in expandedFileNames())

        val bNode = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == b.path }
        vm.toggle(bNode)
        advanceUntilIdle()

        assertTrue("2.txt" in expandedFileNames())
        assertFalse("after expanding b-dir, a-dir should be collapsed", "1.txt" in expandedFileNames())
    }

    // ---- descriptor round-trip ----

    /**
     * Save -> restore -> save again, the descriptor must be stable. If it is not, the
     * position drifts on every cold start, and that kind of drift is extremely hard to
     * notice on a real device.
     */
    @Test
    fun `descriptor is unchanged after saving expansion state, restoring, and saving again`() = runTest(dispatcher) {
        val d = dir("roundtrip")
        vm.bootstrap(listOf("file\t${ext.path}", "file\t${d.path}"), currentDesc = "file\t${d.path}")
        advanceUntilIdle()

        val saved = vm.expandedDescriptors().sorted()
        val savedCur = vm.currentDescriptor()

        val vm2 = PaneViewModel(app).apply { io = dispatcher }
        vm2.bootstrap(saved, savedCur)
        advanceUntilIdle()

        assertEquals(saved, vm2.expandedDescriptors().sorted())
        assertEquals(savedCur, vm2.currentDescriptor())
    }
}
