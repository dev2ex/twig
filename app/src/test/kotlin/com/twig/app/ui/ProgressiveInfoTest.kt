package com.twig.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.SavedConnection
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressiveInfoTest {
    private val main = StandardTestDispatcher()
    private val held = object : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun next() { tasks.removeFirst().run() }
    }
    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var fs: FileSystem
    private var reads = 0

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(main)
        val conn = SavedConnection("sftp", "info.test", user = "test")
        ConnectionStore.save(app, conn)
        val fake = FakeFileSystem(Connections.schemeOf(conn), mapOf("/" to listOf("clip.mkv", "image.png")),
            mapOf("/clip.mkv" to "not a video", "/image.png" to "PNG"))
        fs = object : FileSystem by fake {
            override fun openInput(file: XFile): java.io.InputStream {
                reads++
                // A generated PNG header is enough for the image dimensions (no network or decoder required).
                return ByteArrayInputStream(java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF9sAAAAASUVORK5CYII="))
            }
        }
        FsRegistry.register(fs)
        vm = PaneViewModel(app).apply { io = main }
    }

    @After fun cleanup() {
        FsRegistry.unregister(fs.scheme)
        Dispatchers.resetMain()
    }

    private fun card() = vm.state.value.rows.filterIsInstance<PaneViewModel.InfoNode>().single()

    @Test fun `basic fields appear before media IO and failure ends the loading state`() = runTest(main) {
        val file = fs.resolve("/clip.mkv")
        vm.revealPath(fs.root(), focus = file); advanceUntilIdle()
        vm.io = held
        vm.toggleInfo(file); runCurrent()
        held.next(); runCurrent() // Basic listing metadata only, media work remains queued.
        val partial = card().details!!
        assertTrue(partial.sections.first().rows.any { it.second == "clip.mkv" })
        assertEquals(0, reads)
        assertTrue(partial.sections[1].loading)
        vm.selectInfoTab(card(), 1)
        held.next(); runCurrent()
        val complete = card()
        assertEquals(1, complete.tab)
        assertFalse(complete.details!!.sections[1].loading)
        assertTrue(complete.details!!.sections[1].rows.isEmpty())
    }

    @Test fun `closing and reopening rejects old loading work`() = runTest(main) {
        val file = fs.resolve("/image.png")
        vm.revealPath(fs.root(), focus = file); advanceUntilIdle()
        vm.io = held
        vm.toggleInfo(file); runCurrent()
        held.next(); runCurrent()
        vm.toggleInfo(file); runCurrent()
        assertFalse(vm.state.value.rows.any { it is PaneViewModel.InfoNode })
        vm.toggleInfo(file); runCurrent()
        held.next(); runCurrent() // Cancelled request must not read the image.
        assertEquals(0, reads)
        assertNull(card().details)
        held.next(); runCurrent() // New Basic phase.
        assertEquals(1, card().details!!.sections.size)
        held.next(); runCurrent()
        assertEquals(1, reads)
        assertEquals(2, card().details!!.sections.size)
    }

    @Test fun `hash stays selected when delayed metadata adds a tab`() = runTest(main) {
        val file = fs.resolve("/image.png")
        vm.revealPath(fs.root(), focus = file); advanceUntilIdle()
        vm.io = held
        vm.toggleInfo(file); runCurrent()
        held.next(); runCurrent()
        vm.selectInfoTab(card(), card().details!!.sections.size)
        held.next(); runCurrent()
        assertEquals(2, card().details!!.sections.size)
        assertEquals(card().details!!.sections.size, card().tab)
    }
    @Test fun `second page shortcut waits for image metadata and focuses an already open card`() = runTest(main) {
        val file = fs.resolve("/image.png")
        vm.revealPath(fs.root(), focus = file); advanceUntilIdle()
        vm.io = held
        vm.showInfo(file, 1); runCurrent()
        held.next(); runCurrent()
        assertEquals(0, card().tab)
        held.next(); runCurrent()
        assertEquals(2, card().details!!.sections.size)
        assertEquals(1, card().tab) // EXIF, rather than Hash (which was temporarily the second tab).
        vm.showInfo(file)
        assertEquals(0, card().tab)
        vm.showInfo(file, 1)
        assertEquals(1, card().tab)
        assertEquals(1, reads)
    }

    @Test fun `second page shortcut selects remote hash without reading the whole file`() = runTest(main) {
        val file = fs.resolve("/image.png").copy(path = "/clip.mkv.txt")
        // Mount the source with a name that has no metadata section.
        val plain = FakeFileSystem(fs.scheme, mapOf("/" to listOf("clip.mkv.txt")), mapOf(file.path to "text"))
        FsRegistry.register(plain)
        vm.revealPath(plain.root(), focus = file); advanceUntilIdle()
        vm.showInfo(file, 1); advanceUntilIdle()
        assertEquals(1, card().tab)
        assertEquals(1, card().details!!.sections.size)
        assertEquals(0, reads)
    }

    @Test fun `manual page choice supersedes a pending second page shortcut`() = runTest(main) {
        val file = fs.resolve("/clip.mkv")
        vm.revealPath(fs.root(), focus = file); advanceUntilIdle()
        vm.io = held
        vm.showInfo(file, 1); runCurrent()
        held.next(); runCurrent()
        vm.selectInfoTab(card(), 0)
        held.next(); runCurrent()
        assertEquals(0, card().tab)
    }

    @Test fun `normal properties toggle closes a card opened on the second page`() = runTest(main) {
        val file = fs.resolve("/image.png")
        vm.revealPath(fs.root(), focus = file); advanceUntilIdle()
        vm.showInfo(file, 1); advanceUntilIdle()
        assertEquals(1, card().tab)
        vm.toggleInfo(file)
        assertFalse(vm.state.value.rows.any { it is PaneViewModel.InfoNode })
        vm.toggleInfo(file); advanceUntilIdle()
        assertEquals(0, card().tab)
    }

}
