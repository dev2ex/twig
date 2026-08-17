package com.twig.fs.archive

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 带密码打包。zip 那条是**自己手写的写出器**([ZipWriter]),所以除了往返,还要盯住
 * 「别家工具认不认」——把 `TWIG_DUMP_DIR` 指到一个目录跑这个类,会把包吐出来,
 * 再用 `7z t -psecret xxx.zip` / `unzip -P secret -t xxx.zip` 验一遍。
 */
class ArchiveEncryptWriteTest {

    private lateinit var tmp: File
    private lateinit var src: File
    private lateinit var out: File
    private val zipFs = ZipFileSystem()
    private val sevenZFs = SevenZFileSystem()

    /** 跨 deflate 缓冲与 AES 计数块的大文件,单块数据测不到计数器递增。 */
    private val big = ByteArray(3 * 1024 * 1024) { ((it * 31) xor (it shr 7)).toByte() }

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(zipFs)
        FsRegistry.register(sevenZFs)

        tmp = File.createTempFile("twigenc", "").let { it.delete(); it.mkdirs(); it }
        src = File(tmp, "src").apply { mkdirs() }
        File(src, "hello.txt").writeText("hello encrypted world")
        File(src, "中文 目录").mkdirs()
        File(src, "中文 目录/说明.txt").writeText("名字带空格和中文")
        File(src, "big.bin").writeBytes(big)
        out = File(tmp, "out").apply { mkdirs() }
    }

    private fun local(f: File) = XFile("file", f.absolutePath, isDir = f.isDirectory, size = f.length())

    private fun ArchiveFileSystem.read(archive: File, inner: String): ByteArray {
        val root = rootOf(XFile("file", archive.absolutePath, isDir = false, size = archive.length()))
        var cur = root
        val parts = inner.split('/')
        for (p in parts.dropLast(1)) cur = list(cur).first { it.name == p && it.isDir }
        val f = list(cur).first { it.name == parts.last() }
        return openInput(f).use { it.readBytes() }
    }

    private fun dump(f: File) {
        val dir = System.getenv("TWIG_DUMP_DIR")?.let { File(it) } ?: return
        dir.mkdirs()
        f.copyTo(File(dir, f.name), overwrite = true)
    }

    @Test
    fun `加密 zip 能被自己读回,内容一字节不差`() {
        val archive = File(out, "enc.zip")
        ArchiveWriter.compress(
            listOf(local(src)), local(out), local(archive),
            ArchiveWriter.Format.ZIP, password = "secret",
        )
        dump(archive)

        assertTrue(zipFs.needsPassword(archive.path))
        zipFs.setPassword(archive.path, "secret")
        assertEquals(
            "hello encrypted world",
            zipFs.read(archive, "src/hello.txt").toString(Charsets.UTF_8),
        )
        assertEquals(
            "名字带空格和中文",
            zipFs.read(archive, "src/中文 目录/说明.txt").toString(Charsets.UTF_8),
        )
        assertArrayEquals(big, zipFs.read(archive, "src/big.bin"))
    }

    @Test
    fun `加密 zip 用错密码读不出来,报的是密码错`() {
        val archive = File(out, "enc2.zip")
        ArchiveWriter.compress(
            listOf(local(src)), local(out), local(archive),
            ArchiveWriter.Format.ZIP, password = "secret",
        )
        assertFalse(zipFs.checkPassword(archive.path, "wrong"))
        assertTrue(zipFs.checkPassword(archive.path, "secret"))

        zipFs.setPassword(archive.path, "wrong")
        val t = runCatching { zipFs.read(archive, "src/hello.txt") }.exceptionOrNull()
        assertTrue("got $t", t is ArchivePasswordException && t.wrong)
    }

    @Test
    fun `不给密码打出来的还是普通 zip`() {
        val archive = File(out, "plain.zip")
        ArchiveWriter.compress(
            listOf(local(src)), local(out), local(archive),
            ArchiveWriter.Format.ZIP, password = null,
        )
        assertFalse(zipFs.needsPassword(archive.path))
        // 空密码等同不加密(对话框里没填就是这种)
        val archive2 = File(out, "plain2.zip")
        ArchiveWriter.compress(
            listOf(local(src)), local(out), local(archive2),
            ArchiveWriter.Format.ZIP, password = "",
        )
        assertFalse(zipFs.needsPassword(archive2.path))
    }

    /**
     * zip64 分支:本地头里要不要给 64 位大小留位置,是**写之前**按 sizeHint 定的
     * (见 [ZipWriter]),所以拿假的大 hint 就能测到那条路径,不必真造 4GB 文件。
     */
    @Test
    fun `sizeHint 超过 4GB 时走 zip64,包仍然合法`() {
        val archive = File(out, "zip64.zip")
        archive.outputStream().use { os ->
            ZipWriter(os, "secret").use { zw ->
                zw.putDir("d", System.currentTimeMillis())
                zw.putNextEntry("d/a.txt", System.currentTimeMillis(), sizeHint = 5L shl 30)
                zw.write("zip64 entry".toByteArray())
                zw.closeEntry()
            }
        }
        dump(archive)
        zipFs.setPassword(archive.path, "secret")
        assertEquals("zip64 entry", zipFs.read(archive, "d/a.txt").toString(Charsets.UTF_8))
    }

    @Test
    fun `不带密码的 ZipWriter 写出的是能通读的普通 zip`() {
        val archive = File(out, "writer-plain.zip")
        archive.outputStream().use { os ->
            ZipWriter(os).use { zw ->
                zw.putNextEntry("a.txt", System.currentTimeMillis(), sizeHint = 5)
                zw.write("plain".toByteArray())
                zw.closeEntry()
            }
        }
        dump(archive)
        assertFalse(zipFs.needsPassword(archive.path))
        assertEquals("plain", zipFs.read(archive, "a.txt").toString(Charsets.UTF_8))
    }

    @Test
    fun `加密 7z 能被自己读回`() {
        val archive = File(out, "enc.7z")
        ArchiveWriter.compress(
            listOf(local(src)), local(out), local(archive),
            ArchiveWriter.Format.SEVEN_Z, password = "secret",
        )
        dump(archive)

        assertTrue(sevenZFs.needsPassword(archive.path))
        assertFalse(sevenZFs.checkPassword(archive.path, "wrong"))
        sevenZFs.setPassword(archive.path, "secret")
        assertEquals(
            "hello encrypted world",
            sevenZFs.read(archive, "src/hello.txt").toString(Charsets.UTF_8),
        )
        assertArrayEquals(big, sevenZFs.read(archive, "src/big.bin"))
    }
}
