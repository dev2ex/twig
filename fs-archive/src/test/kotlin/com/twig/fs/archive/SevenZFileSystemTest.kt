package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SevenZFileSystemTest {

    private lateinit var tmp: File
    private lateinit var archive: File
    private val fs = SevenZFileSystem()

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(fs)

        tmp = File.createTempFile("twig7z", "").let { it.delete(); it.mkdirs(); it }
        archive = File(tmp, "a.7z")
        SevenZOutputFile(archive).use { out ->
            out.putEntry("hello.txt", "hi-7z")
            out.putEntry("dir/a.txt", "aaa")
        }
    }

    private fun SevenZOutputFile.putEntry(name: String, content: String) {
        val e = SevenZArchiveEntry().apply {
            this.name = name
            isDirectory = false
        }
        putArchiveEntry(e)
        write(content.toByteArray())
        closeArchiveEntry()
    }

    private fun archiveX() = XFile("file", archive.absolutePath, false)

    @Test
    fun listsAndReads7z() {
        val root = fs.rootOf(archiveX())
        val names = fs.list(root).map { "${it.name}:${it.isDir}" }
        assertEquals(listOf("dir:true", "hello.txt:false"), names)

        val hello = fs.list(root).first { it.name == "hello.txt" }
        val text = fs.openInput(hello).bufferedReader().use { it.readText() }
        assertEquals("hi-7z", text)
    }

    @Test
    fun extract7zViaCopyEngine() {
        val root = fs.rootOf(archiveX())
        val dest = File(tmp, "out").apply { mkdirs() }
        val destX = FsRegistry.of("file").resolve(dest.absolutePath)

        CopyEngine.transfer(fs.list(root), destX, move = false)

        assertTrue(File(dest, "hello.txt").exists())
        assertEquals("hi-7z", File(dest, "hello.txt").readText())
        assertEquals("aaa", File(dest, "dir/a.txt").readText())
    }
}
