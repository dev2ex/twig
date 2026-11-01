package com.twig.app.ui

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.StreamProvider
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PickerBrowserTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        org.robolectric.util.ReflectionHelpers.setStaticField(
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        Prefs.setRememberLocation(app, false)
    }

    private fun pane(activity: PickerActivity, index: Int) = activity.supportFragmentManager
        .findFragmentByTag(if (index == 0) "picker_pane" else "picker_pane_$index") as PaneFragment

    @Test fun `external multiple selection enables immediate row selection in both panes after recreation`() {
        val intent = Intent(app, PickerActivity::class.java).setAction(Intent.ACTION_GET_CONTENT)
            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val controller = Robolectric.buildActivity(PickerActivity::class.java, intent).setup()
        try {
            repeat(2) {
                val activity = controller.get()
                assertTrue(activity.selectFilesByRow)
                for (index in 0..1) {
                    val fragment = pane(activity, index)
                    val adapter = org.robolectric.util.ReflectionHelpers.getField<FileAdapter>(fragment, "adapter")
                    assertTrue(org.robolectric.util.ReflectionHelpers.getField(adapter, "selectFilesByRow"))
                }
                if (it == 0) controller.recreate()
            }
        } finally { controller.pause().stop().destroy() }
        for (otherIntent in listOf(PickerActivity.intent(app).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            Intent(intent).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false), PickerActivity.dirIntent(app, null))) {
            val other = Robolectric.buildActivity(PickerActivity::class.java, otherIntent).setup()
            try { assertFalse(other.get().selectFilesByRow) }
            finally { other.pause().stop().destroy() }
        }
    }

    @Test fun `file picker and copy destination expose identical working strip actions`() {
        val picker = Robolectric.buildActivity(PickerActivity::class.java, PickerActivity.intent(app)).setup()
        val target = Robolectric.buildActivity(ShareTargetActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "text")).setup()
        try {
            val actions = listOf(R.id.as_up, R.id.as_refresh, R.id.as_sort, R.id.as_search, R.id.as_history,
                R.id.as_new_folder, R.id.as_compress, R.id.as_share, R.id.as_copy, R.id.as_move,
                R.id.as_rename, R.id.as_delete, R.id.as_compare, R.id.as_map)
            for (stripId in listOf(R.id.strip_left, R.id.strip_mid, R.id.strip_right)) {
                val source = picker.get().findViewById<View>(stripId)
                val dest = target.get().findViewById<View>(stripId)
                for (id in actions) {
                    val button = source.findViewById<View>(id)
                    assertEquals(dest.findViewById<View>(id).visibility, button.visibility)
                    if (button.visibility == View.VISIBLE) assertTrue(button.hasOnClickListeners())
                }
            }
            assertEquals(View.VISIBLE, picker.get().findViewById<View>(R.id.strip_right)
                .findViewById<View>(R.id.as_new_folder).visibility)
            assertSame(pane(picker.get(), 1), picker.get().siblingOf(pane(picker.get(), 0)))
            assertEquals(View.GONE, picker.get().findViewById<View>(R.id.btn_confirm).visibility)
        } finally { picker.pause().stop().destroy(); target.pause().stop().destroy() }
    }

    @Test fun `toolbar swipe and recreation preserve independent panes and active side`() {
        Prefs.setRootItemVisible(app, 0, "private", false)
        Prefs.setRootItemVisible(app, 1, "private", true)
        val controller = Robolectric.buildActivity(PickerActivity::class.java, PickerActivity.intent(app)).setup()
        try {
            var activity = controller.get()
            val left = pane(activity, 0)
            val right = pane(activity, 1)
            fun hasPrivate(p: PaneFragment) = p.viewModel.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .any { it.label == activity.getString(R.string.private_space) }
            assertFalse(hasPrivate(left))
            assertTrue(hasPrivate(right))
            val dir = XFile("file", app.cacheDir.path, true)
            left.viewModel.currentDir = dir
            left.viewModel.refreshTree()
            activity.findViewById<Toolbar>(R.id.toolbar).menu.getItem(0).actionView!!.performClick()
            assertTrue(activity.isPaneActive(right))
            activity.onPaneSwipe(1f)
            assertTrue(activity.isPaneActive(left))
            assertEquals(dir, left.viewModel.currentDir)
            activity.onPaneSwipe(-1f)
            val rightVm = right.viewModel
            controller.recreate()
            activity = controller.get()
            assertTrue(activity.isPaneActive(pane(activity, 1)))
            assertSame(rightVm, pane(activity, 1).viewModel)
        } finally {
            controller.pause().stop().destroy()
            Prefs.setRootItemVisible(app, 1, "private", false)
        }
    }

    @Test fun `measured orientation changes retain panes and landscape touch chooses active side`() {
        val controller = Robolectric.buildActivity(PickerActivity::class.java, PickerActivity.intent(app)).setup()
        try {
            val activity = controller.get()
            val panes = (0..1).map { pane(activity, it) }
            repeat(12) { turn ->
                val land = turn % 2 == 0
                controller.configurationChange(Configuration(activity.resources.configuration).apply {
                    orientation = if (land) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp = if (land) 800 else 400
                    screenHeightDp = if (land) 400 else 800
                })
                val root = activity.findViewById<Toolbar>(R.id.toolbar).parent as View
                val width = if (land) 800 else 400
                val height = if (land) 400 else 800
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val strip = activity.findViewById<View>(if (land) R.id.strip_mid else R.id.strip_right)
                assertEquals(View.VISIBLE, strip.visibility)
                assertEquals(if (land) 2 else 1, strip.findViewById<StripGrid>(R.id.as_grid).columnCount)
                if (land) {
                    activity.onPaneTouched(panes[1])
                    assertTrue(activity.isPaneActive(panes[1]))
                    activity.onPaneTouched(panes[0])
                    assertTrue(activity.isPaneActive(panes[0]))
                }
                for (i in 0..1) assertSame(panes[i], pane(activity, i))
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `directory confirmation follows only the active pane after switching`() {
        val controller = Robolectric.buildActivity(PickerActivity::class.java, PickerActivity.dirIntent(app, null)).setup()
        try {
            val activity = controller.get()
            val left = pane(activity, 0)
            val right = pane(activity, 1)
            val confirm = activity.findViewById<View>(R.id.btn_confirm)
            val dir = XFile("file", app.cacheDir.path, true)
            left.viewModel.currentDir = dir
            left.viewModel.refreshTree()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(confirm.isEnabled)
            activity.focusPane(right)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(confirm.isEnabled)
            left.viewModel.refreshTree()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(confirm.isEnabled)
            activity.focusPane(left)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(confirm.isEnabled)
            confirm.performClick()
            assertEquals(dir.path, shadowOf(activity).resultIntent.getStringExtra(PickerActivity.EXTRA_PICKED_PATH))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `locked picker keeps both panes on its requested source`() {
        val controller = Robolectric.buildActivity(PickerActivity::class.java,
            PickerActivity.pathIntent(app, null, "file", "/", "Local files")).setup()
        try {
            val activity = controller.get()
            for (index in 0..1) {
                val current = pane(activity, index)
                assertEquals(index, current.paneIndex)
                assertEquals("file", current.viewModel.lockRoot?.scheme)
                activity.focusPane(current)
                assertTrue(activity.isPaneActive(current))
                assertTrue(activity.onPickFile(XFile("sftp-unrelated", "/other.txt", false, 12)))
                assertFalse(activity.isFinishing)
                assertNull(shadowOf(activity).resultIntent)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `source directory picker accepts read only directories while destinations require writing`() {
        for (requireWritable in listOf(true, false)) {
            val controller = Robolectric.buildActivity(PickerActivity::class.java,
                PickerActivity.dirIntent(app, null, requireWritable = requireWritable)).setup()
            try {
                val activity = controller.get()
                val left = pane(activity, 0)
                val right = pane(activity, 1)
                val confirm = activity.findViewById<View>(R.id.btn_confirm)
                val dir = XFile("file", app.cacheDir.path, true, canRead = true, canWrite = false)
                fun select(file: XFile?) {
                    left.viewModel.currentDir = file
                    left.viewModel.refreshTree()
                    shadowOf(Looper.getMainLooper()).idle()
                }
                select(dir)
                assertEquals(!requireWritable, confirm.isEnabled)
                if (!requireWritable) {
                    select(dir.copy(canRead = false))
                    assertFalse(confirm.isEnabled)
                    select(dir.copy(isDir = false))
                    assertFalse(confirm.isEnabled)
                    select(null)
                    assertFalse(confirm.isEnabled)
                    select(dir)
                    activity.focusPane(right)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(confirm.isEnabled)
                    activity.focusPane(left)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertTrue(confirm.isEnabled)
                    confirm.performClick()
                    assertEquals(dir.path, shadowOf(activity).resultIntent.getStringExtra(PickerActivity.EXTRA_PICKED_PATH))
                }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun `shortcut confirmation observes the active pane after repeated switches`() {
        val controller = Robolectric.buildActivity(CreateCommandShortcutActivity::class.java).setup()
        try {
            val activity = controller.get()
            val left = pane(activity, 0)
            val right = pane(activity, 1)
            val confirm = activity.findViewById<View>(R.id.btn_confirm)
            val local = left.viewModel.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .first { it.file.scheme == "file" && it.file.path == "/" }
            activity.onPickNode(local)
            assertTrue(confirm.isEnabled)
            activity.focusPane(right)
            val group = right.viewModel.state.value.rows.filterIsInstance<PaneViewModel.GroupNode>().first()
            activity.onPickNode(group)
            assertFalse(confirm.isEnabled)
            repeat(3) {
                activity.focusPane(left)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(confirm.isEnabled)
                activity.focusPane(right)
                left.viewModel.refreshTree()
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(confirm.isEnabled)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `selecting a file on the right returns the original readable URI`() {
        val controller = Robolectric.buildActivity(PickerActivity::class.java, PickerActivity.intent(app)).setup()
        try {
            val activity = controller.get()
            activity.focusPane(pane(activity, 1))
            val local = File(app.cacheDir, "right-picked.txt").apply { writeText("selected") }
            val file = XFile("file", local.path, false, local.length())
            assertTrue(activity.onPickFile(file))
            assertEquals(StreamProvider.uriFor(activity, file), shadowOf(activity).resultIntent.data)
        } finally { controller.pause().stop().destroy() }
    }
}
