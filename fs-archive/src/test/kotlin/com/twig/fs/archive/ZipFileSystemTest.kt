package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipFileSystemTest {

    private lateinit var tmp: File
    private lateinit var archive: File
    private val zfs = ZipFileSystem()

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(zfs)

        tmp = File.createTempFile("twig", "").let { it.delete(); it.mkdirs(); it }
        archive = File(tmp, "a.zip")
        ZipOutputStream(archive.outputStream()).use { z ->
            z.put("hello.txt", "hi")
            z.put("dir/a.txt", "aaa")
            z.put("dir/sub/b.txt", "bbbbb")
        }
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private fun archiveX() =
        XFile(scheme = "file", path = archive.absolutePath, isDir = false)

    @Test
    fun listsRootWithDirsFirst() {
        val root = zfs.rootOf(archiveX())
        val names = zfs.list(root).map { "${it.name}:${it.isDir}" }
        // Directories come first
        assertEquals(listOf("dir:true", "hello.txt:false"), names)
    }

    @Test
    fun listsNestedDirAndReadsFile() {
        val root = zfs.rootOf(archiveX())
        val dir = zfs.list(root).first { it.isDir }
        val children = zfs.list(dir).map { "${it.name}:${it.isDir}" }
        assertEquals(listOf("sub:true", "a.txt:false"), children)

        val helloX = zfs.list(root).first { it.name == "hello.txt" }
        val text = zfs.openInput(helloX).bufferedReader().use { it.readText() }
        assertEquals("hi", text)
        assertEquals(2L, helloX.size)
    }

    @Test
    fun parentOfRootJumpsBackToHostFolder() {
        val root = zfs.rootOf(archiveX())
        val parent = zfs.parentOf(root)!!
        assertEquals("file", parent.scheme)
        assertEquals(tmp.absolutePath, parent.path)
    }

    @Test
    fun extractViaCopyEngine() {
        // Extraction = using CopyEngine to copy entries inside the package to a local directory, verifying the abstraction layer's cross-filesystem copy
        val root = zfs.rootOf(archiveX())
        val dest = File(tmp, "out").apply { mkdirs() }
        val destX = FsRegistry.of("file").resolve(dest.absolutePath)

        CopyEngine.transfer(zfs.list(root), destX, move = false)

        assertTrue(File(dest, "hello.txt").exists())
        assertEquals("hi", File(dest, "hello.txt").readText())
        // Directory recursion
        assertEquals("aaa", File(dest, "dir/a.txt").readText())
        assertEquals("bbbbb", File(dest, "dir/sub/b.txt").readText())
    }

    @Test
    fun autoDetectsGbkNames() {
        // Write a Chinese-named entry using GBK (simulating a legacy archive)
        val gbk = File(tmp, "gbk.zip")
        ZipOutputStream(gbk.outputStream(), Charset.forName("GBK")).use { z ->
            z.put("报告.txt", "neirong")
        }
        val gbkX = XFile("file", gbk.absolutePath, false)
        val root = zfs.rootOf(gbkX)
        val names = zfs.list(root).map { it.name }
        assertEquals(listOf("报告.txt"), names) // Auto-detected as GBK, name is not garbled
        val text = zfs.openInput(zfs.list(root).first()).bufferedReader().use { it.readText() }
        assertEquals("neirong", text)
    }

    @Test
    fun writeCopyIntoZip() {
        // Copy a local file into the zip (goes through the full-package rewrite of createFile + openOutput)
        val local = File(tmp, "new.txt").apply { writeText("inserted") }
        val localX = FsRegistry.of("file").resolve(local.absolutePath)
        CopyEngine.transfer(listOf(localX), zfs.rootOf(archiveX()), move = false)

        val root = zfs.rootOf(archiveX())
        val names = zfs.list(root).map { it.name }.toSet()
        assertTrue("new.txt" in names)
        assertTrue("hello.txt" in names) // the original entry is still present
        val inserted = zfs.openInput(zfs.list(root).first { it.name == "new.txt" })
            .bufferedReader().use { it.readText() }
        assertEquals("inserted", inserted)
    }

    @Test
    fun deleteMkdirRenameInZip() {
        val root = zfs.rootOf(archiveX())

        // Delete a directory (recursively)
        val dir = zfs.list(root).first { it.isDir }
        zfs.delete(dir)
        assertTrue(zfs.list(zfs.rootOf(archiveX())).none { it.name == "dir" })

        // Create a new directory
        zfs.mkdir(zfs.rootOf(archiveX()), "newdir")
        assertTrue(zfs.list(zfs.rootOf(archiveX())).any { it.name == "newdir" && it.isDir })

        // Rename a file
        val hello = zfs.list(zfs.rootOf(archiveX())).first { it.name == "hello.txt" }
        zfs.rename(hello, "renamed.txt")
        val names = zfs.list(zfs.rootOf(archiveX())).map { it.name }.toSet()
        assertTrue("renamed.txt" in names)
        assertTrue("hello.txt" !in names)
    }

    /**
     * stat answers from the entry table: exact for a file, the implicit directory for a path
     * that only appears as a prefix, and — unlike [ZipFileSystem.resolve] — null for a path
     * that matches nothing at all.
     */
    @Test
    fun statAnswersExactlyWhereResolveGuesses() {
        val root = zfs.rootOf(archiveX())
        val base = root.path

        val file = zfs.stat("$base/dir/a.txt")!!
        assertEquals(3L, file.size)
        assertTrue(!file.isDir)
        assertTrue(zfs.stat("$base/dir")!!.isDir) // implicit directory, no entry of its own
        assertEquals(null, zfs.stat("$base/dir/nope.txt"))
        assertEquals(null, zfs.stat("$base/nosuchdir"))
        // resolve, by contrast, invents a directory for it
        assertTrue(zfs.resolve("$base/nosuchdir").isDir)
    }

    /**
     * Zip slip, the settled shape (2026-09-18): such an archive is **shown and browsable** —
     * the `..` directory is right there and the files under it open — but extracting must not
     * write anything outside the destination, and the entry whose own name is `..` is skipped
     * rather than failing the whole extraction.
     */
    @Test
    fun entriesClimbingOutAreVisibleButCannotEscapeWhenExtracted() {
        val evil = File(tmp, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.put("a/../../escaped.txt", "pwn")
            z.put("ok/inner.txt", "fine")
        }
        val root = zfs.rootOf(XFile(scheme = "file", path = evil.absolutePath, isDir = false))
        // visible: `a`, then `..`, then `..`, then the file itself
        assertEquals(listOf("a", "ok"), zfs.list(root).map { it.name })
        val a = zfs.list(root).first { it.name == "a" }
        assertEquals(listOf(".."), zfs.list(a).map { it.name })
        val up2 = zfs.list(zfs.list(a).single()).single()
        val escaped = zfs.list(up2).single()
        assertEquals("escaped.txt", escaped.name)
        // …and openable, which is read-only and therefore harmless
        assertEquals("pwn", zfs.openInput(escaped).bufferedReader().use { it.readText() })

        val dest = File(tmp, "out/dest").apply { mkdirs() }
        var refused = 0
        CopyEngine.transfer(
            zfs.list(root), XFile("file", dest.absolutePath, isDir = true), move = false,
            listener = object : CopyEngine.ProgressListener {
                override fun onRefused(file: XFile) { refused++ }
            },
        )
        assertEquals("the `..` entry must be refused", 1, refused)
        assertEquals("fine", File(dest, "ok/inner.txt").readText())
        assertEquals("nothing may be written outside the destination", listOf("dest"), File(tmp, "out").list()!!.toList())
        assertTrue(!File(tmp, "escaped.txt").exists())
    }

    /** ★ The user's own case: after expanding the `..`, an ordinary file inside it copies out normally. */
    @Test
    fun anOrdinaryFileUnderADotDotPathStillCopiesOut() {
        val evil = File(tmp, "evil2.zip")
        ZipOutputStream(evil.outputStream()).use { z -> z.put("a/../../escaped.txt", "payload") }
        val root = zfs.rootOf(XFile(scheme = "file", path = evil.absolutePath, isDir = false))
        val a = zfs.list(root).single()
        val file = zfs.list(zfs.list(zfs.list(a).single()).single()).single()

        val dest = File(tmp, "out2/dest").apply { mkdirs() }
        CopyEngine.transfer(listOf(file), XFile("file", dest.absolutePath, isDir = true), move = false)

        // lands under its own name, in the directory the user picked
        assertEquals("payload", File(dest, "escaped.txt").readText())
        assertEquals(listOf("dest"), File(tmp, "out2").list()!!.toList())
    }
}
