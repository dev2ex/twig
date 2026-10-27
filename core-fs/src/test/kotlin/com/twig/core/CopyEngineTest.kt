package com.twig.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    private class MemFs(override val scheme: String) : FileSystem, ResumableWrite {
        override val displayName get() = scheme

        val randomOffsets = mutableListOf<Long>()
        val opened = mutableListOf<String>()
        var deleteFailPath: String? = null
        override fun appendPartial(file: XFile) = openOutput(file, true)
        override fun openRandom(file: XFile): RandomSource {
            opened.add(file.path)
            return object : RandomSource {
                override fun length() = file.size
                override fun close() {}
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    randomOffsets.add(position)
                    val data = files.getValue(file.path)
                    if (position >= data.size) return -1
                    if (failAfter != null && position >= failAfter!!) throw FsException("connection reset")
                    val n = minOf(length, data.size - position.toInt(), (failAfter ?: data.size) - position.toInt())
                    data.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                    return n
                }
            }
        }

        val files = LinkedHashMap<String, ByteArray>()
        val dirs = linkedSetOf("/")
        val mtimes = HashMap<String, Long>()

        /** Toggles the "in-place move within the same filesystem" fast path (off by default, so the generic copy+delete-source path runs). */
        var moveWithinSupported = false
        var moveWithinCalls = 0

        /**
         * Simulates a backend that reports a failed read as a clean EOF (the privileged
         * `cat` used to): the stream ends after this many bytes. null = read everything.
         */
        var readLimit: Int? = null

        /** Simulates a read that dies partway (network drop): throws after this many bytes. */
        var failAfter: Int? = null

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

        override fun openInput(file: XFile): InputStream {
            opened.add(file.path)
            val data = files[file.path] ?: throw FsException("does not exist: ${file.path}")
            readLimit?.let { return ByteArrayInputStream(data.copyOf(minOf(it, data.size))) }
            val fail = failAfter ?: return ByteArrayInputStream(data)
            return object : InputStream() {
                private var pos = 0
                override fun read(): Int {
                    if (pos >= fail) throw java.io.IOException("connection reset")
                    return if (pos < data.size) data[pos++].toInt() and 0xFF else -1
                }
            }
        }

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
            if (file.path == deleteFailPath) throw FsException("delete denied")
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

    /**
     * Cancelling in overwrite mode keeps the original: the new content goes to a temp
     * sibling first (SafeWrite), so a cancel costs nothing that was already there. (Before
     * that, openOutput had truncated the original on open and the best we could do was
     * delete the fragment.)
     */
    @Test
    fun cancelDuringOverwriteKeepsTheOriginal() {
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
        assertEquals("old", dst.text("/a.txt"))
        assertEquals("no temp file left behind", listOf("/a.txt"), dst.files.keys.toList())
    }

    /** A read that dies while overwriting leaves the old file as it was. */
    @Test
    fun failedOverwriteKeepsTheOriginal() {
        src.put("/a.txt", "hello world")
        dst.put("/a.txt", "old")
        src.failAfter = 5
        assertThrows(Exception::class.java) {
            CopyEngine.transfer(
                listOf(srcFile("/a.txt")), dstRoot(), move = false,
                resolver = { _, _ -> CopyEngine.Decision.OVERWRITE },
            )
        }
        assertEquals("old", dst.text("/a.txt"))
        assertEquals(listOf("/a.txt"), dst.files.keys.toList())
    }

    @Test
    fun overwriteReplacesContent() {
        src.put("/a.txt", "new")
        dst.put("/a.txt", "old")
        CopyEngine.transfer(
            listOf(srcFile("/a.txt")), dstRoot(), move = false,
            resolver = { _, _ -> CopyEngine.Decision.OVERWRITE },
        )
        assertEquals("new", dst.text("/a.txt"))
        assertEquals(listOf("/a.txt"), dst.files.keys.toList())
    }

    /** Replacing ends with a recursive delete of the old entry — a folder must never be that entry. */
    @Test
    fun aFileNeverOverwritesAFolder() {
        src.put("/a", "file")
        dst.put("/a/keep.txt", "k")
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(
                listOf(srcFile("/a")), dstRoot(), move = true,
                resolver = { _, _ -> CopyEngine.Decision.OVERWRITE },
            )
        }
        assertEquals("k", dst.text("/a/keep.txt"))
        assertEquals("file", src.text("/a"))
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

    // ---- Unsafe names (zip slip) ----

    /**
     * An entry whose **own name** cannot be written into a directory is skipped — the rest of
     * the batch still copies, and a move does not delete it. (It is the name being written
     * that matters, never where the entry came from: see [CopyEngine.isSafeName].)
     */
    @Test
    fun anEntryNamedDotDotIsRefusedAndTheRestStillCopies() {
        src.put("/evil", "pwn")
        src.put("/good.txt", "fine")
        val evil = XFile("cpsrc", "/evil", isDir = false, size = 3, displayName = "..")
        val refused = ArrayList<String>()
        val dest = dst.mkdir(dstRoot(), "out")
        CopyEngine.transfer(
            listOf(evil, srcFile("/good.txt")), dest, move = true,
            listener = object : CopyEngine.ProgressListener {
                override fun onRefused(file: XFile) { refused += file.name }
            },
        )
        assertEquals(listOf(".."), refused)
        assertEquals("fine", dst.text("/out/good.txt"))
        assertEquals("the refused entry stays put even on a move", "pwn", src.text("/evil"))
        assertEquals(listOf("/out/good.txt"), dst.files.keys.toList())
    }

    /** Inside a directory the same rule applies per child, and the directory counts as incomplete. */
    @Test
    fun anUnsafeChildIsSkippedWhileItsSiblingsCopy() {
        val fs = object : FileSystem by src {
            override fun list(dir: XFile): List<XFile> = when (dir.path) {
                "/d" -> listOf(
                    XFile("cpsrc", "/d/x", isDir = false, size = 1, displayName = "../../x"),
                    XFile("cpsrc", "/d/ok.txt", isDir = false, size = 2),
                )
                else -> src.list(dir)
            }
        }
        src.put("/d/x", "1")
        src.put("/d/ok.txt", "ok")
        FsRegistry.register(fs)
        try {
            CopyEngine.transfer(listOf(XFile("cpsrc", "/d", isDir = true)), dstRoot(), move = true)
            assertEquals("ok", dst.text("/d/ok.txt"))
            assertEquals(listOf("/d/ok.txt"), dst.files.keys.toList())
            assertEquals("an incomplete directory is not deleted by a move", "1", src.text("/d/x"))
        } finally {
            FsRegistry.register(src)
        }
    }

    @Test
    fun dotAndRootNamesAreStillWritten() {
        assertTrue(CopyEngine.isSafeName("."))
        assertTrue(CopyEngine.isSafeName("/"))
        assertTrue(CopyEngine.isSafeName("a..b"))
        assertFalse(CopyEngine.isSafeName(".."))
        assertFalse(CopyEngine.isSafeName(""))
        assertFalse(CopyEngine.isSafeName("a/b"))
    }

    // ---- Short / failed reads ----

    /** A read that ends early without an error must not let a move delete the only complete copy. */
    @Test
    fun shortReadDuringMoveKeepsSourceAndDropsPartial() {
        src.put("/a.txt", "hello world")
        src.readLimit = 4
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(listOf(srcFile("/a.txt")), dstRoot(), move = true)
        }
        assertEquals("hello world", src.text("/a.txt"))
        assertEquals(null, dst.text("/a.txt"))
    }

    @Test
    fun readFailureMidCopyRemovesThePartialFile() {
        src.put("/a.txt", "hello world")
        src.failAfter = 5
        assertThrows(Exception::class.java) {
            CopyEngine.transfer(listOf(srcFile("/a.txt")), dstRoot(), move = true)
        }
        assertEquals("hello world", src.text("/a.txt"))
        assertEquals(null, dst.text("/a.txt"))
    }
    @Test fun retryResumesPartialAndProtectsOriginalUntilMoveCompletes() {
        val source = MemFs("retry-source")
        val target = MemFs("retry-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/video", "abcdefghij", 123)
        target.put("/video", "original")
        source.failAfter = 3
        val item = source.resolve("/video")
        val checkpoint = TransferCheckpoint()
        var conflicts = 0
        val resolver = CopyEngine.ConflictResolver { _, _ -> conflicts++; CopyEngine.Decision.OVERWRITE }
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(listOf(item), target.root(), true, resolver = resolver, checkpoint = checkpoint)
        }
        assertEquals("original", target.text("/video"))
        assertEquals("abcdefghij", source.text("/video"))
        assertEquals(3, target.files.getValue(checkpoint.partials.values.single().file.path).size)
        source.failAfter = null
        source.randomOffsets.clear()
        CopyEngine.transfer(listOf(item), target.root(), true, resolver = resolver, checkpoint = checkpoint)
        assertEquals(3L, source.randomOffsets.first())
        assertEquals("abcdefghij", target.text("/video"))
        assertFalse(source.files.containsKey("/video"))
        assertTrue(checkpoint.partials.isEmpty())
        assertEquals(1, conflicts)
    }

    @Test fun retrySkipsCompletedChildrenOfAnUnfinishedDirectory() {
        val source = MemFs("retry-tree-source")
        val target = MemFs("retry-tree-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/folder/a", "abc", 123)
        source.put("/folder/b", "abcdefghij", 123)
        source.failAfter = 3
        val item = source.resolve("/folder")
        val checkpoint = TransferCheckpoint()
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(listOf(item), target.root(), true, checkpoint = checkpoint)
        }
        assertTrue(source.exists(item))
        source.opened.clear()
        source.failAfter = null
        CopyEngine.transfer(listOf(item), target.root(), true, checkpoint = checkpoint)
        assertEquals(listOf("/folder/b"), source.opened)
        assertEquals("abc", target.text("/folder/a"))
        assertEquals("abcdefghij", target.text("/folder/b"))
        assertFalse(source.exists(item))
    }

    @Test fun retryStillDeletesSourceWhenThePreviousDeleteFailed() {
        val source = MemFs("retry-delete-source")
        val target = MemFs("retry-delete-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/file", "complete", 123)
        source.deleteFailPath = "/file"
        val item = source.resolve("/file")
        val checkpoint = TransferCheckpoint()
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(listOf(item), target.root(), true, checkpoint = checkpoint)
        }
        source.deleteFailPath = null
        source.opened.clear()
        CopyEngine.transfer(listOf(item), target.root(), true, checkpoint = checkpoint)
        assertTrue(source.opened.isEmpty())
        assertFalse(source.exists(item))
        assertEquals("complete", target.text("/file"))
    }

    @Test fun retryRestartsIncompleteFilesOnTargetsWithoutResumeSupport() {
        val source = MemFs("retry-fallback-source")
        val storage = MemFs("retry-fallback-target")
        val target = object : FileSystem by storage {}
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/file", "abcdefghij", 123)
        storage.put("/file", "original")
        source.failAfter = 3
        val item = source.resolve("/file")
        val checkpoint = TransferCheckpoint()
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(listOf(item), target.root(), false, checkpoint = checkpoint)
        }
        assertEquals("original", storage.text("/file"))
        assertTrue(checkpoint.partials.isEmpty())
        source.failAfter = null
        CopyEngine.transfer(listOf(item), target.root(), false, checkpoint = checkpoint)
        assertEquals("abcdefghij", storage.text("/file"))
    }

    @Test fun changedSourceRestartsRatherThanAppendingToAnOldPartial() {
        val source = MemFs("changed-source")
        val target = MemFs("changed-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/file", "abcdefghij", 123)
        val item = source.resolve("/file")
        val checkpoint = TransferCheckpoint()
        source.failAfter = 3
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(listOf(item), target.root(), false, checkpoint = checkpoint)
        }
        source.put("/file", "NEWCONTENT", 456)
        source.failAfter = null
        source.randomOffsets.clear()
        CopyEngine.transfer(listOf(item), target.root(), false, checkpoint = checkpoint)
        assertEquals(0L, source.randomOffsets.first())
        assertEquals("NEWCONTENT", target.text("/file"))
    }

    @Test fun cancelledTransferKeepsItsPartialForManualRetry() {
        val source = MemFs("cancel-source")
        val target = MemFs("cancel-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/file", "abcdefghij", 123)
        val item = source.resolve("/file")
        val checkpoint = TransferCheckpoint()
        var cancelled = false
        CopyEngine.transfer(listOf(item), target.root(), true,
            listener = object : CopyEngine.ProgressListener {
                override fun onFileBytes(copied: Long, size: Long) { if (copied > 0) cancelled = true }
            }, cancelled = { cancelled }, checkpoint = checkpoint)
        assertTrue(source.exists(item))
        assertEquals(1, checkpoint.partials.size)
        CopyEngine.transfer(listOf(item), target.root(), true, checkpoint = checkpoint)
        assertEquals("abcdefghij", target.text("/file"))
        assertFalse(source.exists(item))
    }

    @Test fun missingCompletedDestinationIsRecopiedBeforeDeletingTheSource() {
        val source = MemFs("missing-source")
        val target = MemFs("missing-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/file", "complete", 123)
        source.deleteFailPath = "/file"
        val item = source.resolve("/file")
        val checkpoint = TransferCheckpoint()
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(listOf(item), target.root(), true, checkpoint = checkpoint)
        }
        target.files.remove("/file")
        source.deleteFailPath = null
        source.opened.clear()
        CopyEngine.transfer(listOf(item), target.root(), true, checkpoint = checkpoint)
        assertEquals(listOf("/file"), source.opened)
        assertEquals("complete", target.text("/file"))
        assertFalse(source.exists(item))
    }

    @Test fun sourcesWithoutStatOrModificationTimeStillCopyNormally() {
        val storage = MemFs("unstatable-source")
        val source = object : FileSystem by storage {
            override fun stat(path: String): XFile? = null
        }
        val target = MemFs("unstatable-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        storage.put("/file", "shared text")
        CopyEngine.transfer(listOf(storage.resolve("/file")), target.root(), false,
            checkpoint = TransferCheckpoint())
        assertEquals("shared text", target.text("/file"))
    }

    @Test fun retryDoesNotPlanRootsThatWereAlreadyMovedAndDeleted() {
        val source = MemFs("moved-roots-source")
        val target = MemFs("moved-roots-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/a", "abc", 123)
        source.put("/b", "abcdefghij", 123)
        val items = listOf(source.resolve("/a"), source.resolve("/b"))
        val checkpoint = TransferCheckpoint()
        source.failAfter = 3
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(items, target.root(), true, checkpoint = checkpoint)
        }
        assertFalse(source.exists(items[0]))
        source.failAfter = null
        CopyEngine.transfer(items, target.root(), true, checkpoint = checkpoint)
        assertEquals("abc", target.text("/a"))
        assertEquals("abcdefghij", target.text("/b"))
        assertFalse(source.exists(items[1]))
    }

    @Test fun skipFailedChildContinuesTheDirectoryAndKeepsItsMoveSource() {
        val source = MemFs("skip-child-source")
        val target = MemFs("skip-child-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/folder/a", "abc", 123)
        source.put("/folder/b", "broken-file", 123)
        source.put("/folder/c", "xyz", 123)
        target.put("/folder/b", "original")
        source.failAfter = 3
        val folder = source.resolve("/folder")
        val checkpoint = TransferCheckpoint()
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(listOf(folder), target.root(), true, checkpoint = checkpoint)
        }
        val failed = checkpoint.failure!!
        assertEquals("/folder/b", failed.source.path)
        assertEquals("/folder", failed.directory.path)
        assertEquals("b", failed.targetName)
        checkpoint.skip(failed)
        source.opened.clear()
        CopyEngine.transfer(listOf(folder), target.root(), true, checkpoint = checkpoint)
        assertEquals(listOf("/folder/c"), source.opened)
        assertEquals("abc", target.text("/folder/a"))
        assertEquals("original", target.text("/folder/b"))
        assertEquals("xyz", target.text("/folder/c"))
        assertTrue(source.exists(folder))
        assertEquals("broken-file", source.text("/folder/b"))
        assertNull(checkpoint.failure)
    }

    @Test fun successiveSkipsKeepPreviousChoicesAndReportTheNextFailedRoot() {
        val source = MemFs("successive-skip-source")
        val target = MemFs("successive-skip-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/bad-one", "broken-one", 123)
        source.put("/bad-two", "broken-two", 123)
        source.put("/good", "abc", 123)
        val items = listOf(source.resolve("/bad-one"), source.resolve("/bad-two"), source.resolve("/good"))
        source.failAfter = 3
        val checkpoint = TransferCheckpoint()
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(items, target.root(), true, checkpoint = checkpoint)
        }
        checkpoint.skip(checkpoint.failure!!)
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(items, target.root(), true, checkpoint = checkpoint)
        }
        assertEquals("/bad-two", checkpoint.failure!!.source.path)
        checkpoint.skip(checkpoint.failure!!)
        source.opened.clear()
        CopyEngine.transfer(items, target.root(), true, checkpoint = checkpoint)
        assertEquals(listOf("/good"), source.opened)
        assertEquals("abc", target.text("/good"))
        assertFalse(source.exists(items[2]))
        assertTrue(source.exists(items[0]))
        assertTrue(source.exists(items[1]))
    }

    @Test fun failureReportsTheFinalRenamedDestination() {
        val source = MemFs("rename-failure-source")
        val target = MemFs("rename-failure-target")
        FsRegistry.register(source)
        FsRegistry.register(target)
        source.put("/file.txt", "broken-file", 123)
        target.put("/file.txt", "original")
        source.failAfter = 3
        val checkpoint = TransferCheckpoint()
        assertThrows(FsException::class.java) {
            CopyEngine.transfer(listOf(source.resolve("/file.txt")), target.root(), false,
                resolver = { _, _ -> CopyEngine.Decision.RENAME }, checkpoint = checkpoint)
        }
        assertEquals("file (1).txt", checkpoint.failure!!.targetName)
        assertEquals("original", target.text("/file.txt"))
    }

}
