package com.twig.app.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.text.SpannableString
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.ShareSourceFileSystem
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SharedTextTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun resetFactory() {
        // AndroidX caches the Application across Robolectric test sandboxes.
        org.robolectric.util.ReflectionHelpers.setStaticField(
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null,
        )
    }
    private fun send(text: CharSequence) = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)

    @Test fun `plain and styled text retain whitespace and Unicode without interpreting URLs`() {
        val text = "  中文 🙂\r\nhttps://example.com/?q=a&b=c\n"
        assertEquals(text, ShareTargetActivity.sharedText(send(SpannableString(text))))
        assertEquals(" \n", ShareTargetActivity.sharedText(send(" \n")))
        assertNull(ShareTargetActivity.sharedText(send("")))
        assertEquals(text, ShareTargetActivity.sharedText(Intent(Intent.ACTION_SEND)
            .apply { clipData = ClipData.newPlainText("text", text) }))
    }

    @Test fun `attachments and rejected private URIs never fall back to saving a caption`() {
        val intent = send("caption").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(File(app.filesDir, "secret")))
        assertNull(ShareTargetActivity.sharedText(intent))
        assertNull(ShareTargetActivity.sharedText(send("caption").setAction(Intent.ACTION_SEND_MULTIPLE)))
        assertNull(ShareTargetActivity.sharedText(send("caption").apply {
            clipData = ClipData.newRawUri("file", Uri.parse("content://other/file"))
        }))
        assertFalse(IncomingUri.allowed(app, Uri.parse("data:text/plain;base64,c2VjcmV0")))
    }

    @Test fun `text copies as exact UTF8 bytes and existing files keep the normal conflict policy`() {
        val fs = ShareSourceFileSystem(app)
        FsRegistry.register(fs)
        FsRegistry.register(LocalFileSystem())
        val dir = File(app.cacheDir, "shared-text-test").apply { mkdirs() }
        try {
            val text = "中文 🙂\r\n  line two\n"
            val source = fs.wrapText(text, "notes.txt")
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), source.size)
            assertEquals("notes.txt", source.name)
            val target = File(dir, source.name).apply { writeText("keep me") }
            var conflicts = 0
            CopyEngine.transfer(listOf(source), XFile("file", dir.path, true), move = false, resolver = { _, _ ->
                conflicts++
                CopyEngine.Decision.RENAME
            })
            assertEquals(1, conflicts)
            assertEquals("keep me", target.readText())
            val saved = dir.listFiles()!!.single { it != target }
            assertArrayEquals(text.toByteArray(Charsets.UTF_8), saved.readBytes())
            // A fresh source instance can read the same item: no Activity or temporary-file lifetime.
            assertEquals(text, ShareSourceFileSystem(app).openInput(source).bufferedReader().use { it.readText() })
        } finally {
            dir.deleteRecursively()
            FsRegistry.unregister("share")
            FsRegistry.unregister("file")
        }
    }

    @Test fun `filename survives Activity recreation and text shares expose Save`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        Prefs.setRememberLocation(app, false)
        val controller = Robolectric.buildActivity(ShareTargetActivity::class.java, send("some text"))
        try {
            controller.setup()
            val activity = controller.get()
            val field = activity.findViewById<EditText>(R.id.text_filename)
            assertEquals(android.view.View.VISIBLE, field.visibility)
            assertEquals(activity.getString(R.string.viewer_save),
                activity.findViewById<android.widget.TextView>(R.id.btn_confirm).text)
            field.setText("my notes.txt")
            activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu.getItem(0).actionView!!.performClick()
            val state = Bundle()
            controller.saveInstanceState(state).pause().stop().destroy()
            val restored = Robolectric.buildActivity(ShareTargetActivity::class.java, send("some text"))
            try {
                restored.create(state).start().restoreInstanceState(state).resume().visible()
                assertEquals("my notes.txt", restored.get().findViewById<EditText>(R.id.text_filename).text.toString())
                val right = restored.get().supportFragmentManager.findFragmentByTag("share_pane_1")!!
                assertTrue(restored.get().isPaneActive(right as PaneFragment))
                assertNull(restored.get().findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).subtitle)
            } finally { restored.pause().stop().destroy() }
        } finally {
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        }
    }

    @Test fun `untouched text share survives repeated measured orientation changes`() =
        assertShareRotates(send("some text"), text = true)

    @Test fun `single file share survives repeated measured orientation changes`() =
        assertShareRotates(Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://external-share/document.bin")), text = false)

    @Test fun `multiple image share survives repeated measured orientation changes`() =
        assertShareRotates(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(
                Uri.parse("content://external-share/first.jpg"), Uri.parse("content://external-share/second.jpg"),
            )), text = false)

    private fun assertShareRotates(intent: Intent, text: Boolean) {
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        Prefs.setRememberLocation(app, false)
        val controller = Robolectric.buildActivity(ShareTargetActivity::class.java, intent)
        try {
            controller.setup()
            val activity = controller.get()
            val summary = activity.findViewById<android.widget.TextView>(R.id.tv_summary).text.toString()
            val panes = (0..1).map { activity.supportFragmentManager.findFragmentByTag("share_pane_$it") }
            fun measure(land: Boolean) {
                val root = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).parent as android.view.View
                val width = if (land) 800 else 400
                val height = if (land) 400 else 800
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            measure(false)
            repeat(12) { turn ->
                val land = turn % 2 == 0
                val config = android.content.res.Configuration(activity.resources.configuration).apply {
                    orientation = if (land) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        else android.content.res.Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp = if (land) 800 else 400
                    screenHeightDp = if (land) 400 else 800
                }
                controller.configurationChange(config)
                measure(land)
                val stripId = if (land) R.id.strip_mid else R.id.strip_right
                val strip = activity.findViewById<android.view.View>(stripId)
                assertEquals(android.view.View.VISIBLE, strip.visibility)
                val grid = strip.findViewById<StripGrid>(R.id.as_grid)
                assertEquals(if (land) 2 else 1, grid.columnCount)
                for (i in 0..1) assertSame(panes[i], activity.supportFragmentManager.findFragmentByTag("share_pane_$i"))
                val filename = activity.findViewById<EditText>(R.id.text_filename)
                assertEquals(if (text) android.view.View.VISIBLE else android.view.View.GONE, filename.visibility)
                if (text) assertEquals(activity.getString(R.string.share_text_filename), filename.text.toString())
                assertEquals(summary, activity.findViewById<android.widget.TextView>(R.id.tv_summary).text.toString())
                assertFalse(activity.isFinishing)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `switching panes uses independent root visibility and only the active destination controls Save`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        Prefs.setRememberLocation(app, false)
        Prefs.setRootItemVisible(app, 0, "private", false)
        Prefs.setRootItemVisible(app, 1, "private", true)
        val controller = Robolectric.buildActivity(ShareTargetActivity::class.java, send("text"))
        try {
            controller.setup()
            val activity = controller.get()
            fun pane(index: Int) = activity.supportFragmentManager.findFragmentByTag("share_pane_$index") as PaneFragment
            val left = pane(0)
            val right = pane(1)
            fun hasPrivate(pane: PaneFragment) = pane.viewModel.state.value.rows
                .filterIsInstance<PaneViewModel.FileNode>().any { it.label == activity.getString(R.string.private_space) }
            assertFalse(hasPrivate(left))
            assertTrue(hasPrivate(right))
            assertFalse(activity.isPaneActive(right))
            val confirm = activity.findViewById<android.view.View>(R.id.btn_confirm)
            val dir = XFile("file", app.cacheDir.path, true)
            left.viewModel.currentDir = dir
            left.viewModel.refreshTree()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(confirm.isEnabled)
            activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu.getItem(0).actionView!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activity.isPaneActive(left))
            assertTrue(activity.isPaneActive(right))
            val browser = activity.findViewById<PaneLayout>(R.id.browser)
            browser.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
            browser.layout(0, 0, 400, 600)
            browser.finishTransition()
            val strip = browser.findViewById<android.view.View>(R.id.strip_right)
            val rightPane = browser.findViewById<android.view.View>(R.id.pane_b)
            assertEquals(0, strip.left - browser.scrollX)
            assertEquals(strip.width, rightPane.left - browser.scrollX)
            assertFalse(confirm.isEnabled)
            // Background renders of the hidden pane must not enable saving to an absent target.
            left.viewModel.refreshTree()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(confirm.isEnabled)
            activity.onPaneSwipe(1f)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isPaneActive(left))
            assertEquals(dir, left.viewModel.currentDir)
            assertTrue(confirm.isEnabled)
        } finally {
            controller.pause().stop().destroy()
            Prefs.setRootItemVisible(app, 1, "private", false)
        }
    }

    @Test fun `destination toolbar hides mutations and opens sorting search and history`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        Prefs.setRememberLocation(app, false)
        val controller = Robolectric.buildActivity(ShareTargetActivity::class.java, send("text"))
        try {
            controller.setup()
            val activity = controller.get()
            assertNull(activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).subtitle)
            val strip = activity.findViewById<android.view.View>(R.id.strip_right)
            assertEquals(android.view.View.GONE, strip.findViewById<android.view.View>(R.id.as_compress).visibility)
            assertEquals(android.view.View.GONE, strip.findViewById<android.view.View>(R.id.as_share).visibility)
            strip.findViewById<android.view.View>(R.id.as_sort).performClick()
            val sort = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(sort.isShowing)
            sort.findViewById<android.widget.RadioButton>(R.id.rb_size)!!.isChecked = true
            sort.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(com.twig.app.FileSortKey.SIZE, com.twig.app.SortSpec.load(app).by)
            val pane = activity.supportFragmentManager.findFragmentByTag("share_pane_0") as PaneFragment
            pane.viewModel.currentDir = XFile("file", app.cacheDir.path, true)
            pane.viewModel.refreshTree()
            strip.findViewById<android.view.View>(R.id.as_search).performClick()
            val search = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertNotSame(sort, search)
            assertTrue(search.isShowing)
            search.dismiss()
            strip.findViewById<android.view.View>(R.id.as_history).performClick()
            val history = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertNotSame(search, history)
            assertTrue(history.isShowing)
            history.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `confirming sort reorders already expanded directories in both destination panes`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        Prefs.setRememberLocation(app, false)
        com.twig.app.SortSpec.save(app, com.twig.app.SortSpec())
        val dir = File(app.cacheDir, "sort-destination").apply { mkdirs() }
        File(dir, "a-large.txt").writeText("123456789")
        File(dir, "z-small.txt").writeText("1")
        File(dir, "a-old").apply { mkdirs(); setLastModified(100000) }
        File(dir, "z-new").apply { mkdirs(); setLastModified(200000) }
        val controller = Robolectric.buildActivity(ShareTargetActivity::class.java, send("text"))
        try {
            controller.setup()
            val activity = controller.get()
            val panes = (0..1).map {
                activity.supportFragmentManager.findFragmentByTag("share_pane_$it") as PaneFragment
            }
            panes.forEach {
                it.viewModel.io = kotlinx.coroutines.Dispatchers.Main.immediate
                it.viewModel.mountPinnedDirectory(XFile("file", dir.path, true), "Sort test")
            }
            shadowOf(Looper.getMainLooper()).idle()
            fun names(pane: PaneFragment) = pane.viewModel.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .filter { it.file.parentPath == dir.path }.map { it.file.name }
            assertEquals(listOf("a-old", "z-new", "a-large.txt", "z-small.txt"), names(panes[0]))
            val strip = activity.findViewById<android.view.View>(R.id.strip_right)
            strip.findViewById<android.view.View>(R.id.as_sort).performClick()
            val sort = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            sort.findViewById<android.widget.RadioGroup>(R.id.rg_by)!!.check(R.id.rb_size)
            sort.findViewById<android.widget.RadioGroup>(R.id.rg_folder)!!.check(R.id.rf_date_new)
            sort.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            for (pane in panes) {
                assertEquals(listOf("z-new", "a-old", "z-small.txt", "a-large.txt"), names(pane))
            }
        } finally {
            controller.pause().stop().destroy()
            dir.deleteRecursively()
        }
    }
}
