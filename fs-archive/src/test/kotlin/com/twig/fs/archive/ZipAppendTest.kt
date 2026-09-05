package com.twig.fs.archive

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Adding a file to an existing zip goes through **append**: the new entry is tacked onto
 * the tail of the package, and existing entries are not touched by a single byte.
 *
 * The most important assertion here is "the first N bytes of the original package are
 * byte-for-byte identical" — it directly proves the old data was not rewritten, rather
 * than timing the operation (that kind of assertion is inevitably flaky on CI). The old
 * implementation had to decompress and recompress the whole package for every file
 * added, whereas `CopyEngine` does one `openOutput` per file, so dragging N files into a
 * large package used to mean N full decompress+recompress passes.
 */
class ZipAppendTest {

    private lateinit var tmp: File
    private lateinit var archive: File
    private val zfs = ZipFileSystem()

    /** Incompressible random data: the compression ratio cannot be faked, so whether it was rewritten is visible in the bytes. */
    private val bulk = ByteArray(2 * 1024 * 1024).also { java.util.Random(7).nextBytes(it) }

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(zfs)
        tmp = File.createTempFile("twigappend", "").let { it.delete(); it.mkdirs(); it }
        archive = File(tmp, "a.zip")
        ZipOutputStream(archive.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("old.txt"))
            z.write("original".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("dir/bulk.bin"))
            z.write(bulk)
            z.closeEntry()
        }
    }

    private fun archiveX() = XFile("file", archive.absolutePath, isDir = false)

    private fun add(inner: String, content: ByteArray) {
        val target = XFile(ZipFileSystem.SCHEME, "${archive.absolutePath}${ArchiveFileSystem.SEP}$inner", isDir = false)
        zfs.openOutput(target, append = false).use { it.write(content) }
    }

    private fun read(inner: String): ByteArray {
        val root = zfs.rootOf(archiveX())
        var cur = root
        val parts = inner.split('/')
        for (p in parts.dropLast(1)) cur = zfs.list(cur).first { it.name == p && it.isDir }
        return zfs.openInput(zfs.list(cur).first { it.name == parts.last() }).use { it.readBytes() }
    }

    private fun names(): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: XFile, prefix: String) {
            for (c in zfs.list(dir)) {
                if (c.isDir) walk(c, "$prefix${c.name}/") else out.add("$prefix${c.name}")
            }
        }
        walk(zfs.rootOf(archiveX()), "")
        return out.sorted()
    }

    @Test
    fun addingANewFileLeavesExistingBytesUntouched() {
        val before = archive.readBytes()
        add("added.txt", "brand new".toByteArray())

        val after = archive.readBytes()
        assertTrue("appending should only make the package grow", after.size > before.size)
        assertArrayEquals(
            "the region holding existing entries must be byte-for-byte identical (a change would mean the whole package got rewritten again)",
            before,
            after.copyOfRange(0, before.size),
        )
    }

    @Test
    fun bothOldAndNewEntriesAreReadableAfterAppending() {
        add("added.txt", "brand new".toByteArray())

        assertEquals(listOf("added.txt", "dir/bulk.bin", "old.txt"), names())
        assertEquals("original", read("old.txt").toString(Charsets.UTF_8))
        assertEquals("brand new", read("added.txt").toString(Charsets.UTF_8))
        assertArrayEquals(bulk, read("dir/bulk.bin"))
    }

    @Test
    fun repeatedAppendsEachOnlyTackOntoTheEnd() {
        val sizes = ArrayList<Int>()
        for (i in 1..5) {
            val before = archive.readBytes()
            add("f$i.txt", "content $i".toByteArray())
            sizes.add(archive.readBytes().size)
            assertArrayEquals(
                "append #$i touched the preceding bytes",
                before,
                archive.readBytes().copyOfRange(0, before.size),
            )
        }
        assertEquals(sizes.sorted(), sizes) // monotonically increasing
        assertEquals(
            listOf("dir/bulk.bin", "f1.txt", "f2.txt", "f3.txt", "f4.txt", "f5.txt", "old.txt"),
            names(),
        )
        for (i in 1..5) assertEquals("content $i", read("f$i.txt").toString(Charsets.UTF_8))
        assertArrayEquals(bulk, read("dir/bulk.bin"))
    }

    @Test
    fun existingSameNameEntryFallsBackToFullRewriteOverwritingWithoutLeavingADuplicate() {
        add("old.txt", "replaced".toByteArray())

        assertEquals(listOf("dir/bulk.bin", "old.txt"), names())
        assertEquals("replaced", read("old.txt").toString(Charsets.UTF_8))
        assertArrayEquals(bulk, read("dir/bulk.bin"))
    }

    @Test
    fun aNewEntryInASubdirectoryAlsoGoesThroughAppend() {
        val before = archive.readBytes()
        add("dir/added.bin", bulk)

        assertArrayEquals(before, archive.readBytes().copyOfRange(0, before.size))
        assertEquals(listOf("dir/added.bin", "dir/bulk.bin", "old.txt"), names())
        assertArrayEquals(bulk, read("dir/added.bin"))
    }

    @Test
    fun canStillAppendFilesAfterCreatingADirectoryEntry() {
        // mkdir goes through a full rewrite, after which the EOCD position changes; append must still recognize it correctly
        zfs.mkdir(zfs.rootOf(archiveX()), "fresh")
        add("fresh/x.txt", "inside".toByteArray())

        assertTrue(names().contains("fresh/x.txt"))
        assertEquals("inside", read("fresh/x.txt").toString(Charsets.UTF_8))
        assertEquals("original", read("old.txt").toString(Charsets.UTF_8))
    }

    /** After appending, the tail structure of the package must survive scrutiny from another parser; java.util.zip serves as the second pair of eyes here. */
    @Test
    fun theAppendedPackageIsAlsoReadableByTheJdkItself() {
        add("added.txt", "brand new".toByteArray())
        add("added2.txt", "another".toByteArray())

        java.util.zip.ZipFile(archive).use { zf ->
            val got = zf.entries().asSequence().map { it.name }.toList().sorted()
            assertEquals(listOf("added.txt", "added2.txt", "dir/bulk.bin", "old.txt"), got)
            val e = zf.getEntry("added.txt")
            assertEquals("brand new", zf.getInputStream(e).readBytes().toString(Charsets.UTF_8))
            assertArrayEquals(bulk, zf.getInputStream(zf.getEntry("dir/bulk.bin")).readBytes())
        }
    }

    /**
     * In the UI, "expand a zip -> copy on the other side" ultimately goes through
     * `CopyEngine.transfer(source, archive root)`. The cases above call
     * [ZipFileSystem.openOutput] directly; this one strings the real entry point
     * together to verify it too.
     */
    @Test
    fun copyingIntoTheArchiveRootViaCopyEngineAlsoGoesThroughAppend() {
        val src = File(tmp, "outside.txt").apply { writeText("from outside") }
        val before = archive.readBytes()

        com.twig.core.CopyEngine.transfer(
            listOf(XFile("file", src.absolutePath, isDir = false, size = src.length())),
            zfs.rootOf(archiveX()),
            move = false,
        )

        assertArrayEquals(
            "copying into the package must not rewrite existing entries",
            before,
            archive.readBytes().copyOfRange(0, before.size),
        )
        assertEquals(listOf("dir/bulk.bin", "old.txt", "outside.txt"), names())
        assertEquals("from outside", read("outside.txt").toString(Charsets.UTF_8))
        assertArrayEquals(bulk, read("dir/bulk.bin"))
    }

    @Test
    fun deleteStillDoesAFullRewriteAndCleansUpTheGarbageLeftByAppending() {
        add("added.txt", "brand new".toByteArray())
        val afterAppend = archive.length()

        val root = zfs.rootOf(archiveX())
        zfs.delete(zfs.list(root).first { it.name == "added.txt" })

        assertEquals(listOf("dir/bulk.bin", "old.txt"), names())
        assertTrue("the old central directory garbage should not remain after a full rewrite", archive.length() < afterAppend)
        assertArrayEquals(bulk, read("dir/bulk.bin"))
    }
}
