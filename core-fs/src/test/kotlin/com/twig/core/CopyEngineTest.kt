package com.twig.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * [CopyEngine] 的行为契约。
 *
 * 这是全项目**唯一会删用户源文件**的组件(`transfer(move = true)`),而它的冲突决策
 * 矩阵(覆盖/跳过/重命名/取消 × 文件/目录 × 同源/跨源)原先一个用例都没有。
 * 这里的每条断言对应一种"弄错了就丢数据"的情况:
 *
 * - 跳过/取消/中止之后**绝不能删源**(复制没成,源就是唯一一份);
 * - 重命名不能覆盖已有条目;
 * - 目录同名要合并而不是询问。
 *
 * 用内存文件系统跑,不碰真实磁盘。
 */
class CopyEngineTest {

    /** 一个支持读写、有真实目录概念的内存文件系统。 */
    private class MemFs(override val scheme: String) : FileSystem {
        override val displayName get() = scheme

        val files = LinkedHashMap<String, ByteArray>()
        val dirs = linkedSetOf("/")
        val mtimes = HashMap<String, Long>()

        /** 打开"同一文件系统内就地移动"的快路径(默认关,好走通用的拷贝+删源)。 */
        var moveWithinSupported = false
        var moveWithinCalls = 0

        /** 模拟"这个文件系统不支持设置时间"(如 WebDAV/SMB),校验 CopyEngine 不因此报错。 */
        var setModifiedTimeSupported = true
        var setModifiedTimeCalls = 0

        private fun join(dir: String, name: String) =
            if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

        override fun root() = XFile(scheme, "/", isDir = true)

        override fun resolve(path: String): XFile = when {
            path in dirs -> XFile(scheme, path, isDir = true)
            files.containsKey(path) -> XFile(
                scheme, path, isDir = false,
                size = files.getValue(path).size.toLong(),
                lastModified = mtimes[path] ?: 0L,
            )
            else -> throw FsException("不存在: $path")
        }

        override fun list(dir: XFile): List<XFile> {
            val prefix = if (dir.path.endsWith("/")) dir.path else "${dir.path}/"
            return (dirs + files.keys)
                .filter { it != dir.path && it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
                .map { resolve(it) }
        }

        override fun openInput(file: XFile): InputStream =
            ByteArrayInputStream(files[file.path] ?: throw FsException("不存在: ${file.path}"))

        override fun openOutput(file: XFile, append: Boolean): OutputStream =
            object : ByteArrayOutputStream() {
                override fun close() {
                    files[file.path] = if (append) (files[file.path] ?: ByteArray(0)) + toByteArray()
                    else toByteArray()
                }
            }

        override fun mkdir(parent: XFile, name: String): XFile {
            val p = join(parent.path, name)
            dirs += p
            return XFile(scheme, p, isDir = true)
        }

        override fun delete(file: XFile) {
            val prefix = "${file.path}/"
            files.keys.removeAll { it == file.path || it.startsWith(prefix) }
            dirs.removeAll { it == file.path || it.startsWith(prefix) }
        }

        override fun rename(file: XFile, newName: String): XFile {
            val to = join(file.parentPath, newName)
            if (exists(XFile(scheme, to, isDir = false))) throw FsException("目标已存在: $newName")
            files.remove(file.path)?.let { files[to] = it }
            return XFile(scheme, to, file.isDir)
        }

        override fun exists(file: XFile) = file.path in dirs || files.containsKey(file.path)

        override fun setModifiedTime(file: XFile, time: Long): Boolean {
            setModifiedTimeCalls++
            if (!setModifiedTimeSupported) return false
            mtimes[file.path] = time
            return true
        }

        override fun moveWithin(src: XFile, destDir: XFile, newName: String): Boolean {
            if (!moveWithinSupported) return false
            moveWithinCalls++
            val to = join(destDir.path, newName)
            if (src.isDir) {
                val prefix = "${src.path}/"
                for (k in files.keys.toList()) {
                    if (k.startsWith(prefix)) files[to + k.removePrefix(src.path)] = files.remove(k)!!
                }
                for (d in dirs.toList()) {
                    if (d == src.path || d.startsWith(prefix)) {
                        dirs.remove(d); dirs += to + d.removePrefix(src.path)
                    }
                }
            } else {
                files.remove(src.path)?.let { files[to] = it }
            }
            return true
        }

        fun put(path: String, text: String, time: Long = 0L) {
            files[path] = text.toByteArray()
            if (time > 0) mtimes[path] = time
            var p = path.substringBeforeLast('/', "")
            while (p.isNotEmpty()) { dirs += p; p = p.substringBeforeLast('/', "") }
        }

        fun text(path: String) = files[path]?.decodeToString()
    }

    private lateinit var src: MemFs
    private lateinit var dst: MemFs

    @Before
    fun setup() {
        src = MemFs("cpsrc")
        dst = MemFs("cpdst")
        FsRegistry.register(src)
        FsRegistry.register(dst)
    }

    private fun srcFile(path: String) = src.resolve(path)
    private fun dstRoot() = dst.root()

    // ---- 基本 ----

    @Test
    fun copySingleFile() {
        src.put("/a.txt", "hello")
        CopyEngine.transfer(listOf(srcFile("/a.txt")), dstRoot(), move = false)

        assertEquals("hello", dst.text("/a.txt"))
        assertEquals("hello", src.text("/a.txt")) // 复制不动源
    }

    // ---- 修改时间(见 FileSystem.setModifiedTime) ----

    @Test
    fun `复制后把源的修改时间写回目标`() {
        src.put("/a.txt", "hello", time = 1_700_000_000_000L)
        CopyEngine.transfer(listOf(srcFile("/a.txt")), dstRoot(), move = false)

        assertEquals(1_700_000_000_000L, dst.resolve("/a.txt").lastModified)
    }

    @Test
    fun `目标不支持设置时间不影响复制成功`() {
        dst.setModifiedTimeSupported = false
        src.put("/a.txt", "hello", time = 1_700_000_000_000L)
        CopyEngine.transfer(listOf(srcFile("/a.txt")), dstRoot(), move = false)

        // 复制本身照样成功,只是目标时间没被改——不该因为这一步失败就报错/回滚整个复制
        assertEquals("hello", dst.text("/a.txt"))
        assertEquals(1, dst.setModifiedTimeCalls)
    }

    @Test
    fun `目录里的每个文件都单独写回时间`() {
        src.put("/d/a.txt", "a", time = 1_000L)
        src.put("/d/b.txt", "b", time = 2_000L)
        CopyEngine.transfer(listOf(srcFile("/d")), dstRoot(), move = false)

        assertEquals(1_000L, dst.resolve("/d/a.txt").lastModified)
        assertEquals(2_000L, dst.resolve("/d/b.txt").lastModified)
    }

    @Test
    fun copyDirectoryTreeRecursively() {
        src.put("/d/x.txt", "x")
        src.put("/d/sub/y.txt", "y")
        CopyEngine.transfer(listOf(srcFile("/d")), dstRoot(), move = false)

        assertEquals("x", dst.text("/d/x.txt"))
        assertEquals("y", dst.text("/d/sub/y.txt"))
        assertTrue("/d/sub" in dst.dirs)
    }

    @Test
    fun moveDeletesSourceAfterFullCopy() {
        src.put("/a.txt", "hello")
        CopyEngine.transfer(listOf(srcFile("/a.txt")), dstRoot(), move = true)

        assertEquals("hello", dst.text("/a.txt"))
        assertFalse("移动成功后源必须删掉", src.files.containsKey("/a.txt"))
    }

    /** 同一文件系统内的移动优先走 moveWithin 快路径,不做整份拷贝。 */
    @Test
    fun moveWithinSameFsUsesFastPath() {
        src.moveWithinSupported = true
        src.put("/a.txt", "hello")
        src.dirs += "/target"

        CopyEngine.transfer(listOf(srcFile("/a.txt")), src.resolve("/target"), move = true)

        assertEquals(1, src.moveWithinCalls)
        assertEquals("hello", src.text("/target/a.txt"))
        assertFalse(src.files.containsKey("/a.txt"))
    }

    // ---- 冲突决策 ----

    @Test
    fun conflictOverwriteReplacesTarget() {
        src.put("/a.txt", "new")
        dst.put("/a.txt", "old")

        CopyEngine.transfer(
            listOf(srcFile("/a.txt")), dstRoot(), move = false,
            resolver = { _, _ -> CopyEngine.Decision.OVERWRITE },
        )
        assertEquals("new", dst.text("/a.txt"))
    }

    /** ★ 跳过时**不能删源**:什么都没复制过去,源就是唯一一份。 */
    @Test
    fun conflictSkipKeepsBothSides() {
        src.put("/a.txt", "new")
        dst.put("/a.txt", "old")

        CopyEngine.transfer(
            listOf(srcFile("/a.txt")), dstRoot(), move = true,
            resolver = { _, _ -> CopyEngine.Decision.SKIP },
        )
        assertEquals("old", dst.text("/a.txt")) // 目标没被动
        assertEquals("new", src.text("/a.txt")) // 源也没被删
    }

    @Test
    fun conflictRenameKeepsExistingAndAddsNumbered() {
        src.put("/a.txt", "new")
        dst.put("/a.txt", "old")

        CopyEngine.transfer(
            listOf(srcFile("/a.txt")), dstRoot(), move = false,
            resolver = { _, _ -> CopyEngine.Decision.RENAME },
        )
        assertEquals("old", dst.text("/a.txt"))
        assertEquals("new", dst.text("/a (1).txt"))
    }

    /** 连续重命名要接着往下编号,不能覆盖上一个 (1)。 */
    @Test
    fun renameNumbersIncrement() {
        src.put("/a.txt", "new")
        dst.put("/a.txt", "old")
        dst.put("/a (1).txt", "old1")

        CopyEngine.transfer(
            listOf(srcFile("/a.txt")), dstRoot(), move = false,
            resolver = { _, _ -> CopyEngine.Decision.RENAME },
        )
        assertEquals("old1", dst.text("/a (1).txt"))
        assertEquals("new", dst.text("/a (2).txt"))
    }

    /** ★ 冲突框选"取消"(resolver 返回 null):整个任务中止,已排队的后续项不再处理,源全留着。 */
    @Test
    fun conflictAbortStopsTaskAndKeepsSources() {
        src.put("/a.txt", "A")
        src.put("/b.txt", "B")
        dst.put("/a.txt", "old")

        CopyEngine.transfer(
            listOf(srcFile("/a.txt"), srcFile("/b.txt")), dstRoot(), move = true,
            resolver = { _, _ -> null },
        )
        assertEquals("old", dst.text("/a.txt"))
        assertFalse("中止后不该再复制后续条目", dst.files.containsKey("/b.txt"))
        assertTrue(src.files.containsKey("/a.txt"))
        assertTrue(src.files.containsKey("/b.txt"))
    }

    /** 目录同名直接合并,不询问;两边的文件都在。 */
    @Test
    fun sameNameDirectoriesMergeWithoutPrompting() {
        src.put("/d/new.txt", "n")
        dst.put("/d/old.txt", "o")
        var asked = 0

        CopyEngine.transfer(
            listOf(srcFile("/d")), dstRoot(), move = false,
            resolver = { _, _ -> asked++; CopyEngine.Decision.OVERWRITE },
        )
        assertEquals("目录同名不该触发冲突询问", 0, asked)
        assertEquals("o", dst.text("/d/old.txt"))
        assertEquals("n", dst.text("/d/new.txt"))
    }

    // ---- 取消 ----

    /** ★ 取消后不能删源。 */
    @Test
    fun cancelKeepsSource() {
        src.put("/a.txt", "hello")
        CopyEngine.transfer(
            listOf(srcFile("/a.txt")), dstRoot(), move = true,
            cancelled = { true },
        )
        assertTrue("取消后源必须留着", src.files.containsKey("/a.txt"))
    }

    /**
     * ★ 取消后目标那份半成品要删掉——留着就是个内容不全的坏文件。
     *
     * 注意取消的时机:`cancelled = { true }` 在 copyRecursive 开头就返回了,目标压根
     * 没建过,测不到这条。要在**第一个文件已经开搬之后**才取消,才走到 pump 里去。
     */
    @Test
    fun cancelRemovesPartialTarget() {
        src.put("/a.txt", "hello")
        var started = false
        CopyEngine.transfer(
            listOf(srcFile("/a.txt")), dstRoot(), move = false,
            listener = object : CopyEngine.ProgressListener {
                override fun onFile(file: XFile) { started = true }
            },
            cancelled = { started },
        )
        assertFalse("取消后目标不能留半成品", dst.files.containsKey("/a.txt"))
    }

    /** 覆盖模式下取消:原文件已被 openOutput 截断,同样不该留个坏文件。 */
    @Test
    fun cancelRemovesTargetEvenWhenOverwriting() {
        src.put("/a.txt", "hello")
        dst.put("/a.txt", "old")
        var started = false
        CopyEngine.transfer(
            listOf(srcFile("/a.txt")), dstRoot(), move = false,
            listener = object : CopyEngine.ProgressListener {
                override fun onFile(file: XFile) { started = true }
            },
            cancelled = { started },
            resolver = { _, _ -> CopyEngine.Decision.OVERWRITE },
        )
        assertFalse("覆盖时取消同样不留半成品", dst.files.containsKey("/a.txt"))
    }

    // ---- 统计与进度 ----

    @Test
    fun planCountsRecursively() {
        src.put("/d/x.txt", "12345")
        src.put("/d/sub/y.txt", "123")
        val plan = CopyEngine.plan(listOf(srcFile("/d")))

        assertEquals(2, plan.files)
        assertEquals(2, plan.dirs) // /d 与 /d/sub
        assertEquals(8L, plan.bytes)
    }

    @Test
    fun listenerReportsEveryItem() {
        src.put("/d/x.txt", "x")
        src.put("/d/sub/y.txt", "y")
        var files = 0
        var dirs = 0
        CopyEngine.transfer(
            listOf(srcFile("/d")), dstRoot(), move = false,
            listener = object : CopyEngine.ProgressListener {
                override fun onItemDone(isDir: Boolean) { if (isDir) dirs++ else files++ }
            },
        )
        assertEquals(2, files)
        assertEquals(2, dirs)
    }

    // ---- 流水线 ----

    /** pipe 要能原样搬运(含跨块边界的大数据),并在取消时返回 false。 */
    @Test
    fun pipeCopiesExactBytesAndHonoursCancel() {
        val data = ByteArray(3 shl 20) { (it % 251).toByte() } // 3MB,跨多个 1MB 缓冲块
        val out = ByteArrayOutputStream()
        assertTrue(CopyEngine.pipe(ByteArrayInputStream(data), out, { false }))
        assertArrayEquals(data, out.toByteArray())

        val out2 = ByteArrayOutputStream()
        assertFalse(CopyEngine.pipe(ByteArrayInputStream(data), out2, { true }))
    }
}
