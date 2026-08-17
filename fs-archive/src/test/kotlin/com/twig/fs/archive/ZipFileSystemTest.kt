package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipFileSystemTest {

    private lateinit var tmp: File
    private lateinit var archive: File
    private val zfs = ZipFileSystem()

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(zfs)

        tmp = File.createTempFile("twig", "").let { it.delete(); it.mkdirs(); it }
        archive = File(tmp, "a.zip")
        ZipOutputStream(archive.outputStream()).use { z ->
            z.put("hello.txt", "hi")
            z.put("dir/a.txt", "aaa")
            z.put("dir/sub/b.txt", "bbbbb")
        }
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private fun archiveX() =
        XFile(scheme = "file", path = archive.absolutePath, isDir = false)

    @Test
    fun listsRootWithDirsFirst() {
        val root = zfs.rootOf(archiveX())
        val names = zfs.list(root).map { "${it.name}:${it.isDir}" }
        // 目录在前
        assertEquals(listOf("dir:true", "hello.txt:false"), names)
    }

    @Test
    fun listsNestedDirAndReadsFile() {
        val root = zfs.rootOf(archiveX())
        val dir = zfs.list(root).first { it.isDir }
        val children = zfs.list(dir).map { "${it.name}:${it.isDir}" }
        assertEquals(listOf("sub:true", "a.txt:false"), children)

        val helloX = zfs.list(root).first { it.name == "hello.txt" }
        val text = zfs.openInput(helloX).bufferedReader().use { it.readText() }
        assertEquals("hi", text)
        assertEquals(2L, helloX.size)
    }

    @Test
    fun parentOfRootJumpsBackToHostFolder() {
        val root = zfs.rootOf(archiveX())
        val parent = zfs.parentOf(root)!!
        assertEquals("file", parent.scheme)
        assertEquals(tmp.absolutePath, parent.path)
    }

    @Test
    fun extractViaCopyEngine() {
        // 解压 = 用 CopyEngine 把包内条目拷到本地目录,验证抽象层跨系统拷贝
        val root = zfs.rootOf(archiveX())
        val dest = File(tmp, "out").apply { mkdirs() }
        val destX = FsRegistry.of("file").resolve(dest.absolutePath)

        CopyEngine.transfer(zfs.list(root), destX, move = false)

        assertTrue(File(dest, "hello.txt").exists())
        assertEquals("hi", File(dest, "hello.txt").readText())
        // 目录递归
        assertEquals("aaa", File(dest, "dir/a.txt").readText())
        assertEquals("bbbbb", File(dest, "dir/sub/b.txt").readText())
    }

    @Test
    fun autoDetectsGbkNames() {
        // 用 GBK 写入中文名条目(模拟老压缩包)
        val gbk = File(tmp, "gbk.zip")
        ZipOutputStream(gbk.outputStream(), Charset.forName("GBK")).use { z ->
            z.put("报告.txt", "neirong")
        }
        val gbkX = XFile("file", gbk.absolutePath, false)
        val root = zfs.rootOf(gbkX)
        val names = zfs.list(root).map { it.name }
        assertEquals(listOf("报告.txt"), names) // 自动识别为 GBK,名字不乱码
        val text = zfs.openInput(zfs.list(root).first()).bufferedReader().use { it.readText() }
        assertEquals("neirong", text)
    }

    @Test
    fun writeCopyIntoZip() {
        // 把本地文件复制进 zip(走 createFile + openOutput 的整包重写)
        val local = File(tmp, "new.txt").apply { writeText("inserted") }
        val localX = FsRegistry.of("file").resolve(local.absolutePath)
        CopyEngine.transfer(listOf(localX), zfs.rootOf(archiveX()), move = false)

        val root = zfs.rootOf(archiveX())
        val names = zfs.list(root).map { it.name }.toSet()
        assertTrue("new.txt" in names)
        assertTrue("hello.txt" in names) // 原条目仍在
        val inserted = zfs.openInput(zfs.list(root).first { it.name == "new.txt" })
            .bufferedReader().use { it.readText() }
        assertEquals("inserted", inserted)
    }

    @Test
    fun deleteMkdirRenameInZip() {
        val root = zfs.rootOf(archiveX())

        // 删除目录(递归)
        val dir = zfs.list(root).first { it.isDir }
        zfs.delete(dir)
        assertTrue(zfs.list(zfs.rootOf(archiveX())).none { it.name == "dir" })

        // 新建目录
        zfs.mkdir(zfs.rootOf(archiveX()), "newdir")
        assertTrue(zfs.list(zfs.rootOf(archiveX())).any { it.name == "newdir" && it.isDir })

        // 重命名文件
        val hello = zfs.list(zfs.rootOf(archiveX())).first { it.name == "hello.txt" }
        zfs.rename(hello, "renamed.txt")
        val names = zfs.list(zfs.rootOf(archiveX())).map { it.name }.toSet()
        assertTrue("renamed.txt" in names)
        assertTrue("hello.txt" !in names)
    }
}
