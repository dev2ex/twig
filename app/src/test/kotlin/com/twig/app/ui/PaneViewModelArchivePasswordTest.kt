package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.core.FsRegistry
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.ZipFileSystem
import com.twig.fs.archive.ZipWriter
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The control flow for expanding an encrypted archive: needs a password -> UI prompts ->
 * validates -> expands -> (optionally) remembers the password.
 *
 * `fs-archive`'s own tests only cover "given a password, can the bytes be decrypted" --
 * they do not reach this layer: where the password comes from, how the UI learns it was
 * wrong, whether a saved password is still trusted next time -- all of that is the VM's
 * control flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelArchivePasswordTest {

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
        archive = File(ext, "secret.zip")
        archive.outputStream().use { os ->
            ZipWriter(os, PASSWORD).use { zw ->
                zw.putNextEntry("inside.txt", System.currentTimeMillis(), sizeHint = 5)
                zw.write("boo!".toByteArray())
                zw.closeEntry()
            }
        }
        vm = PaneViewModel(app).apply { io = dispatcher }
        // a previous test case may have left a password sitting on the singleton FileSystem
        zipFs().setPassword(archive.path, null)
        Prefs.setArchivePassword(app, "file:${archive.path}", null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        archive.delete()
    }

    private fun zipFs() = FsRegistry.of(ZipFileSystem.SCHEME) as ArchiveFileSystem

    /** Expands external storage and finds the row for that archive. */
    private fun archiveNode(): PaneViewModel.FileNode =
        vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == archive.path }

    private fun innerNames() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
        .map { it.file.name }

    @Test
    fun `expanding an encrypted archive requests a password instead of popping up an English error`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()

        vm.toggle(archiveNode())
        advanceUntilIdle()

        val st = vm.state.value
        assertEquals(archive.path, st.passwordFor?.path)
        assertNull("asking for the password the first time should not also report an error", st.error)
        assertFalse("without unlocking it, the archive's contents should not be exposed", innerNames().contains("inside.txt"))
    }

    @Test
    fun `a wrong password tells the UI to try again, a correct one expands it`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        val node = archiveNode()
        vm.toggle(node)
        advanceUntilIdle()

        var result: Boolean? = null
        vm.unlockArchive(node.file, "not-it", save = false) { result = it }
        advanceUntilIdle()
        assertEquals(false, result)
        assertFalse(innerNames().contains("inside.txt"))

        vm.unlockArchive(node.file, PASSWORD, save = false) { result = it }
        advanceUntilIdle()
        assertEquals(true, result)
        assertTrue("once unlocked, the archive's entries should expand in place", innerNames().contains("inside.txt"))
    }

    @Test
    fun `a password saved with the checkbox is reused next time, with no prompt`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        val node = archiveNode()
        vm.toggle(node)
        advanceUntilIdle()
        vm.unlockArchive(node.file, PASSWORD, save = true) {}
        advanceUntilIdle()
        assertNotNull(Prefs.archivePassword(app, "file:${archive.path}"))

        // switch to a fresh VM, and wipe the in-process unlocked state -- leaving only "a saved password" as the one remaining clue
        zipFs().setPassword(archive.path, null)
        val vm2 = PaneViewModel(app).apply { io = dispatcher }
        vm2.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        val n2 = vm2.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.path == archive.path }
        vm2.toggle(n2)
        advanceUntilIdle()

        assertNull("with a saved password present, it should not ask again", vm2.state.value.passwordFor)
        assertTrue(
            vm2.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .any { it.file.name == "inside.txt" },
        )
    }

    @Test
    fun `when a saved password no longer works it is cleared and asked for again`() = runTest(dispatcher) {
        Prefs.setArchivePassword(app, "file:${archive.path}", "stale")
        vm.bootstrap(listOf("file\t${ext.path}"))
        advanceUntilIdle()
        vm.toggle(archiveNode())
        advanceUntilIdle()

        val st = vm.state.value
        assertEquals(archive.path, st.passwordFor?.path)
        assertNotNull("this time it should flag the password as wrong", st.error)
        assertNull("it must not keep retrying with a password that no longer works", Prefs.archivePassword(app, "file:${archive.path}"))
    }

    private companion object {
        const val PASSWORD = "s3cret"
    }
}
