package com.twig.app.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.StorageVolumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StorageCapacityTest {
    private lateinit var app: Application
    private val main = StandardTestDispatcher()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
        StorageVolumes.reset()
        Dispatchers.setMain(main)
    }

    @After fun cleanup() {
        StorageVolumes.reset()
        Dispatchers.resetMain()
    }

    @Test fun `rendering defers capacity reads and caches both success and failure`() = runTest(main) {
        val reads = mutableListOf<String>()
        val internal = Environment.getExternalStorageDirectory().absolutePath
        val pane = PaneViewModel(app).apply {
            io = StandardTestDispatcher(testScheduler)
            capacityReader = { path ->
                reads += path
                if (path == "/") error("unreadable mount")
                1024L to 2048L
            }
        }
        try {
            pane.refreshTree()
            pane.refreshTree()
            assertTrue("rendering must not run statvfs inline", reads.isEmpty())
            runCurrent()
            assertEquals(setOf(internal, "/"), reads.toSet())
            assertEquals(2, reads.size)
            val rows = pane.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            assertNotNull(rows.first { it.file.path == internal }.capacity)
            assertNull(rows.first { it.file.path == "/" }.capacity)
            repeat(5) { pane.refreshTree() }
            runCurrent()
            assertEquals("successful and failed reads both need a cooldown", 2, reads.size)
        } finally {
            pane.viewModelScope.cancel()
        }
    }

    @Test fun `a blocked mount does not block rendering or another capacity read`() = runTest(main) {
        val started = CountDownLatch(1)
        val otherRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockedReads = AtomicInteger()
        val uiThread = Thread.currentThread()
        val workerThreads = java.util.concurrent.ConcurrentLinkedQueue<Thread>()
        val workers = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val pane = PaneViewModel(app).apply {
            io = workers
            capacityReader = { path ->
                workerThreads += Thread.currentThread()
                if (path == "/") {
                    blockedReads.incrementAndGet()
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                } else otherRead.countDown()
                1024L to 2048L
            }
        }
        try {
            pane.refreshTree()
            runCurrent()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue("other roots must not queue behind the blocked mount", otherRead.await(2, TimeUnit.SECONDS))
            assertTrue(workerThreads.all { it !== uiThread })
            repeat(5) { pane.refreshTree() }
            runCurrent()
            assertEquals("rebuilds must not duplicate a blocked request", 1, blockedReads.get())
            assertTrue(pane.state.value.rows.isNotEmpty())
        } finally {
            release.countDown()
            pane.viewModelScope.cancel()
            workers.close()
        }
    }
}
