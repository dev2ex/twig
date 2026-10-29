package com.twig.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.SavedConnection
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.XFile
import com.twig.core.FsRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderCountsRowTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    @After fun tearDown() { Prefs.setFolderCounts(app, false); Prefs.setFolderCountsLocalOnly(app, true); DirectoryCounts.clear() }
    private val dir = XFile("file", "/storage/Example", true, lastModified = 1700000000000L)
    private fun adapter() = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
    private fun parent() = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))
    private fun seed() {
        DirectoryCounts.remember(dir, (1..2).map { XFile("file", dir.path + "/Folder$it", true) } +
            (1..5).map { XFile("file", dir.path + "/File$it", false) })
    }

    @Test fun `local only scope hides remote counts and media services always stay excluded`() {
        Prefs.setFolderCounts(app, true)
        assertTrue(Prefs.folderCountsLocalOnly(app))
        val initialSignature = Prefs.uiSignature(app)
        for (localOnly in listOf(true, false)) {
            Prefs.setFolderCountsLocalOnly(app, localOnly)
            for (scheme in listOf("file", "smb-test", "sftp-test", "ftp-test", "webdav-test", "s3-test", "jellyfin-test", "emby-test")) {
                val file = dir.copy(scheme = scheme)
                DirectoryCounts.remember(file, listOf(XFile(scheme, file.path + "/child", false)))
                val adapter = adapter()
                adapter.submitList(listOf(PaneViewModel.FileNode(file, 1, true, false)))
                val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
                adapter.bindViewHolder(holder, 0)
                val visible = (scheme == "file" || !localOnly) && !scheme.startsWith("jellyfin") && !scheme.startsWith("emby")
                assertEquals("scope for $scheme with localOnly=$localOnly", if (visible) View.VISIBLE else View.GONE,
                    holder.itemView.findViewById<View>(R.id.folderCounts).visibility)
            }
        }
        assertNotEquals(initialSignature, Prefs.uiSignature(app))
    }

    @Test fun `count icons sit before the timestamp with the idle selection check color`() {
        Prefs.setFolderCounts(app, true)
        seed()
        val adapter = adapter()
        adapter.submitList(listOf(PaneViewModel.FileNode(dir, 1, true, false)))
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val row = holder.itemView
        row.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val width = (400 * app.resources.displayMetrics.density).toInt()
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, width, row.measuredHeight)
        val counts = row.findViewById<View>(R.id.folderCounts)
        val meta = row.findViewById<TextView>(R.id.meta)
        assertEquals(View.VISIBLE, counts.visibility)
        val directories = row.findViewById<TextView>(R.id.directoryCount)
        val files = row.findViewById<TextView>(R.id.fileCount)
        assertEquals("2", directories.text.toString())
        assertEquals("5", files.text.toString())
        assertNotNull(directories.compoundDrawablesRelative[0])
        assertNotNull(files.compoundDrawablesRelative[0])
        assertTrue("outlined folder icon must reserve space before the number", directories.compoundPaddingLeft > 0)
        assertTrue("outlined file icon must reserve space before the number", files.compoundPaddingLeft > 0)
        assertEquals(meta.currentTextColor, directories.currentTextColor)
        assertEquals(meta.currentTextColor, files.currentTextColor)
        assertEquals(row.findViewById<View>(R.id.check).alpha, counts.alpha, 0.001f)
        assertEquals(directories.top + directories.height / 2, files.top + files.height / 2)
        assertTrue("counts align with the start of the directory name", counts.left == 0)
        assertTrue("timestamp stays to the right of both counts", meta.left >= counts.right)
        assertTrue(meta.text.isNotBlank())
        assertEquals(View.VISIBLE, meta.visibility)
        assertTrue("timestamp has usable bounds: ${meta.width} x ${meta.height}", meta.width > 0 && meta.height > 0)
        assertTrue("right-aligned time must be laid out within its visible width", meta.layout.width <= meta.width)
        val bitmap = Bitmap.createBitmap(width, row.height, Bitmap.Config.ARGB_8888)
        row.draw(Canvas(bitmap))
        File(System.getProperty("java.io.tmpdir"), "twig-folder-counts.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun `zero counts are hidden without shifting either count slot`() {
        Prefs.setFolderCounts(app, true)
        val adapter = adapter()
        val values = listOf(DirectoryCounts.Count(2, 5), DirectoryCounts.Count(0, 5),
            DirectoryCounts.Count(9999, 0), DirectoryCounts.Count(0, 0))
        adapter.submitList(values.mapIndexed { index, value ->
            val file = dir.copy(path = "/storage/Example$index")
            DirectoryCounts.remember(file,
                (1..value.directories).map { XFile("file", file.path + "/Dir$it", true) } +
                (1..value.files).map { XFile("file", file.path + "/File$it", false) })
            PaneViewModel.FileNode(file, 1, true, false)
        })
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        val width = (400 * app.resources.displayMetrics.density).toInt()
        var fileLeft: Int? = null
        values.forEachIndexed { index, value ->
            adapter.onBindViewHolder(holder, index)
            val row = holder.itemView
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, width, row.measuredHeight)
            val directories = row.findViewById<TextView>(R.id.directoryCount)
            val files = row.findViewById<TextView>(R.id.fileCount)
            assertEquals(if (value.directories == 0) View.INVISIBLE else View.VISIBLE, directories.visibility)
            assertEquals(if (value.files == 0) View.INVISIBLE else View.VISIBLE, files.visibility)
            assertEquals(0, directories.left)
            if (fileLeft == null) fileLeft = files.left else assertEquals(fileLeft, files.left)
        }
    }

    @Test fun `setting is independent defaults off and disabled rows do not request counts`() {
        assertFalse(Prefs.folderCounts(app))
        val before = Prefs.uiSignature(app)
        Prefs.setFolderCounts(app, true)
        assertNotEquals(before, Prefs.uiSignature(app))
        Prefs.setFolderCounts(app, false)
        seed()
        val adapter = adapter()
        adapter.submitList(listOf(PaneViewModel.FileNode(dir, 1, true, false,
            directoryCount = DirectoryCounts.Count(2, 5))))
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val counts = holder.itemView.findViewById<View>(R.id.folderCounts)
        assertEquals(View.GONE, counts.visibility)
        assertNull(counts.getTag(R.id.directory_count))
    }

    @Test fun `reusing a directory holder for a file clears its counters`() {
        Prefs.setFolderCounts(app, true)
        seed()
        val adapter = adapter()
        adapter.submitList(listOf(PaneViewModel.FileNode(dir, 1, true, false),
            PaneViewModel.FileNode(XFile("file", "/file.txt", false, size = 4), 1, false, false)))
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.folderCounts).visibility)
        adapter.onBindViewHolder(holder, 1)
        val counts = holder.itemView.findViewById<View>(R.id.folderCounts)
        assertEquals(View.GONE, counts.visibility)
        assertNull(counts.getTag(R.id.directory_count))
    }

    @Test fun `storage root rows hide cached counts and do not request probes`() {
        Prefs.setFolderCounts(app, true)
        seed()
        val roots = listOf("/storage/emulated/0" to "Internal storage", "/" to "Root")
        val adapter = adapter()
        adapter.submitList(listOf(PaneViewModel.FileNode(dir, 1, true, false)) + roots.map { (path, label) ->
            PaneViewModel.FileNode(XFile("file", path, true), 0, true, false,
                label = label, directoryCount = DirectoryCounts.Count(2, 5))
        })
        val holder = adapter.createViewHolder(parent(), adapter.getItemViewType(0))
        for (position in roots.indices.map { it + 1 }) {
            adapter.onBindViewHolder(holder, 0)
            assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.folderCounts).visibility)
            adapter.onBindViewHolder(holder, position)
            val counts = holder.itemView.findViewById<View>(R.id.folderCounts)
            assertEquals(View.GONE, counts.visibility)
            assertNull(counts.getTag(R.id.directory_count))
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `ordinary SFTP expansion and resume update counts without a separate probe`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val conn = SavedConnection("sftp", "counts.test")
        val scheme = Connections.schemeOf(conn)
        val dirs = mutableMapOf("/" to listOf("dir"), "/dir" to listOf("sub", "first.txt"), "/dir/sub" to emptyList())
        val files = mutableMapOf("/dir/first.txt" to "first")
        val fs = FakeFileSystem(scheme, dirs, files)
        FsRegistry.register(fs)
        ConnectionStore.save(app, conn)
        Prefs.setFolderCountsLocalOnly(app, false)
        Prefs.setFolderCounts(app, true)
        try {
            val vm = PaneViewModel(app).apply { io = dispatcher }
            vm.revealPath(XFile(scheme, "/dir", true))
            advanceUntilIdle()
            fun count() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .single { it.file.scheme == scheme && it.file.path == "/dir" }.directoryCount
            assertEquals(DirectoryCounts.Count(1, 1), count())
            dirs["/dir"] = listOf("first.txt", "other.txt")
            files["/dir/other.txt"] = "other"
            fs.listed.clear()
            vm.refreshOnResume()
            advanceUntilIdle()
            assertEquals(DirectoryCounts.Count(0, 2), count())
            assertEquals(listOf("/dir"), fs.listed)
        } finally {
            FsRegistry.unregister(scheme)
            Dispatchers.resetMain()
        }
    }

}
