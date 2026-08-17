package com.twig.fs.archive

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 往已有 zip 里加文件走的是**追加**:新条目接在包尾,旧条目一个字节不动。
 *
 * 这里最要紧的一条断言是「原包前 N 字节逐字节相等」——它直接证明旧数据没被重写,
 * 而不是去卡耗时(那种断言在 CI 上必然时好时坏)。老实现每加一个文件都要把整包
 * 解压再重压一遍,而 `CopyEngine` 是一个文件一次 `openOutput`,于是往大包里拖 N 个
 * 文件就是 N 遍全包解压+压缩。
 */
class ZipAppendTest {

    private lateinit var tmp: File
    private lateinit var archive: File
    private val zfs = ZipFileSystem()

    /** 不可压缩的随机数据:压缩率骗不了人,重写与否在字节上看得见。 */
    private val bulk = ByteArray(2 * 1024 * 1024).also { java.util.Random(7).nextBytes(it) }

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(zfs)
        tmp = File.createTempFile("twigappend", "").let { it.delete(); it.mkdirs(); it }
        archive = File(tmp, "a.zip")
        ZipOutputStream(archive.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("old.txt"))
            z.write("original".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("dir/bulk.bin"))
            z.write(bulk)
            z.closeEntry()
        }
    }

    private fun archiveX() = XFile("file", archive.absolutePath, isDir = false)

    private fun add(inner: String, content: ByteArray) {
        val target = XFile(ZipFileSystem.SCHEME, "${archive.absolutePath}${ArchiveFileSystem.SEP}$inner", isDir = false)
        zfs.openOutput(target, append = false).use { it.write(content) }
    }

    private fun read(inner: String): ByteArray {
        val root = zfs.rootOf(archiveX())
        var cur = root
        val parts = inner.split('/')
        for (p in parts.dropLast(1)) cur = zfs.list(cur).first { it.name == p && it.isDir }
        return zfs.openInput(zfs.list(cur).first { it.name == parts.last() }).use { it.readBytes() }
    }

    private fun names(): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: XFile, prefix: String) {
            for (c in zfs.list(dir)) {
                if (c.isDir) walk(c, "$prefix${c.name}/") else out.add("$prefix${c.name}")
            }
        }
        walk(zfs.rootOf(archiveX()), "")
        return out.sorted()
    }

    @Test
    fun `加新文件时原有字节原封不动`() {
        val before = archive.readBytes()
        add("added.txt", "brand new".toByteArray())

        val after = archive.readBytes()
        assertTrue("追加只会让包变长", after.size > before.size)
        assertArrayEquals(
            "旧条目所在的那一段必须逐字节相同(动了就说明又整包重写了一遍)",
            before,
            after.copyOfRange(0, before.size),
        )
    }

    @Test
    fun `追加后新旧条目都读得出来`() {
        add("added.txt", "brand new".toByteArray())

        assertEquals(listOf("added.txt", "dir/bulk.bin", "old.txt"), names())
        assertEquals("original", read("old.txt").toString(Charsets.UTF_8))
        assertEquals("brand new", read("added.txt").toString(Charsets.UTF_8))
        assertArrayEquals(bulk, read("dir/bulk.bin"))
    }

    @Test
    fun `连着追加多次,每次都只往后接`() {
        val sizes = ArrayList<Int>()
        for (i in 1..5) {
            val before = archive.readBytes()
            add("f$i.txt", "content $i".toByteArray())
            sizes.add(archive.readBytes().size)
            assertArrayEquals(
                "第 $i 次追加动了前面的字节",
                before,
                archive.readBytes().copyOfRange(0, before.size),
            )
        }
        assertEquals(sizes.sorted(), sizes) // 单调增长
        assertEquals(
            listOf("dir/bulk.bin", "f1.txt", "f2.txt", "f3.txt", "f4.txt", "f5.txt", "old.txt"),
            names(),
        )
        for (i in 1..5) assertEquals("content $i", read("f$i.txt").toString(Charsets.UTF_8))
        assertArrayEquals(bulk, read("dir/bulk.bin"))
    }

    @Test
    fun `包里已有同名条目时退回整包重写,内容被覆盖且不留两份`() {
        add("old.txt", "replaced".toByteArray())

        assertEquals(listOf("dir/bulk.bin", "old.txt"), names())
        assertEquals("replaced", read("old.txt").toString(Charsets.UTF_8))
        assertArrayEquals(bulk, read("dir/bulk.bin"))
    }

    @Test
    fun `子目录里的新条目也走追加`() {
        val before = archive.readBytes()
        add("dir/added.bin", bulk)

        assertArrayEquals(before, archive.readBytes().copyOfRange(0, before.size))
        assertEquals(listOf("dir/added.bin", "dir/bulk.bin", "old.txt"), names())
        assertArrayEquals(bulk, read("dir/added.bin"))
    }

    @Test
    fun `新建目录条目后仍能继续追加文件`() {
        // mkdir 走整包重写,之后 EOCD 位置变了,追加得照样认得出来
        zfs.mkdir(zfs.rootOf(archiveX()), "fresh")
        add("fresh/x.txt", "inside".toByteArray())

        assertTrue(names().contains("fresh/x.txt"))
        assertEquals("inside", read("fresh/x.txt").toString(Charsets.UTF_8))
        assertEquals("original", read("old.txt").toString(Charsets.UTF_8))
    }

    /** 追加后包尾结构要经得起别家解析器验;这里用 java.util.zip 当第二双眼睛。 */
    @Test
    fun `追加出来的包 JDK 自己也读得懂`() {
        add("added.txt", "brand new".toByteArray())
        add("added2.txt", "another".toByteArray())

        java.util.zip.ZipFile(archive).use { zf ->
            val got = zf.entries().asSequence().map { it.name }.toList().sorted()
            assertEquals(listOf("added.txt", "added2.txt", "dir/bulk.bin", "old.txt"), got)
            val e = zf.getEntry("added.txt")
            assertEquals("brand new", zf.getInputStream(e).readBytes().toString(Charsets.UTF_8))
            assertArrayEquals(bulk, zf.getInputStream(zf.getEntry("dir/bulk.bin")).readBytes())
        }
    }

    /**
     * 界面上「展开 zip → 对侧点复制」最终走的就是 `CopyEngine.transfer(源, 包根)`。
     * 上面那些用例直接调 [ZipFileSystem.openOutput],这条把真实入口串起来验一遍。
     */
    @Test
    fun `经 CopyEngine 复制进包根,走的也是追加`() {
        val src = File(tmp, "outside.txt").apply { writeText("from outside") }
        val before = archive.readBytes()

        com.twig.core.CopyEngine.transfer(
            listOf(XFile("file", src.absolutePath, isDir = false, size = src.length())),
            zfs.rootOf(archiveX()),
            move = false,
        )

        assertArrayEquals(
            "复制进包不该把旧条目重写一遍",
            before,
            archive.readBytes().copyOfRange(0, before.size),
        )
        assertEquals(listOf("dir/bulk.bin", "old.txt", "outside.txt"), names())
        assertEquals("from outside", read("outside.txt").toString(Charsets.UTF_8))
        assertArrayEquals(bulk, read("dir/bulk.bin"))
    }

    @Test
    fun `删除仍然整包重写,顺带把追加留下的垃圾清掉`() {
        add("added.txt", "brand new".toByteArray())
        val afterAppend = archive.length()

        val root = zfs.rootOf(archiveX())
        zfs.delete(zfs.list(root).first { it.name == "added.txt" })

        assertEquals(listOf("dir/bulk.bin", "old.txt"), names())
        assertTrue("整包重写后不该还留着旧中央目录那份垃圾", archive.length() < afterAppend)
        assertArrayEquals(bulk, read("dir/bulk.bin"))
    }
}
