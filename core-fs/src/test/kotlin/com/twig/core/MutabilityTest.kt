package com.twig.core

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

/**
 * "Can this entry be modified" and "can we write into this directory" are two different
 * questions, and a pile of write operations in the UI (rename/move/delete vs. new
 * folder/paste) are gated separately by them.
 *
 * The user's original report: delete/move/rename/new-folder/new-text-file should not
 * appear for media servers, "none of these should be available for any read-only,
 * unpermissioned, or unimplementable directory or file" — so the check must not be
 * written as "is this Jellyfin", it has to ask the source itself: `FileSystem.writable()`
 * (whole source is read-only: media servers, restic, 7z/RAR, the git view, "Apps") +
 * `XFile.canWrite` (a single entry lacks permission).
 *
 * ★ **Copy/compress/share on a read-only source is valid** and must not be disabled
 * along with the rest — that is precisely the main use of a read-only source.
 */
class MutabilityTest {

    private class Fs(override val scheme: String, private val writable: Boolean) : FileSystem {
        override val displayName get() = scheme
        override fun writable() = writable
        override fun root() = XFile(scheme, "/", isDir = true)
        override fun list(dir: XFile) = emptyList<XFile>()
        override fun resolve(path: String) = XFile(scheme, path, isDir = false)
        override fun openInput(file: XFile): InputStream = throw UnsupportedOperationException()
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw UnsupportedOperationException()
        override fun delete(file: XFile) = throw UnsupportedOperationException()
        override fun mkdir(parent: XFile, name: String) = throw UnsupportedOperationException()
        override fun rename(file: XFile, newName: String) = throw UnsupportedOperationException()
        override fun exists(file: XFile) = false
    }

    private fun register(scheme: String, writable: Boolean) {
        FsRegistry.register(Fs(scheme, writable))
    }

    @After
    fun tearDown() {
        for (s in listOf("mut-ro", "mut-rw")) runCatching { FsRegistry.unregister(s) }
    }

    @Test
    fun entriesOnAReadOnlySourceCanNeverBeModified() {
        register("mut-ro", writable = false)
        val file = XFile("mut-ro", "/movies/m1.mkv", isDir = false)
        val dir = XFile("mut-ro", "/movies", isDir = true)
        assertFalse("a file must not be renamable/movable/deletable", file.isMutable())
        assertFalse("must not be able to create/paste into the directory", dir.isWritableDir())
    }

    @Test
    fun entriesOnAWritableSourceCanBeModifiedAsUsual() {
        register("mut-rw", writable = true)
        assertTrue(XFile("mut-rw", "/a.txt", isDir = false).isMutable())
        assertTrue(XFile("mut-rw", "/d", isDir = true).isWritableDir())
    }

    /** The source itself is writable, but this one entry lacks permission (canWrite as computed by list/resolve). */
    @Test
    fun aSingleUnpermissionedEntryCannotBeModifiedEither() {
        register("mut-rw", writable = true)
        assertFalse(XFile("mut-rw", "/ro.txt", isDir = false, canWrite = false).isMutable())
        assertFalse(XFile("mut-rw", "/ro", isDir = true, canWrite = false).isWritableDir())
    }

    /**
     * ★ The two checks ask **different questions**: a file is never a "writable
     * directory", but it can perfectly well be a "modifiable entry" — using
     * [isWritableDir] to gate rename/delete would block ordinary files too.
     */
    @Test
    fun aFileCanBeModifiedButIsNotAWritableDirectory() {
        register("mut-rw", writable = true)
        val file = XFile("mut-rw", "/a.txt", isDir = false)
        assertTrue(file.isMutable())
        assertFalse(file.isWritableDir())
    }

    /** When the source is not registered (connection deleted / not yet connected), it must not be treated as writable, or the UI would offer an entry point that is bound to fail. */
    @Test
    fun aNonExistentSourceIsAlwaysTreatedAsUnmodifiable() {
        val ghost = XFile("mut-nonexistent", "/a.txt", isDir = false)
        assertFalse(ghost.isMutable())
        assertFalse(XFile("mut-nonexistent", "/d", isDir = true).isWritableDir())
    }
}
