package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectoryViewInheritanceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val root = XFile("file", "/", isDir = true)
    private val parent = root.copy(path = "/photos")
    private val child = root.copy(path = "/photos/album")
    private val deep = root.copy(path = "/photos/album/day")
    private val reverse = SortSpec(reversed = true)

    @Before
    fun clear() { app.getSharedPreferences(DirectoryViewStore.FILE, 0).edit().clear().commit() }

    @Test
    fun `nearest recursive ancestors resolve sort and view independently`() {
        DirectoryViewStore.save(app, root, DirectoryView(reverse, 2, sortRecursive = true, viewRecursive = true))
        DirectoryViewStore.save(app, parent, DirectoryView(gridMode = 1, viewRecursive = true))
        DirectoryViewStore.save(app, child, DirectoryView(sort = SortSpec()))
        val views = DirectoryViewStore.load(app)
        assertEquals(SortSpec(), DirectoryViewStore.resolve(views, child).sort)
        assertEquals(1, DirectoryViewStore.resolve(views, child).gridMode)
        // The child's nonrecursive sort does not propagate or block the recursive root.
        assertEquals(reverse, DirectoryViewStore.resolve(views, deep).sort)
        assertEquals(1, DirectoryViewStore.resolve(views, deep).gridMode)
    }

    @Test
    fun `inheritance respects path boundaries backends and root`() {
        DirectoryViewStore.save(app, parent, DirectoryView(reverse, 2, sortRecursive = true, viewRecursive = true))
        val views = DirectoryViewStore.load(app)
        assertEquals(DirectoryView(), DirectoryViewStore.resolve(views, parent.copy(path = "/photos2/album")))
        assertEquals(DirectoryView(), DirectoryViewStore.resolve(views, child.copy(scheme = "ftp")))
        assertEquals(DirectoryView(), DirectoryViewStore.resolve(views, root))
        assertEquals(reverse, DirectoryViewStore.resolve(views, deep).sort)
    }

    @Test
    fun `opaque identifiers use actual parent links and tolerate cycles`() {
        val a = XFile("saf", "content://provider/tree/root/document/id-95", isDir = true)
        val b = a.copy(path = "content://provider/tree/root/document/id-12")
        val c = a.copy(path = "content://provider/tree/root/document/id-77")
        DirectoryViewStore.save(app, a, DirectoryView(reverse, sortRecursive = true))
        DirectoryViewStore.save(app, b, DirectoryView(gridMode = 2, viewRecursive = true))
        val views = DirectoryViewStore.load(app)
        val parents = mapOf(c.path to b, b.path to a, a.path to c)
        val resolved = DirectoryViewStore.resolve(views, c) { parents[it.path] }
        assertEquals(reverse, resolved.sort)
        assertEquals(2, resolved.gridMode)
        // Only one setting is found in this cycle; resolving the other still terminates.
        val onlySort = views.filterValues { it.sort != null }
        assertEquals(reverse, DirectoryViewStore.resolve(onlySort, c) { parents[it.path] }.sort)
        assertNull(DirectoryViewStore.resolve(onlySort, c) { parents[it.path] }.gridMode)
        assertNull(DirectoryViewStore.pathParent(c))
    }
}
