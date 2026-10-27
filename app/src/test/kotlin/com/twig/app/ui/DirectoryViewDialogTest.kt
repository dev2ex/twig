package com.twig.app.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.SavedConnection
import com.twig.app.formatLocationPath
import com.twig.app.DirectoryView
import com.twig.app.DirectoryViewStore
import com.twig.app.FolderSortKey
import com.twig.app.FileSortKey
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.SortSpec
import com.twig.core.XFile
import com.twig.core.FsRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectoryViewDialogTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dir = XFile("file", "/storage/emulated/0/Pictures", isDir = true)

    @Before
    fun setup() {
        for (name in listOf("twig_sort", "twig_prefs", DirectoryViewStore.FILE)) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun withDialog(directory: XFile? = dir, viewOnly: Boolean = false, run: (AlertDialog) -> Unit) {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            if (viewOnly) SortDialog.showView(controller.get(), directory) {}
            else SortDialog.show(controller.get(), directory) {}
            run(ShadowDialog.getLatestDialog() as AlertDialog)
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `scope drafts stay independent and cancel writes nothing`() {
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_by)!!.check(R.id.rb_size)
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertFalse(dialog.findViewById<RadioButton>(R.id.rb_date)!!.isEnabled)
            assertEquals(View.GONE, dialog.findViewById<View>(R.id.view_options)!!.visibility)
            dialog.findViewById<CheckBox>(R.id.cb_custom_sort)!!.isChecked = true
            dialog.findViewById<RadioGroup>(R.id.rg_by)!!.check(R.id.rb_date)
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_default)
            assertEquals(R.id.rb_size, dialog.findViewById<RadioGroup>(R.id.rg_by)!!.checkedRadioButtonId)
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertEquals(R.id.rb_date, dialog.findViewById<RadioGroup>(R.id.rg_by)!!.checkedRadioButtonId)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        assertEquals(FileSortKey.NAME, SortSpec.load(app).by)
        assertEquals(0, Prefs.thumbsGrid(app))
        assertTrue(DirectoryViewStore.load(app).isEmpty())
    }

    @Test
    fun `saving and resetting directory view preserve its sorting and default view`() {
        val sort = SortSpec(reversed = true)
        DirectoryViewStore.save(app, dir, DirectoryView(sort = sort))
        withDialog(viewOnly = true) { dialog ->
            assertEquals(View.GONE, dialog.findViewById<View>(R.id.sort_options)!!.visibility)
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            dialog.findViewById<CheckBox>(R.id.cb_custom_view)!!.isChecked = true
            dialog.findViewById<RadioGroup>(R.id.rg_view)!!.check(R.id.view_all)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals(DirectoryView(sort, 2), DirectoryViewStore.get(DirectoryViewStore.load(app), dir))
        assertEquals(0, Prefs.thumbsGrid(app))
        withDialog(viewOnly = true) { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertEquals(R.id.view_all, dialog.findViewById<RadioGroup>(R.id.rg_view)!!.checkedRadioButtonId)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals(DirectoryView(sort = sort), DirectoryViewStore.get(DirectoryViewStore.load(app), dir))
    }

    @Test
    fun `saving and resetting directory sorting preserve its view`() {
        DirectoryViewStore.save(app, dir, DirectoryView(gridMode = 2))
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            dialog.findViewById<CheckBox>(R.id.cb_custom_sort)!!.isChecked = true
            dialog.findViewById<RadioGroup>(R.id.rg_by)!!.check(R.id.rb_size)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals(DirectoryView(SortSpec(by = FileSortKey.SIZE), 2),
            DirectoryViewStore.get(DirectoryViewStore.load(app), dir))
        assertEquals(FileSortKey.NAME, SortSpec.load(app).by)
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals(DirectoryView(gridMode = 2), DirectoryViewStore.get(DirectoryViewStore.load(app), dir))
    }

    @Test
    fun `recursive controls stay independent and reset clears only the selected recursion`() {
        withDialog { dialog ->
            assertEquals(View.GONE, dialog.findViewById<CheckBox>(R.id.cb_recursive)!!.visibility)
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            val recursive = dialog.findViewById<CheckBox>(R.id.cb_recursive)!!
            assertFalse(recursive.isChecked)
            assertFalse(recursive.isEnabled)
            dialog.findViewById<CheckBox>(R.id.cb_custom_sort)!!.isChecked = true
            assertTrue(recursive.isEnabled)
            recursive.isChecked = true
            dialog.findViewById<RadioGroup>(R.id.rg_by)!!.check(R.id.rb_size)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        val sorting = DirectoryViewStore.get(DirectoryViewStore.load(app), dir)!!
        assertTrue(sorting.sortRecursive)
        assertFalse(sorting.viewRecursive)
        withDialog(viewOnly = true) { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertFalse(dialog.findViewById<CheckBox>(R.id.cb_recursive)!!.isChecked)
            dialog.findViewById<CheckBox>(R.id.cb_custom_view)!!.isChecked = true
            dialog.findViewById<RadioGroup>(R.id.rg_view)!!.check(R.id.view_all)
            dialog.findViewById<CheckBox>(R.id.cb_recursive)!!.isChecked = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        val both = DirectoryViewStore.get(DirectoryViewStore.load(app), dir)!!
        assertTrue(both.sortRecursive)
        assertTrue(both.viewRecursive)
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertTrue(dialog.findViewById<CheckBox>(R.id.cb_recursive)!!.isChecked)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        val reset = DirectoryViewStore.get(DirectoryViewStore.load(app), dir)!!
        assertNull(reset.sort)
        assertFalse(reset.sortRecursive)
        assertEquals(2, reset.gridMode)
        assertTrue(reset.viewRecursive)
    }

    @Test
    fun `nonrecursive parent settings fall back to defaults in child dialogs`() {
        val parent = dir.copy(path = dir.parentPath)
        DirectoryViewStore.save(app, parent, DirectoryView(SortSpec(by = FileSortKey.SIZE), 2))
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertEquals(R.id.rb_name, dialog.findViewById<RadioGroup>(R.id.rg_by)!!.checkedRadioButtonId)
            dialog.findViewById<CheckBox>(R.id.cb_custom_sort)!!.isChecked = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals(SortSpec(), DirectoryViewStore.get(DirectoryViewStore.load(app), dir)?.sort)
        withDialog(viewOnly = true) { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertEquals(R.id.view_list, dialog.findViewById<RadioGroup>(R.id.rg_view)!!.checkedRadioButtonId)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertNull(DirectoryViewStore.get(DirectoryViewStore.load(app), dir)?.gridMode)
    }

    @Test
    fun `reset skips nonrecursive parent settings and shows recursive ancestor settings`() {
        val parent = dir.copy(path = dir.parentPath)
        val ancestor = parent.copy(path = parent.parentPath)
        DirectoryViewStore.save(app, ancestor, DirectoryView(SortSpec(by = FileSortKey.DATE), 1,
            sortRecursive = true, viewRecursive = true))
        DirectoryViewStore.save(app, parent, DirectoryView(SortSpec(by = FileSortKey.SIZE), 2))
        DirectoryViewStore.save(app, dir, DirectoryView(SortSpec(), 0))
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertEquals(R.id.rb_date, dialog.findViewById<RadioGroup>(R.id.rg_by)!!.checkedRadioButtonId)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        withDialog(viewOnly = true) { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertEquals(R.id.view_media, dialog.findViewById<RadioGroup>(R.id.rg_view)!!.checkedRadioButtonId)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertNull(DirectoryViewStore.get(DirectoryViewStore.load(app), dir))
    }

    @Test
    fun `inherited settings are shown without becoming explicit overrides`() {
        val parent = dir.copy(path = dir.parentPath)
        DirectoryViewStore.save(app, parent, DirectoryView(SortSpec(by = FileSortKey.SIZE), 2,
            sortRecursive = true, viewRecursive = true))
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertFalse(dialog.findViewById<CheckBox>(R.id.cb_custom_sort)!!.isChecked)
            assertEquals(R.id.rb_size, dialog.findViewById<RadioGroup>(R.id.rg_by)!!.checkedRadioButtonId)
            assertFalse(dialog.findViewById<RadioButton>(R.id.rb_size)!!.isEnabled)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        withDialog(viewOnly = true) { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertFalse(dialog.findViewById<CheckBox>(R.id.cb_custom_view)!!.isChecked)
            assertEquals(R.id.view_all, dialog.findViewById<RadioGroup>(R.id.rg_view)!!.checkedRadioButtonId)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertNull(DirectoryViewStore.get(DirectoryViewStore.load(app), dir))
        assertEquals(1, DirectoryViewStore.load(app).size)
    }

    @Test
    fun `selected directory shows the history path with server alias instead of the internal scheme`() {
        val conn = SavedConnection("sftp", "directory-display.test", port = 2222, name = "My server")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(FakeFileSystem(scheme, mapOf("/" to emptyList())))
        Connections.ensure(app, conn)
        val remote = XFile(scheme, "/photos", isDir = true)
        try {
            withDialog(remote) { dialog ->
                dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
                val displayed = dialog.findViewById<TextView>(R.id.directory_path)!!.text.toString()
                assertEquals("sftp://My server/photos", displayed)
                assertEquals(formatLocationPath(conn.label(), remote.path, conn), displayed)
            }
        } finally {
            Connections.drop(scheme)
        }
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertEquals(dir.path, dialog.findViewById<TextView>(R.id.directory_path)!!.text.toString())
        }
    }

    @Test
    fun `reverse folder names save and restore for default and recursive directory sorting`() {
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_folder)!!.check(R.id.rf_name_reversed)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals(FolderSortKey.NAME_REVERSED, SortSpec.load(app).folderBy)
        withDialog { dialog ->
            assertEquals(R.id.rf_name_reversed, dialog.findViewById<RadioGroup>(R.id.rg_folder)!!.checkedRadioButtonId)
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            dialog.findViewById<CheckBox>(R.id.cb_custom_sort)!!.isChecked = true
            dialog.findViewById<RadioGroup>(R.id.rg_folder)!!.check(R.id.rf_name_reversed)
            dialog.findViewById<CheckBox>(R.id.cb_recursive)!!.isChecked = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        val views = DirectoryViewStore.load(app)
        val custom = DirectoryViewStore.get(views, dir)!!
        assertEquals(FolderSortKey.NAME_REVERSED, custom.sort?.folderBy)
        assertTrue(custom.sortRecursive)
        assertEquals(FolderSortKey.NAME_REVERSED,
            DirectoryViewStore.resolve(views, dir.copy(path = "${dir.path}/child")).sort?.folderBy)
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertEquals(R.id.rf_name_reversed, dialog.findViewById<RadioGroup>(R.id.rg_folder)!!.checkedRadioButtonId)
        }
    }

    @Test
    fun `directory scope is unavailable without a selected directory`() {
        withDialog(null) { dialog ->
            assertFalse(dialog.findViewById<RadioButton>(R.id.scope_directory)!!.isEnabled)
            assertEquals(R.id.scope_default, dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.checkedRadioButtonId)
        }
    }
}
