package com.twig.fs.archive

import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ArchiveOptionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var source: File
    private lateinit var destination: File
    private val data = "repeatable text for compression\n".repeat(4096).toByteArray()
    private fun entry(file: File) = XFile("file", file.path, file.isDirectory, file.length())

    @Before fun setup() {
        FsRegistry.register(LocalFileSystem())
        source = temporary.newFolder("source")
        File(source, "private-中文.txt").writeBytes(data)
        File(source, "empty.txt").writeBytes(byteArrayOf())
        File(source, "empty-directory").mkdirs()
        destination = temporary.newFolder("out")
    }

    private fun dump(file: File) {
        System.getenv("TWIG_DUMP_DIR")?.let { File(it).apply { mkdirs() } }
            ?.let { file.copyTo(File(it, file.name), overwrite = true) }
    }

    @Test fun sevenZNamesAreHiddenAndAllLevelsExtractCorrectly() {
        for (level in ArchiveWriter.Level.entries) {
            val archive = File(destination, "hidden-${level.name}.7z")
            ArchiveWriter.compress(listOf(entry(source)), entry(destination), entry(archive),
                ArchiveWriter.Format.SEVEN_Z, password = "secret", level = level, hideNames = true)
            dump(archive)
            assertThrows(PasswordRequiredException::class.java) { SevenZFile.builder().setFile(archive).get().close() }
            assertThrows(java.io.IOException::class.java) {
                SevenZFile.builder().setFile(archive).setPassword("wrong").get().close()
            }
            SevenZFile.builder().setFile(archive).setPassword("secret").get().use { sz ->
                val entries = sz.entries.associateBy { it.name }
                for (entry in entries.values) {
                    assertTrue("Missing Attributes for ${entry.name}", entry.hasWindowsAttributes)
                    assertEquals(entry.name, entry.isDirectory, entry.windowsAttributes and 0x10 != 0)
                }
                assertTrue(entries.getValue("source/empty-directory").isDirectory)
                assertEquals(0L, entries.getValue("source/empty.txt").size)
                assertArrayEquals(data, sz.getInputStream(entries.getValue("source/private-中文.txt")).readBytes())
            }
            val fs = SevenZFileSystem()
            FsRegistry.register(fs)
            val root = fs.rootOf(entry(archive))
            assertTrue(fs.needsPassword(archive.path))
            assertFalse(fs.checkPassword(archive.path, "wrong"))
            assertTrue(fs.checkPassword(archive.path, "secret"))
            fs.setPassword(archive.path, "secret")
            assertEquals("source", fs.list(root).single().name)
            val bytes = archive.readBytes()
            val name = "private-中文.txt".toByteArray(Charsets.UTF_16LE)
            assertFalse((0..bytes.size - name.size).any { start -> name.indices.all { bytes[start + it] == name[it] } })
        }
    }

    @Test fun zipLevelsWorkWithAndWithoutEncryptionIncludingRealStoredEntries() {
        for (password in listOf(null, "secret")) for (level in ArchiveWriter.Level.entries) {
            val archive = File(destination, "${if (password == null) "plain" else "aes"}-${level.name}.zip")
            ArchiveWriter.compress(listOf(entry(source)), entry(destination), entry(archive),
                ArchiveWriter.Format.ZIP, password = password, level = level)
            dump(archive)
            if (password == null) ZipFile(archive).use { zip ->
                val file = zip.getEntry("source/private-中文.txt")
                assertEquals(if (level == ArchiveWriter.Level.STORE) 0 else 8, file.method)
                if (level == ArchiveWriter.Level.STORE) assertEquals(file.size, file.compressedSize)
                assertArrayEquals(data, zip.getInputStream(file).readBytes())
            }
            val fs = ZipFileSystem()
            FsRegistry.register(fs)
            val root = fs.rootOf(entry(archive))
            if (password != null) fs.setPassword(archive.path, password)
            val dir = fs.list(root).single()
            val file = fs.list(dir).single { it.name == "private-中文.txt" }
            assertArrayEquals(data, fs.openInput(file).use { it.readBytes() })
        }
    }

    @Test fun hidingNamesIsOptionalAndIgnoredWithoutPassword() {
        for (password in listOf(null, "secret")) {
            val archive = File(destination, "visible-${password != null}.7z")
            ArchiveWriter.compress(listOf(entry(source)), entry(destination), entry(archive),
                ArchiveWriter.Format.SEVEN_Z, password = password, hideNames = password == null)
            SevenZFile.builder().setFile(archive).get().use { sz ->
                assertTrue(sz.entries.any { it.name == "source/private-中文.txt" })
            }
            dump(archive)
        }
    }

    @Test fun largeHeadersAndEmptyFilesAndDirectoriesCanBeEncrypted() {
        val archive = File(destination, "large-header.7z")
        SevenZOutputFile(archive).use { out ->
            repeat(2500) { index ->
                out.putArchiveEntry(SevenZArchiveEntry().apply {
                    name = "empty-directory-$index/空文件-$index.txt"
                })
                out.closeArchiveEntry()
            }
        }
        SevenZHeaders.encrypt(archive, "secret", CopyEngine.Cancelled { false })
        SevenZFile.builder().setFile(archive).setPassword("secret").get().use { sz ->
            assertEquals(2500, sz.entries.count())
        }
        dump(archive)
    }

    @Test fun cancellingBeforeHeaderEncryptionPreservesTheOldArchive() {
        val archive = File(destination, "cancel-header.7z").apply { writeText("original archive") }
        var cancel = false
        assertThrows(ArchiveWriter.Cancelled::class.java) {
            ArchiveWriter.compress(listOf(entry(File(source, "private-中文.txt"))), entry(destination), entry(archive),
                ArchiveWriter.Format.SEVEN_Z, password = "secret", hideNames = true,
                cancelled = { cancel }, listener = object : CopyEngine.ProgressListener {
                    override fun onItemDone(isDir: Boolean) { cancel = true }
                })
        }
        assertEquals("original archive", archive.readText())
        assertEquals(listOf(archive.name), destination.list()!!.toList())
    }

    @Test fun encryptedHeadersAreUploadedToRemoteDestinationsAfterFinalization() {
        val local = LocalFileSystem()
        val scheme = "remote-archive-options"
        FsRegistry.register(object : com.twig.core.FileSystem by local {
            override val scheme = "remote-archive-options"
            override fun createFile(dir: XFile, name: String) = local.createFile(dir.copy(scheme = "file"), name).copy(scheme = scheme)
            override fun openOutput(file: XFile, append: Boolean) = local.openOutput(file.copy(scheme = "file"), append)
            override fun exists(file: XFile) = local.exists(file.copy(scheme = "file"))
            override fun delete(file: XFile) = local.delete(file.copy(scheme = "file"))
            override fun rename(file: XFile, newName: String) = local.rename(file.copy(scheme = "file"), newName).copy(scheme = scheme)
        })
        try {
            val cache = temporary.newFolder("cache")
            val archive = File(destination, "remote-hidden.7z")
            ArchiveWriter.compress(listOf(entry(source)), entry(destination).copy(scheme = scheme),
                entry(archive).copy(scheme = scheme), ArchiveWriter.Format.SEVEN_Z,
                password = "密碼🙂", hideNames = true, tmpDir = cache)
            assertEquals(0, cache.list()!!.size)
            assertThrows(PasswordRequiredException::class.java) { SevenZFile.builder().setFile(archive).get().close() }
            SevenZFile.builder().setFile(archive).setPassword("密碼🙂").get().use { sz ->
                val file = sz.entries.single { it.name == "source/private-中文.txt" }
                assertArrayEquals(data, sz.getInputStream(file).readBytes())
            }
        } finally { FsRegistry.unregister(scheme) }
    }

    @Test fun savingToTheSourceDirectoryCannotReplaceASelectedArchiveFile() {
        val archive = File(source, "existing.zip").apply { writeText("original archive") }
        assertThrows(com.twig.core.FsException::class.java) {
            ArchiveWriter.compress(listOf(entry(archive)), entry(source), entry(archive), ArchiveWriter.Format.ZIP)
        }
        assertEquals("original archive", archive.readText())
        assertFalse(File(source, "existing.zip.twigpart").exists())
    }

    @Test fun savingToTheSourceDirectoryCannotOverwriteASelectedPartialFile() {
        for (format in ArchiveWriter.Format.entries) {
            val archive = File(source, "existing.${format.ext}").apply { writeText("original archive") }
            val input = File(source, "${archive.name}.twigpart").apply { writeText("selected source") }
            assertThrows(com.twig.core.FsException::class.java) {
                ArchiveWriter.compress(listOf(entry(input)), entry(source), entry(archive), format)
            }
            assertEquals("selected source", input.readText())
            assertEquals("original archive", archive.readText())
        }
    }
}
