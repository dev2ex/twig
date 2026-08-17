package com.twig.fs.local

import com.twig.core.FsException
import com.twig.core.XFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 本地文件系统。重点在**不能悄悄吃掉用户文件**的那几条:
 * `File.renameTo` 底下是 POSIX `rename(2)`,默认行为就是原子替换已存在的目标,
 * 照着写下来"改个名字"会无声删掉另一个文件。
 */
class LocalFileSystemTest {

    private val fs = LocalFileSystem()
    private lateinit var tmp: File

    @Before
    fun setup() {
        tmp = File.createTempFile("twiglocal", "").let { it.delete(); it.mkdirs(); it }
    }

    private fun x(f: File) = XFile("file", f.absolutePath, isDir = f.isDirectory)

    @Test
    fun renameWorks() {
        val a = File(tmp, "a.txt").apply { writeText("hi") }
        val renamed = fs.rename(x(a), "b.txt")

        assertFalse(a.exists())
        assertEquals(File(tmp, "b.txt").absolutePath, renamed.path)
        assertEquals("hi", File(tmp, "b.txt").readText())
    }

    /** 回归:改名到已存在的名字必须报错,不能把那个文件替换掉。 */
    @Test
    fun renameRefusesToOverwrite() {
        val a = File(tmp, "a.txt").apply { writeText("源") }
        val b = File(tmp, "b.txt")
        val victim = "别把我覆盖了".toByteArray()
        b.writeBytes(victim)

        val e = runCatching { fs.rename(x(a), "b.txt") }.exceptionOrNull()

        assertTrue("应抛 FsException,实际: $e", e is FsException)
        assertTrue(a.exists())                    // 源还在
        assertArrayEquals(victim, b.readBytes())  // 目标原封不动
    }

    /** 同上:CopyEngine 的就地移动快路径撞上同名目标时要让路,而不是覆盖。 */
    @Test
    fun moveWithinRefusesToOverwrite() {
        val dst = File(tmp, "dst").apply { mkdirs() }
        val src = File(tmp, "a.txt").apply { writeText("源") }
        val victim = "别把我覆盖了".toByteArray()
        File(dst, "a.txt").writeBytes(victim)

        // 返回 false = 不支持就地移动,交回 CopyEngine 走"拷贝 + 删源"(那条路上有冲突询问)
        assertFalse(fs.moveWithin(x(src), x(dst), "a.txt"))
        assertTrue(src.exists())
        assertArrayEquals(victim, File(dst, "a.txt").readBytes())
    }

    /**
     * 回归:删目录时**不能跟着符号链接进去删目标里的内容**。
     * `File.isDirectory`/`listFiles()` 都跟随链接,不判一下就会把链接指向的那个目录清空。
     */
    @Test
    fun deleteDoesNotFollowSymlinks() {
        val outside = File(tmp, "outside").apply { mkdirs() }
        val treasure = File(outside, "treasure.txt").apply { writeText("别删我") }

        val victim = File(tmp, "victim").apply { mkdirs() }
        File(victim, "own.txt").writeText("这个该删")
        val link = File(victim, "link")
        val made = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        org.junit.Assume.assumeTrue("当前环境建不了符号链接,跳过", made)

        fs.delete(x(victim))

        assertFalse(victim.exists())          // 目录本身删掉了
        assertTrue(outside.isDirectory)       // 链接指向的目录还在
        assertEquals("别删我", treasure.readText()) // 里面的东西一个没少
    }

    @Test
    fun moveWithinMovesWhenFree() {
        val dst = File(tmp, "dst").apply { mkdirs() }
        val src = File(tmp, "a.txt").apply { writeText("hi") }

        assertTrue(fs.moveWithin(x(src), x(dst), "a.txt"))
        assertFalse(src.exists())
        assertEquals("hi", File(dst, "a.txt").readText())
    }

    /**
     * 写入钩子(:app 拿它通知系统媒体库)。判据是**流关闭之后才报,且只报一次**——
     * 打开时报的话,媒体库扫到的是个 0 字节的空壳。
     */
    @Test
    fun changeHookFiresAfterStreamClosed() {
        val seen = ArrayList<String>()
        LocalFileSystem.changed = { seen += it }
        try {
            val f = File(tmp, "a.txt")
            val out = fs.openOutput(x(f), append = false)
            out.write("hi".toByteArray())
            assertTrue(seen.isEmpty()) // 还没关流,不该报
            out.close()
            out.close() // 重复关闭不该重复报
            assertEquals(listOf(f.absolutePath), seen)
            assertEquals("hi", f.readText())

            seen.clear()
            fs.rename(x(f), "b.txt") // 改名要报两条:旧路径撤下、新路径收录
            assertEquals(listOf(f.absolutePath, File(tmp, "b.txt").absolutePath), seen)

            seen.clear()
            fs.delete(x(File(tmp, "b.txt")))
            assertEquals(listOf(File(tmp, "b.txt").absolutePath), seen)
        } finally {
            LocalFileSystem.changed = null
        }
    }

    @Test
    fun setModifiedTimeWritesBackTimestamp() {
        val f = File(tmp, "a.txt").apply { writeText("hi") }
        // 秒级取整:File.setLastModified 在部分文件系统(如老 ext 变体)按秒粒度存储,
        // 用一个整秒的值断言,避免因为底层截断毫秒而误判失败
        val target = 1_700_000_000_000L

        assertTrue(fs.setModifiedTime(x(f), target))
        assertEquals(target, f.lastModified())
    }
}
