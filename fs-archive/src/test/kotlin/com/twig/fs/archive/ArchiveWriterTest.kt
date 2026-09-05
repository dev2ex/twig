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

/** The packer: zip / 7z packages it writes can be read back unchanged by the corresponding read-only implementation, and move mode deletes the source. */
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

    /** The destination archive does not exist yet, so resolve() cannot be used; build it directly (same as what createFile would produce). */
    private fun local(f: File) = XFile("file", f.absolutePath, isDir = f.isDirectory)

    /** A sorted "path:isDir" listing inside the archive (recursive). */
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

    /** Multiple items (flattened at the archive root) + progress callback + move mode deletes the source. */
    @Test
    fun multipleItemsAndMove() {
        val items = listOf(local(File(src, "hello.txt")), local(File(src, "dir")))
        val archive = File(out, "multiple.zip")
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

    /** Cancellation: throws Cancelled, no half-finished archive is left behind. */
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
        assertFalse(File(out, "cancel.zip.twigpart").exists()) // the temp package must be cleaned up too
    }

    /**
     * Regression: **compressing on top of an already-existing archive of the same name,
     * cancelling midway must not touch the old package**.
     * The old implementation wrote directly to the target and called delete(target) on
     * failure, so a user clicking "cancel" would delete the existing backup.zip — the
     * new content never got written, and the old content was gone too.
     */
    @Test
    fun cancelKeepsExistingArchiveIntact() {
        File(src, "big.bin").writeBytes(ByteArray(4 shl 20))
        val archive = File(out, "keep.zip")
        val original = "pretend this is the user's original package".toByteArray()
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

    /** Overwriting an existing archive: it is replaced only on success, with the new content. */
    @Test
    fun successReplacesExistingArchive() {
        val archive = File(out, "replace.zip")
        archive.writeBytes("old content".toByteArray())

        ArchiveWriter.compress(listOf(local(src)), local(out), local(archive), ArchiveWriter.Format.ZIP)

        assertEquals(
            listOf("src:true", "src/dir:true", "src/dir/a.txt:false", "src/empty:true", "src/hello.txt:false"),
            tree(zipFs, archive),
        )
        assertFalse(File(out, "replace.zip.twigpart").exists())
    }
}
