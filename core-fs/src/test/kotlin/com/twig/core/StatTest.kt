package com.twig.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

/**
 * [FileSystem.stat]'s default implementation: the answer every backend can give without a
 * stat primitive — list the parent and match.
 *
 * ★ Matching is by **path, not name**: a media server's `name` is a title while its path is
 * an id, and a SAF path is an opaque URI. Matching by name would quietly return null exactly
 * on the backends whose metadata callers cannot reconstruct themselves.
 */
class StatTest {

    /** Paths are ids, names are titles — the shape that breaks name matching. */
    private class IdFs(private val listed: List<XFile>) : FileSystem {
        override val scheme = "idfs"
        override val displayName = "ids"
        var listCalls = 0
        override fun root() = XFile(scheme, "/", isDir = true)
        override fun resolve(path: String) = XFile(scheme, path, isDir = true) // the usual optimistic stub
        override fun list(dir: XFile): List<XFile> {
            listCalls++
            if (dir.path != "/lib") throw FsException("no such directory: ${dir.path}")
            return listed
        }
        override fun openInput(file: XFile): InputStream = throw FsException("no")
        override fun openOutput(file: XFile, append: Boolean): OutputStream = throw FsException("no")
        override fun mkdir(parent: XFile, name: String): XFile = throw FsException("no")
        override fun delete(file: XFile) = throw FsException("no")
        override fun rename(file: XFile, newName: String): XFile = throw FsException("no")
        override fun exists(file: XFile) = listed.any { it.path == file.path }
    }

    private val song = XFile("idfs", "/lib/3117", isDir = false, size = 42, lastModified = 7, displayName = "Song.flac")

    @Test
    fun `finds the entry by path and brings back its real metadata`() {
        val fs = IdFs(listOf(song))
        val got = fs.stat("/lib/3117")
        assertEquals(42L, got!!.size)
        assertEquals(7L, got.lastModified)
        assertEquals("Song.flac", got.name)
        assertEquals(false, got.isDir)
        assertEquals("one listing, not one per field", 1, fs.listCalls)
    }

    @Test
    fun `a path that is not there is null, and so is one whose parent cannot be listed`() {
        val fs = IdFs(listOf(song))
        assertNull(fs.stat("/lib/9999"))
        assertNull(fs.stat("/elsewhere/3117")) // list throws -> null, not an exception
    }

    @Test
    fun `the root answers with the root itself, without listing anything`() {
        val fs = IdFs(listOf(song))
        assertEquals("/", fs.stat("/")!!.path)
        assertEquals("/", fs.stat("")!!.path)
        assertEquals(0, fs.listCalls)
    }
}
