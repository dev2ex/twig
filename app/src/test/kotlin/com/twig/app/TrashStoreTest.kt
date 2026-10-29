package com.twig.app

import android.app.Application
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import com.twig.core.XFile
import com.twig.core.FsRegistry
import com.twig.fs.local.LocalFileSystem
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class, shadows = [TrashOsShadow::class, TrashMovesShadow::class])
class TrashStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var root: File
    private lateinit var store: TrashStore
    private var oldRoute: ((String) -> com.twig.core.FileSystem?)? = null

    @Before fun setup() {
        root = Files.createTempDirectory("twig-trash-test-").toFile().canonicalFile
        oldRoute = LocalFileSystem.documentAccess
        LocalFileSystem.documentAccess = null
        store = TrashStore(app, { listOf(root) })
    }
    @After fun cleanup() {
        LocalFileSystem.documentAccess = oldRoute
        root.deleteRecursively()
    }
    private fun file(path: String, text: String = "original"): File = File(root, path).apply {
        parentFile!!.mkdirs(); writeText(text)
    }
    private fun xf(file: File) = XFile("file", file.path, file.isDirectory, size = file.length())
    private fun trash(file: File) = store.move(store.plan(listOf(xf(file))).single())

    @Test fun `trash properties retain original locations and separate deletion from modification`() {
        val source = file("folder/sub/note.txt", "abc")
        val modified = 1_600_000_000_000L
        assertTrue(source.setLastModified(modified))
        val entry = trash(File(root, "folder"))
        val fs = TrashFileSystem(app, store)
        FsRegistry.register(fs)
        val folder = fs.list(fs.root()).single()
        val sub = fs.list(folder).single()
        val nested = fs.list(sub).single()
        assertEquals(modified, nested.lastModified) // Export must preserve the original mtime.
        val rows = FileInfo.load(app, nested).sections.first().rows.toMap()
        assertEquals(source.path, rows[app.getString(R.string.trash_original_path)])
        assertEquals(Format.time(entry.deletedAt, app), rows[app.getString(R.string.trash_deleted_at)])
        assertEquals(Format.time(modified, app), rows[app.getString(R.string.info_modified)])
        assertEquals(store.payload(entry, "sub/note.txt").path, rows[app.getString(R.string.info_path)])
        assertEquals(root.path, rows[app.getString(R.string.trash_storage)])
        assertFalse(rows.containsKey(app.getString(R.string.info_writable)))
        assertEquals("abc", fs.openInput(nested).bufferedReader().use { it.readText() })
        val folderRows = FileInfo.load(app, folder).sections.first().rows.toMap()
        assertEquals(File(root, "folder").path, folderRows[app.getString(R.string.trash_original_path)])
        assertEquals(app.getString(R.string.info_contains_value, 1, 0), folderRows[app.getString(R.string.info_contains)])
    }

    @Test fun `damaged recovery metadata still permits properties and read-only export`() {
        val entry = trash(file("recover.txt", "recoverable"))
        File(entry.directory, "info.json").writeText("broken")
        val fs = TrashFileSystem(app, store)
        FsRegistry.register(fs)
        val item = fs.list(fs.root()).single()
        val rows = FileInfo.load(app, item).sections.first().rows.toMap()
        assertEquals(app.getString(R.string.trash_invalid, entry.directory.path), rows[app.getString(R.string.trash_original_path)])
        assertEquals(File(entry.directory, "data").path, rows[app.getString(R.string.info_path)])
        assertFalse(rows.containsKey(app.getString(R.string.trash_deleted_at)))
        assertEquals("recoverable", fs.openInput(item).bufferedReader().use { it.readText() })
        assertThrows(Exception::class.java) { store.restore(entry) }
    }

    @Test fun `nested restore rejects a replaced ancestor before creating directories outside the volume`() {
        file("folder/sub/a.txt")
        val entry = trash(File(root, "folder"))
        val outside = Files.createTempDirectory("twig-trash-outside-").toFile()
        try {
            Files.createSymbolicLink(File(root, "folder").toPath(), outside.toPath())
            assertThrows(IOException::class.java) { store.restoreItem(entry, "sub/a.txt", "a.txt", true) }
            assertFalse(File(outside, "sub").exists())
            assertEquals("original", store.payload(entry, "sub/a.txt").readText())
        } finally { File(root, "folder").delete(); outside.deleteRecursively() }
    }

    @Test fun `tree exposes descendants and nested restore preserves siblings`() {
        val a = file("folder/sub/a.txt", "a")
        file("folder/b.txt", "b")
        val entry = trash(File(root, "folder"))
        val fs = TrashFileSystem(app, store)
        val folder = fs.list(fs.root()).single()
        assertTrue(folder.isDir)
        assertEquals("folder", folder.name)
        assertEquals(setOf("sub", "b.txt"), fs.list(folder).map { it.name }.toSet())
        val sub = fs.list(folder).single { it.name == "sub" }
        val nested = fs.item(fs.list(sub).single())
        assertEquals("sub/a.txt", nested.relative)
        assertFalse(a.parentFile!!.isDirectory)
        assertThrows(IOException::class.java) { store.restoreItem(entry, nested.relative, "a.txt") }
        store.restoreItem(entry, nested.relative, "a.txt", true)
        assertEquals("a", a.readText())
        assertEquals("b", store.payload(entry, "b.txt").readText())
        assertEquals(entry, store.snapshot().entries.single())
        store.purgeItem(entry, "sub")
        assertEquals("b", store.payload(entry, "b.txt").readText())
        store.restore(entry, "remaining")
        assertEquals("b", File(root, "remaining/b.txt").readText())
    }

    @Test fun `virtual trash cannot traverse symlinks or escape a deleted directory`() {
        file("outside/keep.txt", "safe")
        file("folder/plain.txt")
        Files.createSymbolicLink(File(root, "folder/link").toPath(), File(root, "outside").toPath())
        val entry = trash(File(root, "folder"))
        val fs = TrashFileSystem(app, store)
        val folder = fs.list(fs.root()).single()
        val link = fs.list(folder).single { it.name == "link" }
        assertFalse(link.isDir)
        assertFalse(link.canRead)
        FsRegistry.register(fs)
        val properties = FileInfo.load(app, link).sections.single().rows.toMap()
        assertEquals(app.getString(R.string.info_symbolic_link), properties[app.getString(R.string.info_type)])
        assertEquals(File(root, "outside").path, properties[app.getString(R.string.info_link_target)])
        assertThrows(IOException::class.java) { fs.openInput(link) }
        assertThrows(IOException::class.java) { store.payload(entry, "link/keep.txt") }
        assertThrows(IOException::class.java) { store.purgeItem(entry, "../info.json") }
        store.purgeItem(entry, "link")
        assertEquals("safe", File(root, "outside/keep.txt").readText())
    }

    @Test fun `move and restore preserve whole directories metadata and same-volume inode`() {
        val original = file("photos/旅行.txt")
        val before = Os.stat(original.path)
        val entry = trash(original.parentFile!!)
        assertFalse(original.parentFile!!.exists())
        val payload = File(entry.directory, "data/旅行.txt")
        assertEquals("original", payload.readText())
        assertEquals(before.st_ino, Os.stat(payload.path).st_ino)
        assertEquals(before.st_dev, Os.stat(payload.path).st_dev)
        val reopened = TrashStore(app, { listOf(root) })
        val found = reopened.snapshot().entries.single()
        assertEquals(entry.original, found.original)
        reopened.restore(found)
        assertEquals("original", original.readText())
        assertTrue(reopened.snapshot().entries.isEmpty())
    }

    @Test fun `a failed rename leaves original bytes and never copies or deletes them`() {
        val original = file("keep.txt")
        val failing = TrashStore(app, { listOf(root) }, { _, _ -> false })
        assertThrows(IOException::class.java) { failing.move(failing.plan(listOf(xf(original))).single()) }
        assertEquals("original", original.readText())
        assertTrue(store.snapshot().entries.isEmpty())
    }

    @Test fun `recovery record is durable before move and survives interruption after rename`() {
        val original = file("recover.txt")
        val crashing = TrashStore(app, { listOf(root) }, { source, destination ->
            val info = File(destination.parentFile, "info.json")
            assertEquals(source.path, JSONObject(info.readText()).getString("original"))
            assertEquals("original", source.readText())
            assertTrue(source.renameTo(destination))
            throw IOException("simulated interruption")
        })
        assertThrows(IOException::class.java) { crashing.move(crashing.plan(listOf(xf(original))).single()) }
        assertFalse(original.exists())
        store.restore(store.snapshot().entries.single())
        assertEquals("original", original.readText())
    }

    @Test fun `restore refuses conflicts and accepts a new name without overwriting`() {
        val original = file("name.txt")
        val entry = trash(original)
        original.writeText("new file")
        assertThrows(IOException::class.java) { store.restore(entry) }
        assertEquals("new file", original.readText())
        assertEquals("original", File(entry.directory, "data").readText())
        store.restore(entry, "restored.txt")
        assertEquals("new file", original.readText())
        assertEquals("original", File(root, "restored.txt").readText())
    }

    @Test fun `missing parents require explicit permission to recreate`() {
        val original = file("removed/folder/item.txt")
        val entry = trash(original)
        assertTrue(original.parentFile!!.delete())
        assertFalse(original.parentFile!!.isDirectory)
        assertThrows(IOException::class.java) { store.restore(entry) }
        assertFalse(original.parentFile!!.exists())
        store.restore(entry, createParents = true)
        assertEquals("original", original.readText())
    }

    @Test fun `purge deletes symlinks themselves and retains targets outside the trash`() {
        val outside = file("keep/outside.txt", "keep me")
        val directory = File(root, "links").apply { mkdirs() }
        Files.createSymbolicLink(File(directory, "outside").toPath(), outside.parentFile!!.toPath())
        val broken = File(directory, "broken")
        Files.createSymbolicLink(broken.toPath(), File(root, "missing").toPath())
        val entry = trash(directory)
        store.purge(entry)
        assertEquals("keep me", outside.readText())
        assertFalse(entry.directory.exists())
    }

    @Test fun `damaged metadata keeps payload visible and cannot restore to a forged path`() {
        val entry = trash(file("secret.txt"))
        File(entry.directory, "info.json").writeText("broken")
        val damaged = store.snapshot().entries.single()
        assertNull(damaged.original)
        assertEquals("original", File(damaged.directory, "data").readText())
        File(entry.directory, "info.json").writeText(JSONObject().put("original", "/outside/forged.txt").toString())
        assertNull(store.snapshot().entries.single().original)
        assertThrows(Exception::class.java) { store.restore(entry) }
        assertTrue(File(entry.directory, "data").exists())
    }

    @Test fun `unmounted volumes are omitted and restore cannot recreate their mount path`() {
        val entry = trash(file("offline.txt"))
        val detached = File(root.parentFile, root.name + "-detached")
        assertTrue(root.renameTo(detached))
        try {
            assertTrue(store.snapshot().entries.isEmpty())
            assertTrue(store.snapshot().damaged.isEmpty())
            assertThrows(IOException::class.java) { store.restore(entry) }
            assertFalse(root.exists())
        } finally { assertTrue(detached.renameTo(root)) }
        val rediscovered = TrashStore(app, { listOf(root) }).snapshot().entries.single()
        store.restore(rediscovered)
        assertEquals("original", File(root, "offline.txt").readText())
    }

    @Test fun `existing unrelated trash directories and symlink buckets are not adopted`() {
        val original = file("keep.txt")
        val bucket = File(root, TrashStore.DIRECTORY).apply { mkdirs() }
        File(bucket, "user-file").writeText("keep")
        assertThrows(IOException::class.java) { trash(original) }
        assertEquals("keep", File(bucket, "user-file").readText())
        assertEquals("original", original.readText())
        bucket.deleteRecursively()
        Files.createSymbolicLink(bucket.toPath(), File(root, "elsewhere").apply { mkdirs() }.toPath())
        assertThrows(IOException::class.java) { trash(original) }
        Files.delete(bucket.toPath())
    }

    @Test fun `empty only removes the confirmed entries and leaves later additions`() {
        trash(file("first.txt"))
        val confirmed = store.snapshot().entries
        val later = trash(file("second.txt"))
        store.empty(confirmed)
        assertEquals(listOf(later.directory), store.snapshot().entries.map { it.directory })
    }

    @Test fun `plans prune nested selections and exclude remote SAF protected and trash paths`() {
        val original = file("folder/child.txt")
        val plan = store.plan(listOf(xf(original.parentFile!!), xf(original)))
        assertEquals(listOf(original.parent), plan.map { it.file.path })
        assertNotNull(plan.single().volume)
        assertNull(store.plan(listOf(xf(original).copy(scheme = "saf"))).single().volume)
        assertNull(store.plan(listOf(xf(original).copy(scheme = "smb123"))).single().volume)
        val private = Files.createTempFile("twig-private-", ".txt").toFile()
        try { assertNull(store.plan(listOf(xf(private))).single().volume) } finally { private.delete() }
        LocalFileSystem.documentAccess = { object : com.twig.core.FileSystem by LocalFileSystem() {} }
        assertNull(store.plan(listOf(xf(original))).single().volume)
        LocalFileSystem.documentAccess = null
        val entry = trash(original)
        assertNull(store.plan(listOf(xf(File(entry.directory, "data")))).single().volume)
    }

    @Test fun `malformed entries and symlink entries remain untouched during snapshot`() {
        trash(file("good.txt"))
        val bucket = File(root, TrashStore.DIRECTORY)
        val target = file("unrelated.txt", "safe")
        val forged = File(bucket, UUID.randomUUID().toString())
        Files.createSymbolicLink(forged.toPath(), target.toPath())
        val snapshot = store.snapshot()
        assertEquals(1, snapshot.entries.size)
        assertTrue(snapshot.damaged.contains(forged.path))
        assertEquals("safe", target.readText())
        Files.delete(forged.toPath())
    }
}
