package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnixPermissionsRowTest {
    @Test fun `permission rows default hidden and setting refreshes the layout without hiding the Unix tab`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val ctx = ContextThemeWrapper(host.get(), R.style.Theme_Twig)
        val previous = Prefs.unixPermissions(ctx)
        try {
            assertFalse(Prefs.unixPermissions(ctx))
            val signature = Prefs.uiSignature(ctx)
            val file = XFile("file", "/file.bin", false, canRead = false, unixMode = "100755".toInt(8))
            val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
            adapter.submitList(listOf(PaneViewModel.FileNode(file, 1, false, false)))
            val holder = adapter.createViewHolder(FrameLayout(ctx), adapter.getItemViewType(0))
            val permissions = holder.itemView.findViewById<TextView>(R.id.permissions)
            adapter.bindViewHolder(holder, 0)
            assertEquals(View.GONE, permissions.visibility)
            assertTrue(com.twig.app.FileInfo.initial(ctx, file).sections.any {
                it.title == ctx.getString(R.string.info_section_unix)
            })
            Prefs.setUnixPermissions(ctx, true)
            assertNotEquals(signature, Prefs.uiSignature(ctx))
            adapter.bindViewHolder(holder, 0)
            assertEquals(View.VISIBLE, permissions.visibility)
            assertEquals("rwxr-xr-x", permissions.text.toString())
            Prefs.setUnixPermissions(ctx, false)
            adapter.bindViewHolder(holder, 0)
            assertEquals(View.GONE, permissions.visibility)
            assertEquals("", permissions.text.toString())
            assertEquals(signature, Prefs.uiSignature(ctx))
        } finally {
            Prefs.setUnixPermissions(ctx, previous)
            host.pause().stop().destroy()
        }
    }

    @Test fun `count digits zeros and pending counts never move the permission column at any text size`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val ctx = ContextThemeWrapper(host.get(), R.style.Theme_Twig)
        val previous = Prefs.folderCounts(ctx)
        val previousPermissions = Prefs.unixPermissions(ctx)
        Prefs.setUnixPermissions(ctx, true)
        Prefs.setFolderCounts(ctx, true)
        try {
            val parent = FrameLayout(ctx)
            host.get().setContentView(parent)
            val file = XFile("file", "/example/folder", true, lastModified = 1600000000000L, unixMode = "40755".toInt(8))
            val counts = listOf(null, DirectoryCounts.Count(0, 0), DirectoryCounts.Count(1, 0),
                DirectoryCounts.Count(0, 12), DirectoryCounts.Count(345, 6789))
            for (textSize in 0..2) {
                val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, textSize = textSize)
                adapter.submitList(counts.mapIndexed { index, count ->
                    PaneViewModel.FileNode(file.copy(path = "/example/folder$index"), 1, true, false,
                        loading = true, directoryCount = count)
                })
                val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
                parent.addView(holder.itemView)
                var start: Int? = null
                for (index in counts.indices) {
                    adapter.bindViewHolder(holder, index)
                    val row = holder.itemView
                    row.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    row.layout(0, 0, 480, row.measuredHeight)
                    val permissions = row.findViewById<TextView>(R.id.permissions)
                    val group = row.findViewById<View>(R.id.folderCounts)
                    val metadata = row.findViewById<TextView>(R.id.meta)
                    if (start == null) start = permissions.left
                    assertEquals("text size $textSize, count ${counts[index]}", start, permissions.left)
                    val baseline = permissions.top + permissions.baseline
                    for (id in listOf(R.id.directoryCount, R.id.fileCount)) {
                        val counter = row.findViewById<TextView>(id)
                        assertEquals(baseline, group.top + counter.top + counter.baseline)
                    }
                    assertEquals(baseline, metadata.top + metadata.baseline)
                }
                parent.removeView(holder.itemView)
            }
        } finally {
            Prefs.setFolderCounts(ctx, previous)
            Prefs.setUnixPermissions(ctx, previousPermissions)
            host.pause().stop().destroy()
        }
    }

    @Test fun `permissions follow counts precede metadata and clear on unknown rows`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val ctx = ContextThemeWrapper(host.get(), R.style.Theme_Twig)
        val previous = Prefs.folderCounts(ctx)
        val previousPermissions = Prefs.unixPermissions(ctx)
        Prefs.setUnixPermissions(ctx, true)
        Prefs.setFolderCounts(ctx, true)
        try {
            val parent = FrameLayout(ctx)
            host.get().setContentView(parent)
            val file = XFile("file", "/example/folder", true, lastModified = 1600000000000L, unixMode = "40755".toInt(8))
            val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
            adapter.submitList(listOf(
                PaneViewModel.FileNode(file, 1, true, false, loading = true, directoryCount = DirectoryCounts.Count(3, 12)),
                PaneViewModel.FileNode(file.copy(path = "/unknown", unixMode = null), 1, true, false, loading = true),
                PaneViewModel.FileNode(file.copy(path = "/locked.bin", isDir = false, unixMode = 0, lastModified = 0), 1, false, false),
            ))
            val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
            parent.addView(holder.itemView)
            adapter.bindViewHolder(holder, 0)
            val row = holder.itemView
            val permissions = row.findViewById<TextView>(R.id.permissions)
            val counts = row.findViewById<View>(R.id.folderCounts)
            val meta = row.findViewById<TextView>(R.id.meta)
            val metadata = row.findViewById<ViewGroup>(R.id.metaRow)
            assertEquals(View.VISIBLE, permissions.visibility)
            assertEquals("rwxr-xr-x", permissions.text.toString())
            assertTrue(metadata.indexOfChild(counts) < metadata.indexOfChild(permissions))
            assertTrue(metadata.indexOfChild(permissions) < metadata.indexOfChild(meta))
            for (width in listOf(180, 240, 360)) {
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, width, row.measuredHeight)
                assertTrue("$width dp: permissions must fit the text column", permissions.right <= metadata.width)
                assertTrue("$width dp: counts must precede permissions", counts.right <= permissions.left)
            }
            val directoryCount = row.findViewById<TextView>(R.id.directoryCount)
            val fileCount = row.findViewById<TextView>(R.id.fileCount)
            val baseline = permissions.top + permissions.baseline
            assertEquals(baseline, counts.top + directoryCount.top + directoryCount.baseline)
            assertEquals(baseline, counts.top + fileCount.top + fileCount.baseline)
            assertEquals(baseline, meta.top + meta.baseline)
            File("build/previews").mkdirs()
            Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                row.draw(Canvas(bitmap))
                File("build/previews/unix-permissions-row.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val permissionStart = permissions.left
            adapter.bindViewHolder(holder, 1)
            assertEquals(View.GONE, permissions.visibility)
            assertEquals("", permissions.text.toString())
            adapter.bindViewHolder(holder, 2)
            assertEquals(View.VISIBLE, permissions.visibility)
            assertEquals("---------", permissions.text.toString())
            assertEquals(View.INVISIBLE, counts.visibility)
            row.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, 360, row.measuredHeight)
            assertEquals(permissionStart, permissions.left)
            assertEquals(View.VISIBLE, metadata.visibility)
        } finally {
            Prefs.setFolderCounts(ctx, previous)
            Prefs.setUnixPermissions(ctx, previousPermissions)
            host.pause().stop().destroy()
        }
    }
}
