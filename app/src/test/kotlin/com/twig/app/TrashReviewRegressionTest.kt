package com.twig.app

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ui.CompareOptions
import com.twig.app.ui.FileAdapter
import com.twig.app.ui.PaneViewModel
import com.twig.app.ui.buildSyncPlan
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class, shadows = [TrashOsShadow::class, TrashMovesShadow::class])
class TrashReviewRegressionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var root: File
    private lateinit var store: TrashStore
    private lateinit var fs: TrashFileSystem
    private var oldRoute: ((String) -> com.twig.core.FileSystem?)? = null

    @Before fun setup() {
        root = Files.createTempDirectory("twig-trash-regression-").toFile().canonicalFile
        oldRoute = LocalFileSystem.documentAccess
        LocalFileSystem.documentAccess = null
        Prefs.setTrashEnabled(app, true)
        store = TrashStore(app, { listOf(root) })
        fs = TrashFileSystem(app, store)
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(fs)
    }
    @After fun cleanup() {
        LocalFileSystem.documentAccess = oldRoute
        root.deleteRecursively()
    }
    private fun xf(file: File) = XFile("file", file.path, file.isDirectory, file.length(), file.lastModified())
    private fun trash(file: File, target: TrashStore = store) = target.move(target.plan(listOf(xf(file))).single())
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("background operation did not finish", condition())
    }
    private fun expandedPane(): PaneViewModel = PaneViewModel(app).also { vm ->
        vm.refreshTree()
        vm.toggle(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().single { it.file.scheme == "trash" })
        waitFor { vm.state.value.rows.any { it.depth == 1 } }
    }

    @Test fun `repeated mirror scans exclude recovery stores on both sides while retaining ordinary work`() = runBlocking {
        val source = File(root, "source").apply { mkdir() }
        val destination = File(root, "destination").apply { mkdir() }
        val sourceStore = TrashStore(app, { listOf(source) })
        val destinationStore = TrashStore(app, { listOf(destination) })
        trash(File(source, "source-deleted.txt").apply { writeText("source recovery") }, sourceStore)
        val previous = trash(File(destination, "previous.txt").apply { writeText("previous recovery") }, destinationStore)
        File(source, "live.txt").writeText("copy me")
        File(destination, "extra.txt").writeText("recycle me")
        val options = CompareOptions(incrementalSync = false)
        val plan = buildSyncPlan(xf(source), xf(destination), options, 0)
        assertEquals(listOf("live.txt"), plan.copies.map { it.key })
        assertEquals(listOf("extra.txt"), plan.deletes.map { it.key })
        plan.deletes.forEach { destinationStore.move(destinationStore.plan(listOf(it.file)).single()) }
        val repeated = buildSyncPlan(xf(source), xf(destination), options, 0)
        assertTrue(repeated.deletes.isEmpty())
        assertEquals(listOf("live.txt"), repeated.copies.map { it.key })
        assertEquals(2, destinationStore.snapshot().entries.size)
        destinationStore.restore(previous)
        assertEquals("previous recovery", File(destination, "previous.txt").readText())
        assertEquals(1, sourceStore.snapshot().entries.size)
    }

    @Test fun `an ordinary same-named directory is still compared and newer trash formats stay protected`() = runBlocking {
        val source = File(root, "source").apply { mkdir() }
        val destination = File(root, "destination").apply { mkdir() }
        val ordinary = File(destination, TrashStore.DIRECTORY).apply { mkdir() }
        File(ordinary, "user.txt").writeText("ordinary content")
        val options = CompareOptions(incrementalSync = false)
        assertEquals(listOf(TrashStore.DIRECTORY), buildSyncPlan(xf(source), xf(destination), options, 0).deletes.map { it.key })
        File(ordinary, "format.json").writeText("{\"version\":2}")
        assertTrue(buildSyncPlan(xf(source), xf(destination), options, 0).deletes.isEmpty())
    }

    @Test fun `empty trash hides and new deletions reappear even when the root was hidden`() {
        assertTrue(fs.list(fs.root()).isEmpty())
        assertEquals(0, CopyEngine.plan(listOf(fs.root())).files)
        val vm = PaneViewModel(app)
        vm.refreshTree()
        fun visible() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().any { it.file.scheme == "trash" }
        waitFor { !visible() }
        val entry = trash(File(root, "new.txt").apply { writeText("recover me") })
        vm.refresh()
        waitFor { visible() }
        store.restore(entry)
        vm.refreshTrash()
        waitFor { !visible() }
        assertEquals("recover me", File(root, "new.txt").readText())
    }

    @Test fun `repairing damaged trash updates notices even when both child listings are empty`() {
        val entry = trash(File(root, "deleted.txt").apply { writeText("data") })
        store.purge(entry)
        val unexpected = File(root, "${TrashStore.DIRECTORY}/unexpected").apply { writeText("damaged") }
        assertTrue(fs.list(fs.root()).isEmpty())
        assertEquals(1, fs.notices.size)
        val vm = expandedPane()
        assertEquals(app.getString(R.string.trash_invalid, unexpected.path),
            vm.state.value.rows.filterIsInstance<PaneViewModel.ActionNode>().single { it.informational }.label)
        assertTrue(unexpected.delete())
        vm.refreshOnResume()
        waitFor { vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().none { it.file.scheme == "trash" } }
        assertTrue(fs.notices.isEmpty())
    }

    @Test fun `file and folder date orders follow deletion dates while copy preserves modification time`() {
        for ((name, modified, deleted) in listOf(
            Triple("a.txt", 1_600_000_000_000L, 1_800_000_000_000L),
            Triple("b.txt", 1_700_000_000_000L, 1_750_000_000_000L),
            Triple("a-dir", 1_600_000_000_000L, 1_800_000_000_000L),
            Triple("b-dir", 1_700_000_000_000L, 1_750_000_000_000L),
        )) {
            val original = File(root, name)
            if (name.endsWith("-dir")) original.mkdir() else original.writeText(name)
            assertTrue(original.setLastModified(modified))
            val entry = trash(original)
            File(entry.directory, "info.json").writeText(JSONObject().put("original", entry.original).put("deletedAt", deleted).toString())
        }
        val vm = expandedPane()
        fun rows() = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().filter { it.file.scheme == "trash" && it.depth > 0 }.map { it.file }
        vm.setSort(SortSpec(by = FileSortKey.DATE, folderBy = FolderSortKey.DATE_OLD))
        assertEquals(listOf("b-dir", "a-dir", "b.txt", "a.txt"), rows().map { it.name })
        vm.setSort(SortSpec(by = FileSortKey.DATE, reversed = true, folderBy = FolderSortKey.DATE_NEW))
        assertEquals(listOf("a-dir", "b-dir", "a.txt", "b.txt"), rows().map { it.name })
        val a = rows().single { it.name == "a.txt" }
        assertEquals(1_600_000_000_000L, a.lastModified)
        val export = File(root, "export").apply { mkdir() }
        CopyEngine.transfer(listOf(a), xf(export), move = false)
        assertEquals("a.txt", File(export, "a.txt").readText())
        assertEquals(1_600_000_000_000L, File(export, "a.txt").lastModified())
    }
}
