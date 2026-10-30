package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectoryCountLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("directory count state did not settle")
    }

    @Test fun `activating a pane counts rows in display order despite reversed attachment`() {
        val scheme = "sftp-count-display-order"
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        val fake = FakeFileSystem(scheme, emptyMap())
        val prefs = app.getSharedPreferences("twig_prefs", 0)
        prefs.edit().clear().commit()
        Prefs.setFolderCounts(app, true)
        Prefs.setFolderCountsLocalOnly(app, false)
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                calls.add(dir.path)
                return emptyList()
            }
        })
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.setDirectoryCountsActive(false)
        val parent = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))
        val paths = listOf("/Zulu", "/Alpha", "/Middle")
        adapter.submitList(paths.map { PaneViewModel.FileNode(XFile(scheme, it, true), 1, true, false) })
        val holders = paths.indices.map { position ->
            adapter.createViewHolder(parent, adapter.getItemViewType(position)).also {
                adapter.bindViewHolder(it, position)
            }
        }
        try {
            listOf(2, 1, 0).forEach { adapter.onViewAttachedToWindow(holders[it]) }
            adapter.setDirectoryCountsActive(true)
            await { calls.size == 3 && DirectoryCounts.isIdle() }
            assertEquals(paths, calls.toList())
        } finally {
            holders.forEach { adapter.onViewDetachedFromWindow(it) }
            DirectoryCounts.clear()
            await { DirectoryCounts.isIdle() }
            FsRegistry.unregister(scheme)
            prefs.edit().clear().commit()
        }
    }

    @Test fun `prefetch does not enumerate and hidden or detached rows release pending work`() {
        val scheme = "sftp-count-lifecycle"
        val root = XFile(scheme, "/", true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        val fake = FakeFileSystem(scheme, emptyMap())
        val prefs = app.getSharedPreferences("twig_prefs", 0)
        prefs.edit().clear().commit()
        Prefs.setFolderCounts(app, true)
        Prefs.setFolderCountsLocalOnly(app, false)
        FsRegistry.register(object : FileSystem by fake {
            override fun list(dir: XFile): List<XFile> {
                calls.add(dir.path)
                if (dir.path == "/slow") {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                return listOf(XFile(scheme, dir.path + "/file", false))
            }
        })
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        val parent = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))
        val rows = listOf("/slow", "/hidden", "/detached", "/current").map {
            PaneViewModel.FileNode(root.copy(path = it), 1, true, false)
        }
        adapter.submitList(rows)
        val holders = rows.indices.map { position ->
            adapter.createViewHolder(parent, adapter.getItemViewType(position)).also { adapter.bindViewHolder(it, position) }
        }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("binding prefetched holders must not start counts", calls.isEmpty())
            adapter.onViewAttachedToWindow(holders[0])
            await { entered.count == 0L }
            adapter.onViewAttachedToWindow(holders[1])
            adapter.setDirectoryCountsActive(false)
            assertNull(holders[1].itemView.findViewById<View>(R.id.folderCounts).getTag(R.id.directory_count))
            adapter.onViewDetachedFromWindow(holders[0])
            adapter.onViewDetachedFromWindow(holders[1])
            adapter.onViewAttachedToWindow(holders[2])
            adapter.setDirectoryCountsActive(true)
            adapter.onViewDetachedFromWindow(holders[2])
            adapter.onViewAttachedToWindow(holders[3])
            release.countDown()
            await { DirectoryCounts.known(rows[3].file, false) != null }
            assertEquals(listOf("/slow", "/current"), calls.toList())
            assertNull(DirectoryCounts.known(rows[0].file, false))
            assertNull(DirectoryCounts.known(rows[1].file, false))
            assertNull(DirectoryCounts.known(rows[2].file, false))
        } finally {
            release.countDown()
            holders.forEach { adapter.onViewDetachedFromWindow(it) }
            DirectoryCounts.clear()
            await { DirectoryCounts.isIdle() }
            FsRegistry.unregister(scheme)
            prefs.edit().clear().commit()
        }
    }
}
