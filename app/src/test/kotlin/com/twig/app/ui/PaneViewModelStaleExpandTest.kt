package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
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

/**
 * Re-expanding a **local** directory must not show a stale listing.
 *
 * Expanded local directories are watched (`PaneFragment.syncObservers`) and re-listed on
 * resume, but a **collapsed** one is watched by nothing — so anything another app, the shell
 * or the other pane did to it since was missing from the cached children, and re-expanding
 * showed the old contents until the user hit refresh by hand. Listing a local directory is
 * cheap, so the expansion now re-lists behind itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneViewModelStaleExpandTest {

    private lateinit var app: Application
    private lateinit var vm: PaneViewModel
    private lateinit var dir: File
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
        dir = File(Environment.getExternalStorageDirectory(), "stale").apply { mkdirs() }
        File(dir, "first.txt").writeText("1")
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun rowOf(path: String) = vm.state.value.rows
        .filterIsInstance<PaneViewModel.FileNode>().first { it.file.path == path }

    private fun namesUnder() = vm.state.value.rows
        .filterIsInstance<PaneViewModel.FileNode>()
        .filter { it.file.path.startsWith(dir.path + "/") }
        .map { it.file.name }

    @Test
    fun `a directory changed while collapsed is re-listed when expanded again`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${Environment.getExternalStorageDirectory().path}"))
        advanceUntilIdle()

        vm.toggle(rowOf(dir.path)) // expand
        advanceUntilIdle()
        assertEquals(listOf("first.txt"), namesUnder())

        vm.toggle(rowOf(dir.path)) // collapse: nothing watches it from here on
        advanceUntilIdle()

        // another app writes into it while it is closed
        File(dir, "second.txt").writeText("2")
        File(dir, "first.txt").delete()

        vm.toggle(rowOf(dir.path)) // expand again
        advanceUntilIdle()

        assertEquals(
            "the cached listing was shown instead of what is on disk",
            listOf("second.txt"),
            namesUnder(),
        )
    }

    /** An unchanged directory must not be rebuilt for nothing (the rows keep their identity). */
    @Test
    fun `an unchanged directory produces the same rows`() = runTest(dispatcher) {
        vm.bootstrap(listOf("file\t${Environment.getExternalStorageDirectory().path}"))
        advanceUntilIdle()
        vm.toggle(rowOf(dir.path))
        advanceUntilIdle()
        val before = vm.state.value.rows.map { it.key }

        vm.toggle(rowOf(dir.path))
        advanceUntilIdle()
        vm.toggle(rowOf(dir.path))
        advanceUntilIdle()

        assertEquals(before, vm.state.value.rows.map { it.key })
        assertTrue(namesUnder().contains("first.txt"))
    }
}
