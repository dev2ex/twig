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
 * The local filesystem. The focus is on the cases where **a user's file must not be
 * silently swallowed**: `File.renameTo` is backed by POSIX `rename(2)`, whose default
 * behavior is to atomically replace an existing target — implementing "rename" as a
 * straight pass-through would silently delete another file.
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

    /** Regression: renaming to an already-existing name must throw an error, not replace that file. */
    @Test
    fun renameRefusesToOverwrite() {
        val a = File(tmp, "a.txt").apply { writeText("source") }
        val b = File(tmp, "b.txt")
        val victim = "don't overwrite me".toByteArray()
        b.writeBytes(victim)

        val e = runCatching { fs.rename(x(a), "b.txt") }.exceptionOrNull()

        assertTrue("should throw FsException, actually got: $e", e is FsException)
        assertTrue(a.exists())                    // source still present
        assertArrayEquals(victim, b.readBytes())  // destination untouched
    }

    /** Same as above: CopyEngine's in-place-move fast path must yield rather than overwrite when it hits a same-name target. */
    @Test
    fun moveWithinRefusesToOverwrite() {
        val dst = File(tmp, "dst").apply { mkdirs() }
        val src = File(tmp, "a.txt").apply { writeText("source") }
        val victim = "don't overwrite me".toByteArray()
        File(dst, "a.txt").writeBytes(victim)

        // Returning false = in-place move is not supported, hand back to CopyEngine's "copy + delete source" path (which has conflict prompting)
        assertFalse(fs.moveWithin(x(src), x(dst), "a.txt"))
        assertTrue(src.exists())
        assertArrayEquals(victim, File(dst, "a.txt").readBytes())
    }

    /**
     * Regression: deleting a directory **must not follow a symlink into deleting the
     * target's contents**.
     * `File.isDirectory`/`listFiles()` both follow links, so without an explicit check
     * the directory the link points to would get emptied.
     */
    @Test
    fun deleteDoesNotFollowSymlinks() {
        val outside = File(tmp, "outside").apply { mkdirs() }
        val treasure = File(outside, "treasure.txt").apply { writeText("do not delete me") }

        val victim = File(tmp, "victim").apply { mkdirs() }
        File(victim, "own.txt").writeText("this one should be deleted")
        val link = File(victim, "link")
        val made = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        org.junit.Assume.assumeTrue("current environment cannot create symlinks, skipping", made)

        fs.delete(x(victim))

        assertFalse(victim.exists())          // the directory itself is deleted
        assertTrue(outside.isDirectory)       // the directory the link pointed to is still there
        assertEquals("do not delete me", treasure.readText()) // nothing inside it is missing
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
     * The write hook (:app uses it to notify the system media library). The rule is
     * **reported only after the stream is closed, and only once** — reporting at open
     * time would have the media library scan a 0-byte empty shell.
     */
    @Test
    fun changeHookFiresAfterStreamClosed() {
        val seen = ArrayList<String>()
        LocalFileSystem.changed = { seen += it }
        try {
            val f = File(tmp, "a.txt")
            val out = fs.openOutput(x(f), append = false)
            out.write("hi".toByteArray())
            assertTrue(seen.isEmpty()) // stream not closed yet, must not report
            out.close()
            out.close() // closing again must not report again
            assertEquals(listOf(f.absolutePath), seen)
            assertEquals("hi", f.readText())

            seen.clear()
            fs.rename(x(f), "b.txt") // rename must report two entries: old path withdrawn, new path added
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
        // Rounded to the second: File.setLastModified stores at second granularity on
        // some filesystems (e.g. older ext variants); asserting against a whole-second
        // value avoids a false failure from the underlying layer truncating milliseconds
        val target = 1_700_000_000_000L

        assertTrue(fs.setModifiedTime(x(f), target))
        assertEquals(target, f.lastModified())
    }
}
