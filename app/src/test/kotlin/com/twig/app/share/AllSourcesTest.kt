package com.twig.app.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
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
        assertTrue(segs.contains("storage"))
        assertTrue(segs.contains("root"))
        // there should not also be a bare "file" source (its root is "/", which shows the user nothing but system directories)
        assertFalse(segs.contains("file"))
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
        val src = root().sources().first { it.segment == "storage" }
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
