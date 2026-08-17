package com.twig.fs.archive

import com.twig.core.CopyEngine
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

/** 打包器:zip / 7z 写出的包能被对应的只读实现原样读回,move 模式删源。 */
class ArchiveWriterTest {

    private lateinit var tmp: File
    private lateinit var src: File
    private lateinit var out: File
    private val zipFs = ZipFileSystem()
    private val sevenZFs = SevenZFileSystem()

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(zipFs)
        FsRegistry.register(sevenZFs)

        tmp = File.createTempFile("twigpack", "").let { it.delete(); it.mkdirs(); it }
        src = File(tmp, "src").apply { mkdirs() }
        File(src, "hello.txt").writeText("hi")
        File(src, "dir").mkdirs()
        File(src, "dir/a.txt").writeText("aaa")
        File(src, "empty").mkdirs()
        out = File(tmp, "out").apply { mkdirs() }
    }

    /** 目标归档还不存在,不能走 resolve();直接拼(与 createFile 得到的一样)。 */
    private fun local(f: File) = XFile("file", f.absolutePath, isDir = f.isDirectory)

    /** 归档内 "路径:是否目录" 的排序清单(递归)。 */
    private fun tree(fs: ArchiveFileSystem, archive: File): List<String> {
        val list = ArrayList<String>()
        fun walk(dir: XFile) {
            for (c in fs.list(dir).sortedBy { it.name }) {
                list.add("${c.path.substringAfter("!/")}:${c.isDir}")
                if (c.isDir) walk(c)
            }
        }
        walk(fs.rootOf(XFile("file", archive.absolutePath, false)))
        return list
    }

    @Test
    fun zipRoundTrip() {
        val archive = File(out, "src.zip")
        ArchiveWriter.compress(listOf(local(src)), local(out), local(archive), ArchiveWriter.Format.ZIP)

        assertTrue(archive.length() > 0)
        assertEquals(
            listOf("src:true", "src/dir:true", "src/dir/a.txt:false", "src/empty:true", "src/hello.txt:false"),
            tree(zipFs, archive),
        )
        val root = zipFs.rootOf(XFile("file", archive.absolutePath, false))
        val hello = zipFs.resolve("${archive.absolutePath}!/src/hello.txt")
        assertEquals("hi", zipFs.openInput(hello).bufferedReader().use { it.readText() })
        assertEquals(1, zipFs.list(root).size)
    }

    @Test
    fun sevenZRoundTrip() {
        val archive = File(out, "src.7z")
        ArchiveWriter.compress(listOf(local(src)), local(out), local(archive), ArchiveWriter.Format.SEVEN_Z)

        assertTrue(archive.length() > 0)
        assertEquals(
            listOf("src:true", "src/dir:true", "src/dir/a.txt:false", "src/empty:true", "src/hello.txt:false"),
            tree(sevenZFs, archive),
        )
        val a = sevenZFs.resolve("${archive.absolutePath}!/src/dir/a.txt")
        assertEquals("aaa", sevenZFs.openInput(a).bufferedReader().use { it.readText() })
    }

    /** 多项(平铺在归档根)+ 进度回调 + 移动模式删源。 */
    @Test
    fun multipleItemsAndMove() {
        val items = listOf(local(File(src, "hello.txt")), local(File(src, "dir")))
        val archive = File(out, "多项.zip")
        var files = 0
        var dirs = 0
        val listener = object : CopyEngine.ProgressListener {
            override fun onItemDone(isDir: Boolean) { if (isDir) dirs++ else files++ }
        }
        ArchiveWriter.compress(items, local(out), local(archive), ArchiveWriter.Format.ZIP, listener)

        assertEquals(listOf("dir:true", "dir/a.txt:false", "hello.txt:false"), tree(zipFs, archive))
        assertEquals(2, files) // hello.txt + dir/a.txt
        assertEquals(1, dirs)

        items.forEach { FsRegistry.of(it).delete(it) }
        assertFalse(File(src, "hello.txt").exists())
        assertFalse(File(src, "dir").exists())
    }

    /** 取消:抛 Cancelled,半成品归档不留下。 */
    @Test
    fun cancelDeletesPartialArchive() {
        val big = File(src, "big.bin")
        big.writeBytes(ByteArray(4 shl 20))
        val archive = File(out, "cancel.zip")
        val result = runCatching {
            ArchiveWriter.compress(
                listOf(local(src)), local(out), local(archive), ArchiveWriter.Format.ZIP,
                cancelled = { true },
            )
        }
        assertTrue(result.exceptionOrNull() is ArchiveWriter.Cancelled)
        assertFalse(archive.exists())
        assertFalse(File(out, "cancel.zip.twigpart").exists()) // 临时包也要清掉
    }

    /**
     * 回归:**压到一个已存在的同名归档上,中途取消不能动那个旧包**。
     * 老实现直接往目标上写、失败时 delete(target),于是用户点一下"取消"就把原来的
     * backup.zip 删了——新内容没写成,旧内容也没了。
     */
    @Test
    fun cancelKeepsExistingArchiveIntact() {
        File(src, "big.bin").writeBytes(ByteArray(4 shl 20))
        val archive = File(out, "keep.zip")
        val original = "假装这是用户原来的包".toByteArray()
        archive.writeBytes(original)

        val result = runCatching {
            ArchiveWriter.compress(
                listOf(local(src)), local(out), local(archive), ArchiveWriter.Format.ZIP,
                cancelled = { true },
            )
        }
        assertTrue(result.exceptionOrNull() is ArchiveWriter.Cancelled)
        assertTrue(archive.exists())
        assertArrayEquals(original, archive.readBytes())
    }

    /** 覆盖已有归档:成功时才顶替,内容换成新的。 */
    @Test
    fun successReplacesExistingArchive() {
        val archive = File(out, "replace.zip")
        archive.writeBytes("旧内容".toByteArray())

        ArchiveWriter.compress(listOf(local(src)), local(out), local(archive), ArchiveWriter.Format.ZIP)

        assertEquals(
            listOf("src:true", "src/dir:true", "src/dir/a.txt:false", "src/empty:true", "src/hello.txt:false"),
            tree(zipFs, archive),
        )
        assertFalse(File(out, "replace.zip.twigpart").exists())
    }
}
