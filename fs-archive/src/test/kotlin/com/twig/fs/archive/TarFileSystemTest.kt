package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class TarFileSystemTest {

    private lateinit var tmp: File
    private lateinit var archive: File
    private val fs = TarFileSystem()

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(fs)

        tmp = File.createTempFile("twigtar", "").let { it.delete(); it.mkdirs(); it }
        archive = File(tmp, "a.tar")
        TarArchiveOutputStream(FileOutputStream(archive)).use { out ->
            out.putDir("dir/")
            out.putEntry("hello.txt", "hi-tar")
            out.putEntry("dir/a.txt", "aaa")
        }
    }

    private fun TarArchiveOutputStream.putDir(name: String) {
        putArchiveEntry(TarArchiveEntry(name))
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.putEntry(name: String, content: String) {
        val bytes = content.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
        write(bytes)
        closeArchiveEntry()
    }

    private fun archiveX() = XFile("file", archive.absolutePath, false)

    @Test
    fun listsAndReadsTar() {
        val root = fs.rootOf(archiveX())
        assertEquals(listOf("dir:true", "hello.txt:false"), fs.list(root).map { "${it.name}:${it.isDir}" })

        val hello = fs.list(root).first { it.name == "hello.txt" }
        assertEquals(6L, hello.size)
        assertEquals("hi-tar", fs.openInput(hello).bufferedReader().use { it.readText() })

        val dir = fs.list(root).first { it.isDir }
        assertEquals(listOf("a.txt"), fs.list(dir).map { it.name })
        assertEquals("aaa", fs.openInput(fs.list(dir).first()).bufferedReader().use { it.readText() })
    }

    /** Appending a second entry under the same name shadows the first — later wins. */
    @Test
    fun sameNameTwiceReadsTheLastOne() {
        val shadowed = File(tmp, "dup.tar")
        TarArchiveOutputStream(FileOutputStream(shadowed)).use { out ->
            out.putEntry("x.txt", "old")
            out.putEntry("x.txt", "new")
        }
        val root = fs.rootOf(XFile("file", shadowed.absolutePath, false))
        val rows = fs.list(root)
        assertEquals(1, rows.size)
        assertEquals("new", fs.openInput(rows.first()).bufferedReader().use { it.readText() })
    }

    @Test
    fun extractTarViaCopyEngine() {
        val root = fs.rootOf(archiveX())
        val dest = File(tmp, "out").apply { mkdirs() }
        val destX = FsRegistry.of("file").resolve(dest.absolutePath)
        CopyEngine.transfer(fs.list(root), destX, move = false)

        assertTrue(File(dest, "hello.txt").exists())
        assertEquals("hi-tar", File(dest, "hello.txt").readText())
        assertEquals("aaa", File(dest, "dir/a.txt").readText())
    }

    /**
     * Entry data in a tar is contiguous and uncompressed, so reading one is a seek plus a
     * bounded read — no second walk over the headers. On a remote host that difference is
     * the whole point: one channel per entry read, not one per read *plus* one per walk.
     */
    @Test
    fun readingEntriesDoesNotRewalkTheArchive() {
        val share = File(tmp, "share").apply { mkdirs() }
        val remoteTar = File(share, "big.tar")
        TarArchiveOutputStream(FileOutputStream(remoteTar)).use { out ->
            // Padded so the headers land far apart: a re-walk would have to pull several
            // of RandomSourceChannel's 256 KB blocks per entry, not just re-read one.
            repeat(3) { i -> out.putEntry("f$i.bin", "content-$i".padEnd(300_000, '.')) }
        }
        val mount = MountFs(share)
        FsRegistry.register(mount)

        val root = fs.rootOf(mount.resolve("/big.tar"))
        val rows = fs.list(root)
        assertEquals(3, rows.size)

        val afterListing = mount.opens.get()
        for (row in rows) fs.openInput(row).use { it.readBytes() }
        assertEquals("one channel per entry read", afterListing + 3, mount.opens.get())

        assertTrue(fs.openInput(rows[1]).bufferedReader().use { it.readText() }.startsWith("content-1"))
    }

    /** Contiguous storage also means a nested archive/video inside can seek in place. */
    @Test
    fun entriesSupportSlicedRandomAccess() {
        val root = fs.rootOf(archiveX())
        val hello = fs.list(root).first { it.name == "hello.txt" }
        assertTrue(fs.fastRandom(hello))

        fs.openRandom(hello).use { src ->
            assertEquals(6L, src.length())
            val buf = ByteArray(3)
            assertEquals(3, src.readAt(3, buf, 0, 3))
            assertEquals("tar", String(buf))
            assertEquals(-1, src.readAt(6, buf, 0, 3)) // past the entry, not into the next one
        }
    }

    /** skip() must seek instead of reading and discarding (it pulls bytes over a network). */
    @Test
    fun sliceStreamSkipsWithoutReading() {
        val root = fs.rootOf(archiveX())
        val hello = fs.list(root).first { it.name == "hello.txt" }
        fs.openInput(hello).use { ins ->
            assertEquals(3L, ins.skip(3))
            assertEquals("tar", ins.readBytes().decodeToString())
        }
    }

    @Test
    fun missingEntryStillFails() {
        val root = fs.rootOf(archiveX())
        val ghost = XFile(TarFileSystem.SCHEME, "${archive.absolutePath}${ArchiveFileSystem.SEP}nope.txt", false)
        try {
            fs.openInput(ghost).close()
            throw AssertionError("expected a failure for a missing entry")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("No such file") == true)
        }
    }

    @Test
    fun tarIsReadOnly() {
        val root = fs.rootOf(archiveX())
        assertTrue(!fs.writable())
        assertTrue(!root.canWrite)
    }
}
