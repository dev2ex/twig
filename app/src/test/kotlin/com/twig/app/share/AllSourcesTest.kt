package com.twig.app.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.app.Connections
import com.twig.app.ConnectionStore
import com.twig.app.RemovableVolume
import com.twig.app.SavedConnection
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The "all sources" mode.
 *
 * The first version of this mode had **not a single test** (every case ran against a
 * "specified directory"), so three wrong assumptions rode all the way to a real device:
 * a source whose `root()` throws was treated as a browsable root, a source with opaque
 * paths was addressed by "parent path + name", and an href concatenated a decoded path
 * with an encoded name. This file makes up for that lesson.
 *
 * Note: the literal Chinese strings below (`用户`, `微信 8.0.1.apk`) are deliberately
 * left untranslated — they are fixture data for the "opaque path, non-ASCII display
 * name" tests, which specifically verify that a non-ASCII display name resolves and
 * round-trips correctly through the URL path.
 */
@RunWith(RobolectricTestRunner::class)
class AllSourcesTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private lateinit var dir: File
    private val registered = ArrayList<String>()

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "twig-all-${System.nanoTime()}")
        File(dir, "sub").mkdirs()
        File(dir, "hello.txt").writeText("hi")
        register(LocalFileSystem())
    }

    @After
    fun tearDown() {
        registered.forEach { FsRegistry.unregister(it) }
        dir.deleteRecursively()
    }

    private fun register(fs: FileSystem) {
        FsRegistry.register(fs)
        registered += fs.scheme
    }

    private fun root() = ShareRoot(app, ShareScope.AllSources)

    // ---- Source filtering ----

    /**
     * A source whose `root()` throws must not show up in the listing. On a real device
     * these were exactly the four entries that always error out on tap: Archive / 7z
     * archive / RAR archive / Share.
     */
    @Test
    fun `a source with no browsable root is not listed`() {
        register(NoRootFs("zipish", "Archive"))
        register(NoRootFs("shareish", "Share"))
        val segs = root().sources().map { it.segment }
        assertFalse("Archive must not appear", segs.contains("zipish"))
        assertFalse("Share must not appear", segs.contains("shareish"))
    }

    @Test
    fun `local storage splits into internal storage and root directory entries`() {
        val segs = root().sources().map { it.segment }
        assertTrue(segs.contains("Internal-Storage"))
        assertTrue(segs.contains("Root"))
        // there should not also be a bare "file" source (its root is "/", which shows the user nothing but system directories)
        assertFalse(segs.contains("file"))
    }

    @Test
    fun `fixed source names are capitalized and their old addresses still resolve`() {
        register(NamedTreeFs("apps"))
        register(NamedTreeFs("trash"))
        for (name in listOf("Root", "Apps", "Trash")) {
            assertTrue(root().sources().any { it.segment == name })
            assertEquals(root().resolve("/$name"), root().resolve("/" + name.lowercase()))
        }
    }

    @Test
    fun `a source that has a root is listed as usual`() {
        register(FakeTreeFs())
        val s = root().sources().firstOrNull { it.segment == FakeTreeFs.SCHEME }
        assertNotNull(s)
        assertEquals("Fake", s!!.label)
    }

    // ---- Opaque paths ----

    /**
     * A source like "Apps" whose path is a package name and whose name is
     * `WeChat 8.0.x.apk`: only the display name can ever appear in a URL, so resolving by
     * gluing "parent path + name" back together must fail — only walking level by level
     * by name can retrieve the real XFile (whose path remains the package name).
     */
    @Test
    fun `a source with an opaque path can be located by display name`() {
        register(FakeTreeFs())
        val f = root().resolve("/${FakeTreeFs.SCHEME}/用户/微信 8.0.1.apk")
        assertNotNull("it should be findable by display name", f)
        assertEquals("what comes back must be the real opaque path", "/user/com.tencent.mm", f!!.path)
        assertFalse(f.isDir)
        assertEquals(1234L, f.size)
    }

    @Test
    fun `a directory of an opaque source can be entered too`() {
        register(FakeTreeFs())
        val d = root().resolve("/${FakeTreeFs.SCHEME}/用户")
        assertNotNull(d)
        assertTrue(d!!.isDir)
        assertEquals("/user", d.path)
    }

    @Test
    fun `a mismatched name returns null instead of fabricating a fake entry`() {
        register(FakeTreeFs())
        assertNull(root().resolve("/${FakeTreeFs.SCHEME}/用户/com.tencent.mm"))
        assertNull(root().resolve("/${FakeTreeFs.SCHEME}/不存在"))
    }

    // ---- Local sources ----

    @Test
    fun `the internal storage source resolves to the external storage directory`() {
        val src = root().sources().first { it.segment == "Internal-Storage" }
        assertEquals(root().resolve("/Internal-Storage"), root().resolve("/storage"))
        val f = root().resolve("/storage")
        assertNotNull(f)
        assertEquals(src.basePath, f!!.path)
        assertTrue(f.isDir)
    }

    @Test
    fun `the root directory source can walk further down`() {
        val tmp = File(System.getProperty("java.io.tmpdir")).absolutePath
        val segs = tmp.trim('/').split('/')
        val f = root().resolve("/root/" + segs.joinToString("/"))
        assertNotNull("it should be able to walk all the way down the real directory", f)
        assertTrue(f!!.isDir)
    }

    @Test
    fun `an unknown source segment returns null`() {
        assertNull(root().resolve("/nope/whatever"))
    }

    // ---- Server segments ----

    /**
     * A server is addressed by its type and its name — `SMB-NAS`, `WebDAV-192.168.1.5` —
     * because that string is what a WebDAV client shows as the mount point. The old
     * segment (the hashed scheme) still resolves, so a mount point saved before the
     * rename keeps working.
     */
    @Test
    fun `a server is addressed by its type and name`() {
        val conn = SavedConnection(type = "smb", host = "192.168.1.10", share = "Public", name = "NAS")
        ConnectionStore.save(app, conn)
        register(NamedTreeFs(Connections.schemeOf(conn)))
        val src = root().sources().first { it.scheme == Connections.schemeOf(conn) }
        assertEquals("SMB-NAS", src.segment)
        assertNotNull("the readable address works", root().resolve("/SMB-NAS"))
        assertNotNull("the old address still works", root().resolve("/${Connections.schemeOf(conn)}"))
        assertNotNull("the lowercase readable address still works", root().resolve("/smb-NAS"))
    }

    /** A server without a name of its own is addressed by its address. */
    @Test
    fun `a server without a name is addressed by its address`() {
        val conn = SavedConnection(type = "webdav", host = "http://192.168.1.5:8080/dav")
        ConnectionStore.save(app, conn)
        register(NamedTreeFs(Connections.schemeOf(conn)))
        val src = root().sources().first { it.scheme == Connections.schemeOf(conn) }
        assertEquals("WebDAV-192.168.1.5-8080-dav", src.segment)
    }

    @Test
    fun `server addresses survive registry changes and suffix collisions`() {
        val a = SavedConnection(type = "smb", host = "a", name = "NAS")
        val b = SavedConnection(type = "smb", host = "b", name = "NAS")
        val collision = SavedConnection(type = "smb", host = "c",
            name = "NAS-${Connections.schemeOf(a).removePrefix("smb")}")
        listOf(a, b, collision).forEach { ConnectionStore.save(app, it) }
        val schemes = listOf(a, b, collision).map { Connections.schemeOf(it) }
        schemes.forEach { register(NamedTreeFs(it)) }
        fun addresses() = root().sources().filter { it.scheme in schemes }.associate { it.scheme to it.segment }
        val first = addresses()
        assertEquals(3, first.values.toSet().size)
        first.forEach { (scheme, segment) ->
            assertEquals(scheme, root().resolve("/$segment")!!.scheme)
            assertEquals(scheme, root().resolve("/" + segment.replaceFirst("SMB-", "smb-"))!!.scheme)
        }
        FsRegistry.unregister(schemes[1])
        assertEquals(first - schemes[1], addresses())
        register(NamedTreeFs(schemes[1]))
        assertEquals(first, addresses())
    }

    // ---- Removable volumes (issue #64) ----
    /**
     * The app's own tree lists the SD card and the USB drive at the top, so "all sources"
     * has to offer them too: a visitor used to see internal storage only, on a device
     * whose card was perfectly visible in the app.
     *
     * The segment is `External-` plus the volume's **own name** — that is what a WebDAV
     * client shows as the mount point, so it has to be the name, not an internal id.
     */
    @Test
    fun `a removable volume is offered as a source and can be entered`() {
        val card = File(dir, "card")
        card.mkdirs()
        File(card, "photo.jpg").writeText("x")
        val root = ShareRoot(app, ShareScope.AllSources) {
            listOf(RemovableVolume(card.absolutePath, "Sd256"))
        }
        val src = root.sources().firstOrNull { it.segment == "External-Sd256" }
        assertNotNull("the card should be a source", src)
        assertEquals("Sd256", src!!.label)
        assertEquals(root.resolve("/External-Sd256"), root.resolve("/external-Sd256"))
        val f = root.resolve("/External-Sd256/photo.jpg")
        assertNotNull("the card's contents should be reachable", f)
        assertEquals("photo.jpg", f!!.name)
    }

    /**
     * The system's description of a volume is **localized** ("USB drive" / "USB-накопитель"),
     * so the segment must not be derived from it afresh every time: a language change would
     * move the address and a saved WebDAV mount point would 404.
     */
    @Test
    fun `the segment of a volume does not move when its name changes`() {
        val card = File(dir, "stick").apply { mkdirs() }
        val first = ShareRoot(app, ShareScope.AllSources) {
            listOf(RemovableVolume(card.absolutePath, "USB drive"))
        }
        assertEquals("USB drive", first.sources().first { it.segment == "External-USB drive" }.label)
        // The system language changed: the volume is called something else now
        val second = ShareRoot(app, ShareScope.AllSources) {
            listOf(RemovableVolume(card.absolutePath, "USB-накопитель"))
        }
        val segs = second.sources().map { it.segment }
        assertTrue("the remembered address must still work", segs.contains("External-USB drive"))
        assertEquals("the label follows the system", "USB-накопитель",
            second.sources().first { it.segment == "External-USB drive" }.label)
    }

    /**
     * A volume mounted "invisible to the app" (its path is `/mnt/media_rw/<id>`, root-only)
     * cannot be listed by a LAN visitor — the privileged fallback is off for them. Such a
     * volume is not offered at all, rather than offered and always failing.
     */
    @Test
    fun `a volume that cannot be opened is not offered`() {
        val root = ShareRoot(app, ShareScope.AllSources) {
            listOf(RemovableVolume(File(dir, "gone").absolutePath, "Ghost"))
        }
        assertFalse(root.sources().map { it.segment }.contains("External-Ghost"))
    }

    /** Two cards with the same system name must not produce two identical rows. */
    @Test
    fun `two volumes with the same label are told apart`() {
        val a = File(dir, "card-a").apply { mkdirs() }
        val b = File(dir, "card-b").apply { mkdirs() }
        val root = ShareRoot(app, ShareScope.AllSources) {
            listOf(RemovableVolume(a.absolutePath, "SD card"), RemovableVolume(b.absolutePath, "SD card"))
        }
        val volSources = root.sources().filter { it.segment.startsWith("External-") }
        assertEquals(2, volSources.size)
        assertEquals("the addresses have to differ", 2, volSources.map { it.segment }.toSet().size)
        assertEquals("the labels have to differ", 2, volSources.map { it.label }.toSet().size)
    }

    @Test
    fun `volume addresses survive hotplug and never collide with another volume id`() {
        val a = File(dir, "card-a").apply { mkdirs() }
        val b = File(dir, "card-b").apply { mkdirs() }
        val c = File(dir, "card-c").apply { mkdirs() }
        var attached = listOf(RemovableVolume(a.absolutePath, "SD card"))
        val share = ShareRoot(app, ShareScope.AllSources) { attached }
        fun addresses() = share.sources().filter { it.segment.startsWith("External-") }
            .associate { it.basePath to it.segment }
        val original = addresses().getValue(a.absolutePath)
        attached = attached + RemovableVolume(b.absolutePath, "SD card") +
            RemovableVolume(c.absolutePath, "SD card-card-b")
        val all = addresses()
        assertEquals(original, all[a.absolutePath])
        assertEquals(3, all.values.toSet().size)
        all.forEach { (path, segment) -> assertEquals(path, share.resolve("/$segment")!!.path) }
        attached = attached.drop(1)
        assertEquals(all - a.absolutePath, addresses())
        attached = attached.reversed() + RemovableVolume(a.absolutePath, "Renamed")
        assertEquals(all, addresses())
    }

    @Test
    fun `a volume with an empty sanitized label keeps its first address`() {
        val card = File(dir, "empty-label").apply { mkdirs() }
        var label = ".."
        val share = ShareRoot(app, ShareScope.AllSources) { listOf(RemovableVolume(card.absolutePath, label)) }
        val first = share.sources().first { it.basePath == card.absolutePath }.segment
        label = "New name"
        assertEquals(first, share.sources().first { it.basePath == card.absolutePath }.segment)
    }

    /** A volume named like a fixed source must not shadow it. */
    @Test
    fun `a volume named like a fixed source does not shadow it`() {
        val card = File(dir, "card-root").apply { mkdirs() }
        val root = ShareRoot(app, ShareScope.AllSources) {
            listOf(RemovableVolume(card.absolutePath, "root"))
        }
        val segs = root.sources().map { it.segment }
        assertTrue("the fixed source is still there", segs.contains("Root"))
        assertTrue("the volume got its own address", segs.contains("External-root"))
    }

    @Test
    fun `path traversal is rejected the same way under all-sources mode`() {
        assertNull(root().resolve("/storage/../../etc/passwd"))
    }

    @Test
    fun `the virtual root has no XFile`() {
        assertTrue(root().isVirtualRoot("/"))
        assertNull(root().resolve("/"))
    }

    // ---- Stubs ----

    /** A backend with a given scheme — stands in for a registered server. */
    private class NamedTreeFs(override val scheme: String) : FileSystem {
        override val displayName: String = "Server"
        override fun root(): XFile = XFile(scheme, "/", isDir = true)
        override fun resolve(path: String): XFile = XFile(scheme, path, isDir = true)
        override fun list(dir: XFile): List<XFile> = emptyList()
        override fun openInput(file: XFile): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("read-only")
        override fun mkdir(parent: XFile, name: String): XFile = throw FsException("read-only")
        override fun delete(file: XFile) = throw FsException("read-only")
        override fun rename(file: XFile, newName: String): XFile = throw FsException("read-only")
        override fun exists(file: XFile): Boolean = true
    }

    /** `root()` throws: the class of sources that "must be mounted first", like archives/SAF/share. */
    private class NoRootFs(override val scheme: String, override val displayName: String) : FileSystem {
        override fun root(): XFile = throw FsException("must be mounted first")
        override fun resolve(path: String): XFile = throw FsException("no")
        override fun list(dir: XFile): List<XFile> = emptyList()
        override fun openInput(file: XFile): InputStream = throw FsException("no")
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("no")
        override fun mkdir(parent: XFile, name: String): XFile = throw FsException("no")
        override fun delete(file: XFile) = throw FsException("no")
        override fun rename(file: XFile, newName: String): XFile = throw FsException("no")
        override fun exists(file: XFile): Boolean = false
    }

    /**
     * A read-only source whose path is completely decoupled from its display name, built
     * in the shape of `AppsFileSystem`: the `/user` directory holds one entry whose path
     * is a package name and whose name is `微信 8.0.1.apk` ("WeChat 8.0.1.apk").
     */
    private class FakeTreeFs : FileSystem {
        override val scheme: String = SCHEME
        override val displayName: String = "Fake"

        override fun root(): XFile = XFile(SCHEME, "/", isDir = true)

        override fun resolve(path: String): XFile = when (path.trim('/')) {
            "" -> root()
            "user" -> XFile(SCHEME, "/user", isDir = true, displayName = "用户")
            else -> throw FsException("no such path: $path") // ★ gluing the path back together does not work here
        }

        override fun list(dir: XFile): List<XFile> = when (dir.path.trim('/')) {
            "" -> listOf(XFile(SCHEME, "/user", isDir = true, displayName = "用户"))
            "user" -> listOf(
                XFile(
                    SCHEME, "/user/com.tencent.mm", isDir = false,
                    size = 1234L, displayName = "微信 8.0.1.apk",
                ),
            )
            else -> emptyList()
        }

        override fun openInput(file: XFile): InputStream = ByteArrayInputStream(ByteArray(1234))
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("read-only")
        override fun mkdir(parent: XFile, name: String): XFile = throw FsException("read-only")
        override fun delete(file: XFile) = throw FsException("read-only")
        override fun rename(file: XFile, newName: String): XFile = throw FsException("read-only")
        override fun exists(file: XFile): Boolean = true
        override fun writable(): Boolean = false

        companion object {
            const val SCHEME = "faketree"
        }
    }
}
