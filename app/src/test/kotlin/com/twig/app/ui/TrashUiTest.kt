package com.twig.app.ui

import android.app.Application
import android.os.Environment
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Prefs
import com.twig.app.TrashFileSystem
import com.twig.app.TrashOsShadow
import com.twig.app.TrashMovesShadow
import com.twig.app.TrashStore
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [TrashOsShadow::class, TrashMovesShadow::class])
class TrashUiTest {
    open class Host : AppCompatActivity() {
        override fun onCreate(state: android.os.Bundle?) { setTheme(R.style.Theme_Twig); super.onCreate(state) }
    }
    class DualPaneHost : Host(), PaneFragment.Host {
        val left = PaneFragment.newInstance(0)
        val right = PaneFragment.newInstance(1)
        override fun siblingOf(self: PaneFragment) = if (self === left) right else left
        override fun onAddServer(type: String) = Unit
        override fun onEditServer(conn: com.twig.app.SavedConnection) = Unit
        override fun onPaneTouched(self: PaneFragment) = Unit
        override fun onPaneSwipe(velocityX: Float) = Unit
        override fun refreshTrees() = Unit
        override fun isPaneActive(self: PaneFragment) = self === left
        override fun focusPane(pane: PaneFragment) = Unit
    }
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var root: File
    private lateinit var original: File
    @Before fun setup() {
        root = Environment.getExternalStorageDirectory().canonicalFile.apply { mkdirs() }
        original = File(root, "trash-ui-test.txt").apply { writeText("keep me") }
        LocalFileSystem.documentAccess = null
        Prefs.setTrashEnabled(app, true)
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(TrashFileSystem(app))
    }
    @After fun cleanup() {
        original.delete()
        File(root, TrashStore.DIRECTORY).deleteRecursively()
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("background operation did not finish", condition())
    }
    private fun executeConfirmedDeletion() {
        waitFor { Transfers.active != null }
        val session = Transfers.active ?: error("confirmed deletion must start a session")
        Thread { Transfers.execute(app, session) {} }.start()
    }
    private fun dialogAfter(previous: android.app.Dialog?): AlertDialog {
        waitFor { ShadowDialog.getLatestDialog() !== previous && ShadowDialog.getLatestDialog()?.isShowing == true }
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun file() = XFile("file", original.path, false, size = original.length())

    @Test fun `cancel preserves files and a mixed selection shows only its count`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val deleted = AtomicInteger()
        FsRegistry.register(object : com.twig.core.FileSystem by LocalFileSystem() {
            override val scheme = "trash-test-remote"
            override fun delete(file: XFile) { deleted.incrementAndGet() }
        })
        try {
            val before = ShadowDialog.getLatestDialog()
            DeleteUi.confirm(host.get(), host.get(), listOf(file())) { fail("cancel must not delete") }
            val cancel = dialogAfter(before)
            assertEquals(original.name, cancel.findViewById<TextView>(android.R.id.message)!!.text)
            assertEquals(app.getString(R.string.strip_delete), cancel.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text)
            cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals("keep me", original.readText())
            val finished = AtomicBoolean()
            DeleteUi.confirm(host.get(), host.get(), listOf(file(), file().copy(scheme = "trash-test-remote", path = "/remote/test.txt"))) {
                assertTrue(it); finished.set(true)
            }
            val confirm = dialogAfter(cancel)
            val message = confirm.findViewById<TextView>(android.R.id.message)!!.text.toString()
            assertEquals(app.getString(R.string.title_selected_count, 2), message)
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            executeConfirmedDeletion()
            waitFor { finished.get() }
            assertFalse(original.exists())
            assertEquals(1, deleted.get())
            val store = TrashStore(app)
            store.restore(store.snapshot().entries.single())
            assertEquals("keep me", original.readText())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `long press recycles eligible local files without confirmation`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val finished = AtomicBoolean()
        try {
            DeleteUi.confirm(host.get(), host.get(), listOf(file()), skipTrashConfirmation = true) {
                assertTrue(it); finished.set(true)
            }
            executeConfirmedDeletion()
            waitFor { finished.get() }
            assertFalse(original.exists())
            val store = TrashStore(app)
            store.restore(store.snapshot().entries.single())
            assertEquals("keep me", original.readText())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `long press still confirms disabled trash and mixed backend deletion`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        try {
            Prefs.setTrashEnabled(app, false)
            val before = ShadowDialog.getLatestDialog()
            DeleteUi.confirm(host.get(), host.get(), listOf(file()), skipTrashConfirmation = true) {
                fail("cancel must not delete")
            }
            val disabled = dialogAfter(before)
            disabled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            Prefs.setTrashEnabled(app, true)
            FsRegistry.register(object : com.twig.core.FileSystem by LocalFileSystem() {
                override val scheme = "trash-test-remote"
            })
            DeleteUi.confirm(host.get(), host.get(), listOf(file(), file().copy(scheme = "trash-test-remote")),
                skipTrashConfirmation = true) { fail("cancel must not delete") }
            val mixed = dialogAfter(disabled)
            assertTrue(mixed.findViewById<android.widget.CheckBox>(android.R.id.checkbox)!!.isChecked)
            mixed.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals("keep me", original.readText())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `a failed planned trash move never falls back to permanent deletion`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        var permanentDeletes = 0
        FsRegistry.register(object : com.twig.core.FileSystem by LocalFileSystem() {
            override fun delete(file: XFile) { permanentDeletes++; LocalFileSystem().delete(file) }
        })
        try {
            val previous = ShadowDialog.getLatestDialog()
            var success: Boolean? = null
            DeleteUi.confirm(host.get(), host.get(), listOf(file())) { success = it }
            val confirm = dialogAfter(previous)
            assertTrue(confirm.findViewById<android.widget.CheckBox>(android.R.id.checkbox)!!.isChecked)
            // A conflicting, unmarked trash folder appears after the decision was planned.
            File(root, TrashStore.DIRECTORY).mkdirs()
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            executeConfirmedDeletion()
            waitFor { success != null }
            assertEquals(false, success)
            assertEquals(0, permanentDeletes)
            assertEquals("keep me", original.readText())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `empty trash keeps entries created after the confirmation snapshot`() {
        val fs = FsRegistry.of("trash") as TrashFileSystem
        val old = fs.store.move(fs.store.plan(listOf(file())).single())
        val host = Robolectric.buildActivity(Host::class.java).setup()
        var refreshed = false
        try {
            val previous = ShadowDialog.getLatestDialog()
            TrashDialogs(host.get(), host.get()) { refreshed = true }.show(fs.root())
            val actions = dialogAfter(previous)
            actions.listView.performItemClick(actions.listView.adapter.getView(1, null, actions.listView), 1, 1)
            val confirm = dialogAfter(actions)
            original.writeText("new entry")
            val fresh = fs.store.move(fs.store.plan(listOf(file())).single())
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            executeConfirmedDeletion()
            waitFor { refreshed }
            assertFalse(old.directory.exists())
            assertTrue(fresh.directory.exists())
            fs.store.restore(fresh)
            assertEquals("new entry", original.readText())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `inline trash controls restore an entry without a second activity`() {
        val fs = FsRegistry.of("trash") as TrashFileSystem
        fs.store.move(fs.store.plan(listOf(file())).single())
        val deleted = fs.list(fs.root()).single()
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val refreshed = AtomicBoolean()
        try {
            val previous = ShadowDialog.getLatestDialog()
            TrashDialogs(host.get(), host.get()) { refreshed.set(true) }.show(deleted)
            val action = dialogAfter(previous)
            action.listView.performItemClick(action.listView.adapter.getView(0, null, action.listView), 0, 0)
            val restore = dialogAfter(action)
            assertNotNull(findEdit(restore.window!!.decorView))
            restore.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { refreshed.get() }
            assertEquals("keep me", original.readText())
            assertTrue(fs.store.snapshot().entries.isEmpty())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `unchecking trash keeps the compact confirmation and permanently deletes`() {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        try {
            val previous = ShadowDialog.getLatestDialog()
            val finished = AtomicBoolean()
            DeleteUi.confirm(host.get(), host.get(), listOf(file())) { assertTrue(it); finished.set(true) }
            val dialog = dialogAfter(previous)
            val checkbox = dialog.findViewById<android.widget.CheckBox>(android.R.id.checkbox)!!
            assertTrue(checkbox.isChecked)
            checkbox.performClick()
            assertEquals(original.name,
                dialog.findViewById<TextView>(android.R.id.message)!!.text)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            executeConfirmedDeletion()
            waitFor { finished.get() }
            assertFalse(original.exists())
            assertTrue(TrashStore(app).snapshot().entries.isEmpty())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `manual deletion uses the optional completion vibration`() {
        Prefs.setTrashEnabled(app, false)
        Prefs.setVibrateOnCompletion(app, true)
        val vibrator = app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        org.robolectric.shadows.ShadowVibrator.reset()
        shadowOf(vibrator).setHasVibrator(true)
        val host = Robolectric.buildActivity(Host::class.java).setup()
        try {
            val previous = ShadowDialog.getLatestDialog()
            val finished = AtomicBoolean()
            DeleteUi.confirm(host.get(), host.get(), listOf(file())) { finished.set(true) }
            dialogAfter(previous).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            executeConfirmedDeletion()
            waitFor { finished.get() }
            assertFalse(original.exists())
            assertEquals(80, shadowOf(vibrator).milliseconds)
        } finally {
            Prefs.setVibrateOnCompletion(app, false)
            host.pause().stop().destroy()
        }
    }

    @Test fun `globally disabling trash hides checkbox and keeps existing trash recoverable`() {
        val fs = FsRegistry.of("trash") as TrashFileSystem
        val entry = fs.store.move(fs.store.plan(listOf(file())).single())
        original.writeText("new")
        Prefs.setTrashEnabled(app, false)
        val host = Robolectric.buildActivity(Host::class.java).setup()
        try {
            val previous = ShadowDialog.getLatestDialog()
            val finished = AtomicBoolean()
            DeleteUi.confirm(host.get(), host.get(), listOf(file())) { assertTrue(it); finished.set(true) }
            val dialog = dialogAfter(previous)
            assertNull(dialog.findViewById<android.widget.CheckBox>(android.R.id.checkbox))
            assertEquals(original.name, dialog.findViewById<TextView>(android.R.id.message)!!.text)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            executeConfirmedDeletion()
            waitFor { finished.get() }
            assertEquals(1, fs.list(fs.root()).size)
            fs.store.restore(entry)
            assertEquals("keep me", original.readText())
        } finally { Prefs.setTrashEnabled(app, true); host.pause().stop().destroy() }
    }

    @Test fun `the main tree exposes one top-level trash entry with a theme-aware icon`() {
        val vm = PaneViewModel(app)
        vm.refreshTree()
        val entry = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.scheme == "trash" }
        assertEquals(0, entry.depth)
        assertTrue(entry.expandable)
        assertFalse(entry.file.canWrite)
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, thumbs = false)
        adapter.submitList(listOf(entry))
        val parent = android.widget.FrameLayout(android.view.ContextThemeWrapper(app, R.style.Theme_Twig))
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        assertEquals(app.getString(R.string.trash_title), holder.itemView.findViewById<TextView>(R.id.name).text)
        assertEquals(R.drawable.ic_trash,
            shadowOf(holder.itemView.findViewById<android.widget.ImageView>(R.id.icon).drawable).createdFromResId)
    }

    @Test fun `batch restore continues after a conflict without overwriting either file`() {
        val other = File(root, "trash-ui-other.txt").apply { writeText("second") }
        val fs = FsRegistry.of("trash") as TrashFileSystem
        listOf(file(), XFile("file", other.path, false)).forEach { source ->
            fs.store.move(fs.store.plan(listOf(source)).single())
        }
        val deleted = fs.list(fs.root())
        original.writeText("conflicting live file")
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val changed = AtomicBoolean()
        try {
            val before = ShadowDialog.getLatestDialog()
            TrashDialogs(host.get(), host.get()) { changed.set(true) }.restoreSelected(deleted)
            dialogAfter(before).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { changed.get() }
            assertEquals("conflicting live file", original.readText())
            assertEquals("second", other.readText())
            assertEquals(listOf(original.name), fs.list(fs.root()).map { it.name })
        } finally { host.pause().stop().destroy(); other.delete() }
    }

    @Test fun `batch purge uses confirmed snapshot and leaves new trash entries untouched`() {
        val other = File(root, "trash-ui-other.txt").apply { writeText("second") }
        val fs = FsRegistry.of("trash") as TrashFileSystem
        listOf(file(), XFile("file", other.path, false)).forEach { source ->
            fs.store.move(fs.store.plan(listOf(source)).single())
        }
        val selected = fs.list(fs.root())
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val changed = AtomicBoolean()
        try {
            val before = ShadowDialog.getLatestDialog()
            TrashDialogs(host.get(), host.get()) { changed.set(true) }.purgeSelected(selected)
            val confirm = dialogAfter(before)
            original.writeText("new deletion")
            fs.store.move(fs.store.plan(listOf(file())).single())
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            executeConfirmedDeletion()
            waitFor { changed.get() }
            assertEquals(1, fs.list(fs.root()).size)
            assertEquals("new deletion", fs.openInput(fs.list(fs.root()).single()).bufferedReader().use { it.readText() })
        } finally { host.pause().stop().destroy(); other.delete() }
    }

    @Test fun `emptying trash updates both panes including a collapsed sibling root`() {
        for (expandSibling in listOf(false, true)) {
            original.writeText("keep me")
            val fs = FsRegistry.of("trash") as TrashFileSystem
            fs.store.move(fs.store.plan(listOf(file())).single())
            val controller = Robolectric.buildActivity(DualPaneHost::class.java).setup()
            val host = controller.get()
            try {
                host.supportFragmentManager.beginTransaction()
                    .add(host.left, "left").add(host.right, "right").commitNow()
                fun trashRoot(pane: PaneFragment) = pane.viewModel.state.value.rows
                    .filterIsInstance<PaneViewModel.FileNode>().firstOrNull { it.file.scheme == "trash" && it.file.path == "/" }
                waitFor {
                    listOf(host.left, host.right).all {
                        org.robolectric.util.ReflectionHelpers.getField<Boolean>(it.viewModel, "trashPresenceLoaded") && trashRoot(it) != null
                    }
                }
                if (expandSibling) {
                    host.right.viewModel.toggle(trashRoot(host.right)!!)
                    waitFor { host.right.viewModel.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == original.name } }
                }
                val previous = ShadowDialog.getLatestDialog()
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(host.left, "longClickFile",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(PaneViewModel.FileNode::class.java, trashRoot(host.left)!!))
                val menu = dialogAfter(previous)
                val index = (0 until menu.listView.adapter.count).single {
                    menu.listView.adapter.getItem(it).toString() == app.getString(R.string.trash_empty)
                }
                menu.listView.performItemClick(menu.listView.adapter.getView(index, null, menu.listView), index, index.toLong())
                dialogAfter(menu).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                executeConfirmedDeletion()
                waitFor { trashRoot(host.left) == null && trashRoot(host.right) == null }
                assertTrue(fs.store.snapshot().entries.isEmpty())
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun `pane expands trash directories one level at a time and refreshes after recovery`() {
        val folder = File(root, "trash-ui-folder").apply { mkdirs() }
        File(folder, ".hidden.txt").writeText("hidden")
        File(folder, "nested").mkdir()
        File(folder, "nested/deep.txt").writeText("deep")
        val fs = FsRegistry.of("trash") as TrashFileSystem
        fs.store.move(fs.store.plan(listOf(XFile("file", folder.path, true))).single())
        val vm = PaneViewModel(app)
        vm.refreshTree()
        val rootNode = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.scheme == "trash" }
        vm.toggle(rootNode)
        waitFor { vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == folder.name } }
        val deleted = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.name == folder.name }
        vm.toggle(deleted)
        waitFor { vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == ".hidden.txt" } }
        assertFalse(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == "deep.txt" })
        val nested = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.name == "nested" }
        vm.toggle(nested)
        waitFor { vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == "deep.txt" } }
        val deep = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.name == "deep.txt" }
        assertEquals(3, deep.depth)
        val item = fs.item(deep.file)
        fs.store.restoreItem(item.entry, item.relative, "deep.txt", true)
        vm.refreshTrash()
        waitFor { vm.state.value.currentKey == rootNode.key && vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == folder.name } }
        assertEquals(rootNode.key, vm.state.value.currentKey)
        assertFalse(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == "deep.txt" })
        folder.deleteRecursively()
    }

    @Test fun `trash file and directory menus expose inspection and export and properties opens inline`() {
        val fs = FsRegistry.of("trash") as TrashFileSystem
        fs.store.move(fs.store.plan(listOf(file())).single())
        val folder = File(root, "trash-menu-folder").apply { mkdirs() }
        File(folder, "inside.txt").writeText("inside")
        fs.store.move(fs.store.plan(listOf(XFile("file", folder.path, true))).single())
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val fragment = PaneFragment()
        try {
            host.get().supportFragmentManager.beginTransaction().add(fragment, "trash-menu").commitNow()
            val vm = fragment.viewModel
            waitFor { vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.scheme == "trash" } }
            val rootNode = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.scheme == "trash" }
            vm.toggle(rootNode)
            waitFor { vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == original.name } }
            val fileNode = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.name == original.name }
            val longClick = PaneFragment::class.java.getDeclaredMethod("longClickFile", PaneViewModel.FileNode::class.java).apply { isAccessible = true }
            val previous = ShadowDialog.getLatestDialog()
            longClick.invoke(fragment, fileNode)
            val menu = dialogAfter(previous)
            val labels = (0 until menu.listView.adapter.count).map { menu.listView.adapter.getItem(it).toString() }
            listOf(R.string.trash_restore, R.string.file_info, R.string.strip_copy, R.string.strip_compress,
                R.string.open_with_external, R.string.action_share, R.string.trash_delete_permanently).forEach {
                assertTrue(app.getString(it), app.getString(it) in labels)
            }
            assertFalse(app.getString(R.string.strip_move) in labels)
            assertFalse(app.getString(R.string.strip_rename) in labels)
            val index = labels.indexOf(app.getString(R.string.file_info))
            menu.listView.performItemClick(menu.listView.adapter.getView(index, null, menu.listView), index, index.toLong())
            waitFor { vm.state.value.rows.filterIsInstance<PaneViewModel.InfoNode>().any { it.details?.sections?.isNotEmpty() == true } }
            val info = vm.state.value.rows.filterIsInstance<PaneViewModel.InfoNode>().single()
            assertEquals(original.path, info.details!!.sections.first().rows.toMap()[app.getString(R.string.trash_original_path)])
            val dirNode = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.name == folder.name }
            longClick.invoke(fragment, dirNode)
            val dirMenu = dialogAfter(menu)
            val dirLabels = (0 until dirMenu.listView.adapter.count).map { dirMenu.listView.adapter.getItem(it).toString() }
            listOf(R.string.file_info, R.string.action_refresh, R.string.strip_search, R.string.strip_map,
                R.string.strip_copy, R.string.strip_compress).forEach { assertTrue(app.getString(it) in dirLabels) }
            assertFalse(app.getString(R.string.open_with_external) in dirLabels)
            dirMenu.dismiss()
        } finally { host.pause().stop().destroy(); folder.deleteRecursively() }
    }

    @Test fun `disabling trash removes its expanded tree and enabling it again preserves recovery`() {
        val fs = FsRegistry.of("trash") as TrashFileSystem
        fs.store.move(fs.store.plan(listOf(file())).single())
        val vm = PaneViewModel(app)
        vm.refreshTree()
        fun rootNode() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.scheme == "trash" && it.file.path == "/" }
        vm.toggle(rootNode())
        waitFor { vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == original.name } }
        val signature = Prefs.uiSignature(app)
        Prefs.setTrashEnabled(app, false)
        assertNotEquals(signature, Prefs.uiSignature(app))
        vm.resortAll() // Same retained VM path used when MainActivity recreates after settings.
        assertFalse(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.scheme == "trash" })
        assertNull(vm.state.value.currentDir)
        assertNull(vm.state.value.currentKey)
        Prefs.setTrashEnabled(app, true)
        vm.resortAll()
        assertFalse(rootNode().expanded)
        vm.toggle(rootNode())
        waitFor { vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.name == original.name } }
        fs.store.restore(fs.store.snapshot().entries.single())
        assertEquals("keep me", original.readText())
    }

    @Test fun `trash rows show only deletion date while metadata retains original modification time`() {
        val modified = 1_600_000_000_000L
        assertTrue(original.setLastModified(modified))
        val fs = FsRegistry.of("trash") as TrashFileSystem
        val entry = fs.store.move(fs.store.plan(listOf(file())).single())
        val deleted = fs.list(fs.root()).single()
        assertEquals(modified, deleted.lastModified)
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, thumbs = false)
        val parent = android.widget.FrameLayout(android.view.ContextThemeWrapper(app, R.style.Theme_Twig))
        val node = PaneViewModel.FileNode(deleted, 1, false, false)
        adapter.submitList(listOf(node))
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        assertEquals(com.twig.app.Format.time(entry.deletedAt, app), holder.itemView.findViewById<TextView>(R.id.meta).text.toString())
        assertNotEquals(com.twig.app.Format.time(modified, app), holder.itemView.findViewById<TextView>(R.id.meta).text.toString())
    }

    private fun findEdit(view: android.view.View): EditText? {
        if (view is EditText) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findEdit(view.getChildAt(i))?.let { return it }
        return null
    }
}
