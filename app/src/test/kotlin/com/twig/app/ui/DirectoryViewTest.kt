package com.twig.app.ui

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.twig.app.*
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectoryViewTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: PaneViewModel
    private lateinit var dir: XFile
    private val reverse = SortSpec(reversed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        for (name in listOf("twig_sort", "twig_prefs", DirectoryViewStore.FILE)) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val conn = SavedConnection(type = "ftp", host = "directory-views.test", user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(FakeFileSystem(scheme,
            dirs = mapOf("/" to listOf("photos", "z.txt", "a.txt"),
                "/photos" to listOf("child", "z.txt", "a.txt"),
                "/photos/child" to listOf("z.txt", "a.txt")),
            files = listOf("/z.txt", "/a.txt", "/photos/z.txt", "/photos/a.txt",
                "/photos/child/z.txt", "/photos/child/a.txt").associateWith { "x" }))
        dir = XFile(scheme, "/photos", isDir = true)
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    private fun files(path: String) = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
        .filter { !it.file.isDir && it.file.scheme == dir.scheme && it.file.parentPath == path }

    @Test
    fun `parent and child keep independent sort and actual rendered view`() = runTest(dispatcher) {
        DirectoryViewStore.save(app, dir, DirectoryView(reverse, 2))
        vm.refreshSortAndView()
        vm.revealPath(dir.copy(path = "/photos/child"))
        advanceUntilIdle()
        assertEquals(listOf("z.txt", "a.txt"), files("/photos").map { it.file.name })
        assertEquals(listOf("a.txt", "z.txt"), files("/photos/child").map { it.file.name })
        val parent = files("/photos").first()
        val child = files("/photos/child").first()
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(listOf(parent, child))
        assertTrue(adapter.isCellAt(0))
        assertFalse(adapter.isCellAt(1))
        val container = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))
        val cell = adapter.createViewHolder(container, adapter.getItemViewType(0))
        val row = adapter.createViewHolder(container, adapter.getItemViewType(1))
        adapter.bindViewHolder(cell, 0)
        adapter.bindViewHolder(row, 1)
        assertNotEquals(cell.itemView.javaClass, row.itemView.javaClass)
    }

    @Test
    fun `sort-only override follows changed default view and reset removes the record`() = runTest(dispatcher) {
        DirectoryViewStore.save(app, dir, DirectoryView(sort = reverse))
        vm.refreshSortAndView()
        vm.revealPath(dir)
        advanceUntilIdle()
        assertEquals(listOf("z.txt", "a.txt"), files("/photos").map { it.file.name })
        assertTrue(files("/photos").all { it.gridMode == 0 })
        Prefs.setThumbsGrid(app, 2)
        vm.refreshSortAndView()
        assertTrue(files("/photos").all { it.gridMode == 2 })
        DirectoryViewStore.save(app, dir, DirectoryView())
        vm.refreshSortAndView()
        assertEquals(listOf("a.txt", "z.txt"), files("/photos").map { it.file.name })
        assertTrue(app.getSharedPreferences(DirectoryViewStore.FILE, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun `server shortcut and favorite use the same underlying directory settings`() = runTest(dispatcher) {
        val root = dir.copy(path = "/")
        DirectoryViewStore.save(app, root, DirectoryView(reverse, 2))
        vm.refreshSortAndView()
        vm.revealPath(root)
        advanceUntilIdle()
        assertEquals(listOf("z.txt", "a.txt"), files("/").map { it.file.name })
        assertTrue(files("/").all { it.gridMode == 2 })
        val conn = ConnectionStore.all(app).first { Connections.schemeOf(it) == dir.scheme }
        val fav = Favorite(label = "photos", kind = "conn", path = "/", connLabel = conn.label())
        FavoritesStore.add(app, fav)
        vm = PaneViewModel(app).apply { io = dispatcher }
        vm.bootstrap(listOf("group\tfav"))
        advanceUntilIdle()
        val node = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().first { it.fav.id == fav.id }
        vm.toggleFavorite(node, password = null) { _, _ -> }
        advanceUntilIdle()
        assertEquals(listOf("z.txt", "a.txt"), files("/").map { it.file.name })
        assertTrue(files("/").all { it.gridMode == 2 })
    }

    @Test
    fun `recursive settings apply to descendants without creating descendant records`() = runTest(dispatcher) {
        DirectoryViewStore.save(app, dir, DirectoryView(reverse, 2, sortRecursive = true, viewRecursive = true))
        vm.refreshSortAndView()
        vm.revealPath(dir.copy(path = "/photos/child"))
        advanceUntilIdle()
        assertEquals(listOf("z.txt", "a.txt"), files("/photos/child").map { it.file.name })
        assertTrue(files("/photos/child").all { it.gridMode == 2 })
        assertEquals(listOf("a.txt", "z.txt"), files("/").map { it.file.name })
        assertTrue(files("/").all { it.gridMode == 0 })
        assertEquals(1, DirectoryViewStore.load(app).size)

        // A view-only child override retains the inherited sorting.
        val child = dir.copy(path = "/photos/child")
        DirectoryViewStore.save(app, child, DirectoryView(gridMode = 0))
        vm.refreshSortAndView()
        assertEquals(listOf("z.txt", "a.txt"), files(child.path).map { it.file.name })
        assertTrue(files(child.path).all { it.gridMode == 0 })
        DirectoryViewStore.save(app, child, DirectoryView())
        vm.refreshSortAndView()
        assertTrue(files(child.path).all { it.gridMode == 2 })

        // Disabling recursion also updates already expanded descendants immediately.
        DirectoryViewStore.save(app, dir, DirectoryView(reverse, 2))
        vm.refreshSortAndView()
        assertEquals(listOf("a.txt", "z.txt"), files(child.path).map { it.file.name })
        assertTrue(files(child.path).all { it.gridMode == 0 })
        assertTrue(files(dir.path).all { it.gridMode == 2 })
    }

    @Test
    fun `a deep favorite inherits an ancestor rule without opening that ancestor`() = runTest(dispatcher) {
        DirectoryViewStore.save(app, dir, DirectoryView(reverse, 2, sortRecursive = true, viewRecursive = true))
        val conn = ConnectionStore.all(app).first { Connections.schemeOf(it) == dir.scheme }
        val fav = Favorite(label = "child", kind = "conn", path = "/photos/child", connLabel = conn.label())
        FavoritesStore.add(app, fav)
        vm = PaneViewModel(app).apply { io = dispatcher }
        vm.bootstrap(listOf("group\tfav"))
        advanceUntilIdle()
        val node = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().first { it.fav.id == fav.id }
        vm.toggleFavorite(node, password = null) { _, _ -> }
        advanceUntilIdle()
        assertEquals(listOf("z.txt", "a.txt"), files("/photos/child").map { it.file.name })
        assertTrue(files("/photos/child").all { it.gridMode == 2 })
    }

    @Test
    fun `opaque directory listings inherit using observed parents rather than path prefixes`() = runTest(dispatcher) {
        val scheme = dir.scheme
        fun node(path: String, name: String, directory: Boolean) =
            XFile(scheme, path, isDir = directory, displayName = name)
        val opaque = mapOf(
            "/" to listOf(node("id-90", "Shows", true)),
            "id-90" to listOf(node("id-12", "Season", true)),
            "id-12" to listOf(node("track-9", "z.txt", false), node("track-2", "a.txt", false)),
        )
        FsRegistry.register(object : FileSystem by FakeFileSystem(scheme, emptyMap()) {
            override fun list(dir: XFile) = opaque.getValue(dir.path)
        })
        DirectoryViewStore.save(app, dir.copy(path = "/"),
            DirectoryView(reverse, 2, sortRecursive = true, viewRecursive = true))
        vm.refreshSortAndView()
        val conn = ConnectionStore.all(app).first { Connections.schemeOf(it) == scheme }
        vm.revealUnder("s:${conn.label()}", null, "Shows/Season")
        advanceUntilIdle()
        val tracks = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>().filter { !it.file.isDir }
        assertEquals(listOf("z.txt", "a.txt"), tracks.map { it.file.name })
        assertTrue(tracks.all { it.gridMode == 2 })
        assertEquals("id-90", vm.directoryViewParent(node("id-12", "Season", true))?.path)
        assertEquals("/", vm.directoryViewParent(node("id-90", "Shows", true))?.path)
    }

    @Test
    fun `records survive reload and distinguish backends and exact directory paths`() {
        DirectoryViewStore.save(app, dir.copy(path = "/photos/"), DirectoryView(reverse, 2))
        val loaded = DirectoryViewStore.load(app)
        assertEquals(DirectoryView(reverse, 2), DirectoryViewStore.get(loaded, dir))
        assertNull(DirectoryViewStore.get(loaded, dir.copy(scheme = "other")))
        assertNull(DirectoryViewStore.get(loaded, dir.copy(path = "/photos/child")))
        assertNull(DirectoryViewStore.get(loaded, dir.copy(path = "/photos2")))
    }
}
