package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isWritableDir
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.SevenZFileSystem
import com.twig.fs.archive.ZipFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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
 * The interaction "expanding a zip makes it the copy target for the other pane".
 *
 * The lower layer has long supported writing into an archive (`ZipFileSystem.openOutput`
 * -> append), what was always missing was that **the current directory never lands on the
 * archive's root**: the tree row's XFile is the host file (`isDir=false`), and
 * [PaneViewModel.toggleFile] only updates `currentDir` when `isDir` is true -- so the
 * green highlight stops on the zip's own row while the paste target is still the outer
 * directory, and expanding the archive gets you nowhere to copy into. The only way in was
 * tapping a **subdirectory** inside the archive (an archive with no subdirectories, or
 * the archive root itself, had no entry point at all).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelArchiveTargetTest {

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
        archive = File(ext, "box.zip")
        ZipOutputStream(archive.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("inside.txt"))
            z.write("hi".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("sub/deep.txt"))
            z.write("deep".toByteArray())
            z.closeEntry()
        }
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        archive.delete()
    }

    private fun nodeFor(path: String) = vm.state.value.rows
        .filterIsInstance<PaneViewModel.FileNode>().first { it.file.path == path }

    private fun openTree() {
        vm.bootstrap(listOf("file\t${ext.path}"))
    }

    @Test
    fun `after expanding a zip, the current directory is the archive root, usable as a copy target`() = runTest(dispatcher) {
        openTree()
        advanceUntilIdle()
        val outside = vm.state.value.currentDir

        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()

        val dir = vm.state.value.currentDir
        assertEquals(ZipFileSystem.SCHEME, dir?.scheme)
        assertEquals("${archive.path}${ArchiveFileSystem.SEP}", dir?.path)
        assertTrue("the archive root must be a writable directory, otherwise the copy button blocks it outright", dir!!.isWritableDir())
        assertTrue("before and after expanding must not be the same directory", outside?.path != dir.path)
    }

    @Test
    fun `collapsing the zip returns the target to its containing directory, not stuck inside the collapsed archive`() = runTest(dispatcher) {
        openTree()
        advanceUntilIdle()
        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()

        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()

        val dir = vm.state.value.currentDir
        assertEquals("file", dir?.scheme)
        assertEquals(ext.path, dir?.path)
    }

    @Test
    fun `expanding it a second time uses the cache, and the current directory still lands on the archive root`() = runTest(dispatcher) {
        openTree()
        advanceUntilIdle()
        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()
        vm.toggle(nodeFor(archive.path)) // collapse
        advanceUntilIdle()

        vm.toggle(nodeFor(archive.path)) // expand again: goes through the children-cache branch
        advanceUntilIdle()

        assertEquals(ZipFileSystem.SCHEME, vm.state.value.currentDir?.scheme)
        assertEquals("${archive.path}${ArchiveFileSystem.SEP}", vm.state.value.currentDir?.path)
    }

    @Test
    fun `a subdirectory inside the archive can still be selected as usual`() = runTest(dispatcher) {
        openTree()
        advanceUntilIdle()
        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()

        val sub = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.scheme == ZipFileSystem.SCHEME && it.file.isDir }
        vm.toggle(sub)
        advanceUntilIdle()

        assertEquals("${archive.path}${ArchiveFileSystem.SEP}sub", vm.state.value.currentDir?.path)
    }

    @Test
    fun `a read-only format's archive root is never treated as a writable target`() = runTest(dispatcher) {
        // the entire 7z source is read-only (FileSystem.writable() == false); even if its root becomes the current directory, nothing can be copied into it
        val sevenZ = FsRegistry.of(SevenZFileSystem.SCHEME)
        val fake = XFile(SevenZFileSystem.SCHEME, "/x.7z${ArchiveFileSystem.SEP}", isDir = true, canWrite = true)
        assertFalse(sevenZ.writable())
        assertFalse(fake.isWritableDir())
    }

    /**
     * Regression: after expanding an archive, tapping "up" made the green highlight
     * disappear entirely.
     *
     * The archive root's path is `<host>!/`; slicing it with `parentPath` yields the path
     * of the host file's containing directory -- but **the scheme is still zip**, so
     * currentKey ends up pointing at something like `f:zip...:/sdcard`, a row that does
     * not exist at all in the tree. The archive root does not sit at the top of the tree,
     * it hangs off the host file, so "up" should land on the host file's own containing
     * directory (matching what happens when the archive is collapsed).
     */
    @Test
    fun `after expanding a zip, tapping up returns the green highlight to the archive's containing directory instead of vanishing`() = runTest(dispatcher) {
        openTree()
        advanceUntilIdle()
        vm.toggle(nodeFor(archive.path))
        advanceUntilIdle()

        vm.up()
        advanceUntilIdle()

        val st = vm.state.value
        assertEquals(ext.path, st.currentDir?.path)
        assertEquals("file", st.currentDir?.scheme)
        assertEquals("the green highlight should land on the storage row", "f:file:${ext.path}", st.currentKey)
    }

    /**
     * The top-level rows (internal storage / volumes / root directory / apps) have
     * nowhere further up than the top of the tree, and going further up should simply
     * clear the selection -- the old logic would concatenate `parentPath` into something
     * like `/storage/emulated`, a row the tree does not have either, and on screen it also
     * looked like "the green highlight disappears", just for a more deeply hidden reason.
     */
    @Test
    fun `going up from internal storage reaches the top, without pointing at a nonexistent row`() = runTest(dispatcher) {
        openTree()
        advanceUntilIdle()
        vm.toggle(nodeFor(ext.path)) // tap the internal-storage row, the green highlight lands on it
        advanceUntilIdle()
        assertEquals("f:file:${ext.path}", vm.state.value.currentKey)

        vm.up()
        advanceUntilIdle()

        assertNull(vm.state.value.currentKey)
        assertNull(vm.state.value.currentDir)
    }
}
