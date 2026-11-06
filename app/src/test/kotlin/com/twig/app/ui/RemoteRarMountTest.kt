package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.Archives
import com.twig.fs.local.LocalFileSystem
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** Mount policy is shared by full/libre; real RAR decoding is tested in :fs-archive-rar. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteRarMountTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private var previousRar: FileSystem? = null

    private class Remote(size: Long) : FileSystem by LocalFileSystem() {
        override val scheme = "remote-rar-policy"
        val archive = XFile(scheme, "/server-only/box.rar", isDir = false, size = size)
        override fun root() = XFile(scheme, "/server-only", isDir = true)
        override fun list(dir: XFile) = listOf(archive)
        override fun openInput(file: XFile): InputStream =
            throw AssertionError("Remote RAR must not be materialized")
    }

    private class Rar : ArchiveFileSystem() {
        override val scheme = Archives.RAR_SCHEME
        override val displayName = "Test RAR"
        var mountedHost: XFile? = null
        override fun readEntries(archivePath: String): List<ArchiveEntry> {
            mountedHost = hostOf(archivePath)
            return listOf(ArchiveEntry("inside.txt", false, 2, 0))
        }
        override fun openEntry(archivePath: String, inner: String) = ByteArrayInputStream("hi".toByteArray())
    }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        previousRar = runCatching { FsRegistry.of(Archives.RAR_SCHEME) }.getOrNull()
    }

    @After
    fun tearDown() {
        FsRegistry.unregister("remote-rar-policy")
        FsRegistry.unregister(Archives.RAR_SCHEME)
        previousRar?.let(FsRegistry::register)
        Dispatchers.resetMain()
    }

    @Test
    fun `large and unknown size remote rar expand without download confirmation or cache copy`() = runTest(dispatcher) {
        for (size in listOf(0L, 500_000_000L)) {
            val remote = Remote(size)
            val rar = Rar()
            FsRegistry.register(remote)
            FsRegistry.register(rar)
            val vm = PaneViewModel(app).apply { io = dispatcher }
            vm.bootstrap(emptyList())
            vm.mountLocated(remote.root(), "Remote")
            advanceUntilIdle()
            val archiveRow = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .first { it.file == remote.archive }

            vm.toggle(archiveRow)
            advanceUntilIdle()

            assertNull(vm.state.value.confirmMaterialize)
            assertEquals(remote.archive, rar.mountedHost)
            assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .any { it.file.scheme == Archives.RAR_SCHEME && it.file.name == "inside.txt" })
            assertEquals("${remote.archive.path}!/", vm.state.value.currentDir?.path)
        }
    }

    @Test
    fun `treemap mounts remote rar without downloading it`() {
        val remote = Remote(500_000_000L)
        val rar = Rar()
        FsRegistry.register(remote)
        FsRegistry.register(rar)

        val tree = TreemapScanner(File(app.cacheDir, "unused-rar-cache")).scanRoot(remote.archive)

        assertEquals(remote.archive, rar.mountedHost)
        assertEquals(listOf("inside.txt"), tree.children!!.map { it.name })
        assertEquals(2L, tree.size)
    }
}
