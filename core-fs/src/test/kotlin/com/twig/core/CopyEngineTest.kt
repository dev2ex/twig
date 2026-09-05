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
 * The behavior contract of [CopyEngine].
 *
 * This is the **only component in the whole project that can delete the user's source
 * files** (`transfer(move = true)`), and its conflict-decision matrix (overwrite/skip/
 * rename/cancel x file/directory x same-source/cross-source) originally had zero test
 * cases. Every assertion here corresponds to a "get this wrong and you lose data" case:
 *
 * - after skip/cancel/abort the source must **never** be deleted (the copy did not
 *   succeed, so the source is the only copy);
 * - rename must not overwrite an existing entry;
 * - a same-name directory must be merged, not prompted for.
 *
 * Runs on an in-memory filesystem, never touches real disk.
 */
class CopyEngineTest {

    /** An in-memory filesystem that supports read/write and has a real notion of directories. */
    private class MemFs(override val scheme: String) : FileSystem {
        override val displayName get() = scheme

        val files = LinkedHashMap<String, ByteArray>()
        val dirs = linkedSetOf("/")
        val mtimes = HashMap<String, Long>()

        /** Toggles the "in-place move within the same filesystem" fast path (off by default, so the generic copy+delete-source path runs). */
        var moveWithinSupported = false
        var moveWithinCalls = 0

        /** Simulates "this filesystem does not support setting timestamps" (e.g. WebDAV/SMB), to verify CopyEngine does not error out because of it. */
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
            else -> throw FsException("does not exist: $path")
        }

        override fun list(dir: XFile): List<XFile> {
            val prefix = if (dir.path.endsWith("/")) dir.path else "${dir.path}/"
            return (dirs + files.keys)
                .filter { it != dir.path && it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
                .map { resolve(it) }
        }

        override fun openInput(file: XFile): InputStream =
            ByteArrayInputStream(files[file.path] ?: throw FsException("does not exist: ${file.path}"))

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
            if (exists(XFile(scheme, to, isDir = false))) throw FsException("target already exists: $newName")
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

    // ---- Basics ----

    @Test
    fun copySingleFile() {
        src.put("/a.txt", "hello")
        CopyEngine.transfer(listOf(srcFile("/a.txt")), dstRoot(), move = false)

        assertEquals("hello", dst.text("/a.txt"))
        assertEquals("hello", src.text("/a.txt")) // copy does not touch the source
    }

    // ---- Modification time (see FileSystem.setModifiedTime) ----

    @Test
    fun copyWritesBackSourceModifiedTimeToDestination() {
        src.put("/a.txt", "hello", time = 1_700_000_000_000L)
        CopyEngine.transfer(listOf(srcFile("/a.txt")), dstRoot(), move = false)

        assertEquals(1_700_000_000_000L, dst.resolve("/a.txt").lastModified)
    }

    @Test
    fun destinationNotSupportingSetTimeDoesNotAffectCopySuccess() {
        dst.setModifiedTimeSupported = false
        src.put("/a.txt", "hello", time = 1_700_000_000_000L)
        CopyEngine.transfer(listOf(srcFile("/a.txt")), dstRoot(), move = false)

        // The copy itself still succeeds, only the destination time is not changed —
        // this step failing must not error out / roll back the whole copy
        assertEquals("hello", dst.text("/a.txt"))
        assertEquals(1, dst.setModifiedTimeCalls)
    }

    @Test
    fun everyFileInADirectoryGetsItsTimeWrittenBackIndividually() {
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
        assertFalse("source must be deleted once the move succeeds", src.files.containsKey("/a.txt"))
    }

    /** A move within the same filesystem prefers the moveWithin fast path over a full copy. */
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

    // ---- Conflict decisions ----

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

    /** ★ On skip the source **must not be deleted**: nothing got copied over, so the source is the only copy. */
    @Test
    fun conflictSkipKeepsBothSides() {
        src.put("/a.txt", "new")
        dst.put("/a.txt", "old")

        CopyEngine.transfer(
            listOf(srcFile("/a.txt")), dstRoot(), move = true,
            resolver = { _, _ -> CopyEngine.Decision.SKIP },
        )
        assertEquals("old", dst.text("/a.txt")) // destination untouched
        assertEquals("new", src.text("/a.txt")) // source not deleted either
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

    /** Consecutive renames must keep incrementing the number, not overwrite the previous (1). */
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

    /** ★ Choosing "cancel" at a conflict prompt (resolver returns null): the whole task aborts, any queued follow-up items are not processed, and all sources are left in place. */
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
        assertFalse("no further items should be copied after aborting", dst.files.containsKey("/b.txt"))
        assertTrue(src.files.containsKey("/a.txt"))
        assertTrue(src.files.containsKey("/b.txt"))
    }

    /** Same-name directories are merged outright, never prompted for; files from both sides end up present. */
    @Test
    fun sameNameDirectoriesMergeWithoutPrompting() {
        src.put("/d/new.txt", "n")
        dst.put("/d/old.txt", "o")
        var asked = 0

        CopyEngine.transfer(
            listOf(srcFile("/d")), dstRoot(), move = false,
            resolver = { _, _ -> asked++; CopyEngine.Decision.OVERWRITE },
        )
        assertEquals("a same-name directory must not trigger a conflict prompt", 0, asked)
        assertEquals("o", dst.text("/d/old.txt"))
        assertEquals("n", dst.text("/d/new.txt"))
    }

    // ---- Cancellation ----

    /** ★ The source must not be deleted after cancelling. */
    @Test
    fun cancelKeepsSource() {
        src.put("/a.txt", "hello")
        CopyEngine.transfer(
            listOf(srcFile("/a.txt")), dstRoot(), move = true,
            cancelled = { true },
        )
        assertTrue("the source must remain after cancelling", src.files.containsKey("/a.txt"))
    }

    /**
     * ★ The half-finished copy on the destination must be removed after cancelling —
     * leaving it behind would be a corrupt, incomplete file.
     *
     * Note the timing of the cancellation: `cancelled = { true }` returning right at the
     * top of copyRecursive means the destination was never even created, and this case
     * would not be exercised. Cancellation must happen **after the first file has already
     * started transferring** for it to reach the pump.
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
        assertFalse("the destination must not keep a half-finished file after cancelling", dst.files.containsKey("/a.txt"))
    }

    /** Cancelling in overwrite mode: the original file has already been truncated by openOutput, and it must not be left corrupt either. */
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
        assertFalse("cancelling during overwrite must not leave a half-finished file either", dst.files.containsKey("/a.txt"))
    }

    // ---- Counting and progress ----

    @Test
    fun planCountsRecursively() {
        src.put("/d/x.txt", "12345")
        src.put("/d/sub/y.txt", "123")
        val plan = CopyEngine.plan(listOf(srcFile("/d")))

        assertEquals(2, plan.files)
        assertEquals(2, plan.dirs) // /d and /d/sub
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

    // ---- Pipeline ----

    /** pipe must move data through byte-for-byte (including large data crossing buffer-chunk boundaries), and return false on cancellation. */
    @Test
    fun pipeCopiesExactBytesAndHonoursCancel() {
        val data = ByteArray(3 shl 20) { (it % 251).toByte() } // 3MB, spanning several 1MB buffer chunks
        val out = ByteArrayOutputStream()
        assertTrue(CopyEngine.pipe(ByteArrayInputStream(data), out, { false }))
        assertArrayEquals(data, out.toByteArray())

        val out2 = ByteArrayOutputStream()
        assertFalse(CopyEngine.pipe(ByteArrayInputStream(data), out2, { true }))
    }
}
