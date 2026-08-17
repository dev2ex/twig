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
 * 「所有来源」模式。
 *
 * 第一版这个模式**一条测试都没有**(全部用例都跑在「指定目录」上),于是三个错误假设
 * 一路带到了真机:`root()` 会抛的来源被当成可浏览的根、路径不透明的来源按"父路径 + 名字"
 * 寻址、href 用解码后的路径拼编码后的名字。这里就是补上那一课。
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

    // ---- 来源过滤 ----

    /**
     * `root()` 抛异常的来源不该出现在列表里。真机上就是那四个点进去必然报错的条目:
     * Archive / 7z archive / RAR archive / Share。
     */
    @Test
    fun `没有可浏览根的来源不列出来`() {
        register(NoRootFs("zipish", "Archive"))
        register(NoRootFs("shareish", "Share"))
        val segs = root().sources().map { it.segment }
        assertFalse("Archive 不该出现", segs.contains("zipish"))
        assertFalse("Share 不该出现", segs.contains("shareish"))
    }

    @Test
    fun `本地存储拆成内部存储与根目录两项`() {
        val segs = root().sources().map { it.segment }
        assertTrue(segs.contains("storage"))
        assertTrue(segs.contains("root"))
        // 不该再有一个裸的 file 来源(那个根是 "/",用户看到的全是系统目录)
        assertFalse(segs.contains("file"))
    }

    @Test
    fun `有根的来源照常列出`() {
        register(FakeTreeFs())
        val s = root().sources().firstOrNull { it.segment == FakeTreeFs.SCHEME }
        assertNotNull(s)
        assertEquals("Fake", s!!.label)
    }

    // ---- 不透明路径 ----

    /**
     * 「应用」那种 path 是包名、name 是 `微信 8.0.x.apk` 的来源:URL 里只可能出现
     * 显示名,按"父路径 + 名字"拼回去 resolve 必然失败,只有逐级按名字找才能拿到
     * 真正的 XFile(path 仍是包名)。
     */
    @Test
    fun `路径不透明的来源能按显示名定位`() {
        register(FakeTreeFs())
        val f = root().resolve("/${FakeTreeFs.SCHEME}/用户/微信 8.0.1.apk")
        assertNotNull("按显示名应能找到", f)
        assertEquals("拿到的必须是真实的不透明 path", "/user/com.tencent.mm", f!!.path)
        assertFalse(f.isDir)
        assertEquals(1234L, f.size)
    }

    @Test
    fun `不透明来源的目录也能进`() {
        register(FakeTreeFs())
        val d = root().resolve("/${FakeTreeFs.SCHEME}/用户")
        assertNotNull(d)
        assertTrue(d!!.isDir)
        assertEquals("/user", d.path)
    }

    @Test
    fun `名字对不上时返回 null 而不是造一个假条目`() {
        register(FakeTreeFs())
        assertNull(root().resolve("/${FakeTreeFs.SCHEME}/用户/com.tencent.mm"))
        assertNull(root().resolve("/${FakeTreeFs.SCHEME}/不存在"))
    }

    // ---- 本地来源 ----

    @Test
    fun `内部存储来源解析到外部存储目录`() {
        val src = root().sources().first { it.segment == "storage" }
        val f = root().resolve("/storage")
        assertNotNull(f)
        assertEquals(src.basePath, f!!.path)
        assertTrue(f.isDir)
    }

    @Test
    fun `根目录来源能往下走`() {
        val tmp = File(System.getProperty("java.io.tmpdir")).absolutePath
        val segs = tmp.trim('/').split('/')
        val f = root().resolve("/root/" + segs.joinToString("/"))
        assertNotNull("应能沿真实目录一路走下去", f)
        assertTrue(f!!.isDir)
    }

    @Test
    fun `未知来源段返回 null`() {
        assertNull(root().resolve("/nope/whatever"))
    }

    @Test
    fun `路径穿越在所有来源模式下同样被拒`() {
        assertNull(root().resolve("/storage/../../etc/passwd"))
    }

    @Test
    fun `虚拟根没有 XFile`() {
        assertTrue(root().isVirtualRoot("/"))
        assertNull(root().resolve("/"))
    }

    // ---- 桩 ----

    /** `root()` 抛异常:压缩包/SAF/share 那一类"必须先挂载"的来源。 */
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
     * path 与显示名彻底脱钩的只读来源,照着 `AppsFileSystem` 的形状造:
     * `/user` 目录里放一个 path 为包名、name 为 `微信 8.0.1.apk` 的条目。
     */
    private class FakeTreeFs : FileSystem {
        override val scheme: String = SCHEME
        override val displayName: String = "Fake"

        override fun root(): XFile = XFile(SCHEME, "/", isDir = true)

        override fun resolve(path: String): XFile = when (path.trim('/')) {
            "" -> root()
            "user" -> XFile(SCHEME, "/user", isDir = true, displayName = "用户")
            else -> throw FsException("no such path: $path") // ★ 拼路径这条道走不通
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
