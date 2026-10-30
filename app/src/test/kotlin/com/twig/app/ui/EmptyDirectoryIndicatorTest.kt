package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.R
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class EmptyDirectoryIndicatorTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dir = XFile("file", "/example", true)
    private val previous = runCatching { FsRegistry.of("file") }.getOrNull()
    private val attached = ArrayList<Pair<FileAdapter, RecyclerView.ViewHolder>>()
    private fun adapter() = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
    private fun parent() = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))
    private fun bind(adapter: FileAdapter, node: PaneViewModel.FileNode): RecyclerView.ViewHolder {
        adapter.submitList(listOf(node))
        return adapter.createViewHolder(parent(), adapter.getItemViewType(0)).also {
            adapter.bindViewHolder(it, 0)
            adapter.onViewAttachedToWindow(it)
            attached.add(adapter to it)
        }
    }
    private fun indicator(holder: RecyclerView.ViewHolder) = holder.itemView.findViewById<View>(R.id.indicator)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("directory probe did not complete", condition())
    }
    @After fun cleanup() {
        attached.forEach { (adapter, holder) -> adapter.onViewDetachedFromWindow(holder) }
        DirectoryCounts.clear()
        await { DirectoryCounts.isIdle() }
        Prefs.setFolderCounts(app, false)
        Prefs.setFolderCountsLocalOnly(app, true)
        Prefs.setShowHidden(app, false)
        if (previous == null) FsRegistry.unregister("file") else FsRegistry.register(previous)
    }

    @Test fun `empty local directories hide the indicator with counts disabled and stay clickable`() {
        Prefs.setFolderCounts(app, false)
        DirectoryCounts.remember(dir, emptyList())
        var clicked: PaneViewModel.Node? = null
        val adapter = FileAdapter(1, { clicked = it }, {}, {}, { _, _ -> }, {})
        val node = PaneViewModel.FileNode(dir, 1, true, false)
        val holder = bind(adapter, node)
        assertEquals(View.INVISIBLE, indicator(holder).visibility)
        assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.folderCounts).visibility)
        holder.itemView.performClick()
        assertEquals(node, clicked)
        // An expanded empty directory has nothing to collapse either.
        val expanded = bind(adapter(), node.copy(expanded = true))
        assertEquals(View.INVISIBLE, indicator(expanded).visibility)
    }

    @Test fun `hidden entries follow the display setting and synthetic children keep the arrow`() {
        DirectoryCounts.remember(dir, listOf(XFile("file", "/example/.hidden", false)))
        Prefs.setShowHidden(app, false)
        val node = PaneViewModel.FileNode(dir, 1, true, false)
        assertEquals(View.INVISIBLE, indicator(bind(adapter(), node)).visibility)
        Prefs.setShowHidden(app, true)
        assertEquals(View.VISIBLE, indicator(bind(adapter(), node)).visibility)
        Prefs.setShowHidden(app, false)
        assertEquals(View.VISIBLE, indicator(bind(adapter(), node.copy(expanded = true, hasVirtualChildren = true))).visibility)
    }

    @Test fun `nonlocal empty directories keep the arrow even with counts enabled`() {
        Prefs.setFolderCounts(app, true)
        for (scheme in listOf("smb-test", "sftp-test", "saf", "zip")) {
            val file = dir.copy(scheme = scheme)
            DirectoryCounts.remember(file, emptyList())
            assertEquals(scheme, View.VISIBLE,
                indicator(bind(adapter(), PaneViewModel.FileNode(file, 1, true, false))).visibility)
        }
    }

    @Test fun `one pending listing supplies both the arrow and enabled counts and is cached across toggles`() {
        val reads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeFileSystem("file", mapOf(dir.path to emptyList()))
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                reads.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return emptyList()
            }
        })
        val node = PaneViewModel.FileNode(dir, 1, true, false)
        Prefs.setFolderCounts(app, false)
        val withoutCounts = bind(adapter(), node)
        try {
            await { entered.count == 0L }
            assertEquals(View.VISIBLE, indicator(withoutCounts).visibility)
            Prefs.setFolderCounts(app, true)
            val withCounts = bind(adapter(), node)
            release.countDown()
            await { indicator(withCounts).visibility == View.INVISIBLE && indicator(withoutCounts).visibility == View.INVISIBLE }
            assertEquals(View.VISIBLE, withCounts.itemView.findViewById<View>(R.id.folderCounts).visibility)
            assertEquals(1, reads.get())
            Prefs.setFolderCounts(app, false)
            assertEquals(View.INVISIBLE, indicator(bind(adapter(), node)).visibility)
            Prefs.setFolderCounts(app, true)
            assertEquals(View.INVISIBLE, indicator(bind(adapter(), node)).visibility)
            assertEquals(1, reads.get())
        } finally { release.countDown() }
    }

    @Test fun `failed local enumeration keeps the arrow and never becomes a cached zero`() {
        Prefs.setFolderCounts(app, false)
        val sentinel = dir.copy(path = "/sentinel")
        val entered = CountDownLatch(1)
        val fake = FakeFileSystem("file", mapOf(dir.path to emptyList(), sentinel.path to emptyList()))
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                if (dir.path == "/example") {
                    entered.countDown()
                    error("permission denied")
                }
                return emptyList()
            }
        })
        val holder = bind(adapter(), PaneViewModel.FileNode(dir, 1, true, false))
        await { entered.count == 0L }
        // Queue a successful probe after the failed row so its delivery also drains the failure.
        val owner = View(app)
        var done = false
        DirectoryCounts.request(owner, sentinel, false) { done = true }
        await { done }
        assertNull(DirectoryCounts.known(dir, false))
        assertEquals(View.VISIBLE, indicator(holder).visibility)
    }

    @Test fun `recycled or rebound rows reject a late empty result`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeFileSystem("file", mapOf(dir.path to emptyList()))
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return emptyList()
            }
        })
        Prefs.setFolderCounts(app, false)
        val adapter = adapter()
        val holder = bind(adapter, PaneViewModel.FileNode(dir, 1, true, false))
        try {
            await { entered.count == 0L }
            adapter.onViewRecycled(holder)
            val remote = PaneViewModel.FileNode(dir.copy(scheme = "sftp-test"), 1, true, false)
            (holder as FileAdapter.VH).bind(remote)
            release.countDown()
            await { DirectoryCounts.isIdle() }
            assertNull(DirectoryCounts.known(dir, false))
            assertEquals(View.VISIBLE, indicator(holder).visibility)
        } finally { release.countDown() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `expansion and refresh populate shared local counts with the count setting off`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val dirs = mutableMapOf(dir.path to listOf("child"), "/example/child" to emptyList<String>())
        val fs = FakeFileSystem("file", dirs)
        FsRegistry.register(fs)
        Prefs.setFolderCounts(app, false)
        try {
            val vm = PaneViewModel(app).apply { io = dispatcher; lockRoot = dir }
            vm.refreshTree()
            fun row() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().first()
            vm.toggle(row())
            advanceUntilIdle()
            assertEquals(DirectoryCounts.Count(1, 0), DirectoryCounts.known(dir, false))
            fs.listed.clear()
            val holder = bind(adapter(), row())
            assertEquals(View.VISIBLE, indicator(holder).visibility)
            assertTrue("binding reuses the ordinary listing", fs.listed.isEmpty())
            dirs[dir.path] = emptyList()
            vm.refreshOnResume()
            advanceUntilIdle()
            assertEquals(DirectoryCounts.Count(0, 0), row().directoryCount)
            assertEquals(View.INVISIBLE, indicator(bind(adapter(), row())).visibility)
            assertEquals(listOf(dir.path), fs.listed)
            dirs[dir.path] = listOf("child")
            vm.refreshOnResume()
            advanceUntilIdle()
            assertEquals(DirectoryCounts.Count(1, 0), row().directoryCount)
            assertEquals(View.VISIBLE, indicator(bind(adapter(), row())).visibility)
            assertEquals(listOf(dir.path, dir.path), fs.listed)
        } finally { Dispatchers.resetMain() }
    }
}
