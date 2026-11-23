package com.twig.fs.archive

import com.twig.core.*
import com.twig.fs.local.LocalFileSystem
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.FileTime
import java.util.zip.GZIPInputStream

class TarGzipWriteTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var local: LocalFileSystem

    private fun metadata(path: String): UnixMetadata {
        val attrs = Files.readAttributes(File(path).toPath(), "unix:*", NOFOLLOW_LINKS)
        fun n(key: String) = (attrs.getValue(key) as Number).toLong()
        fun t(key: String) = (attrs.getValue(key) as FileTime).toMillis()
        return UnixMetadata(n("mode").toInt(), n("uid"), n("gid"), n("size"),
            t("lastModifiedTime"), t("lastAccessTime"), t("ctime"))
    }

    @Before fun setup() {
        local = LocalFileSystem(readUnix = ::metadata, readLink = { path ->
            if (Files.isSymbolicLink(File(path).toPath())) Files.readSymbolicLink(File(path).toPath()).toString() else null
        })
        FsRegistry.register(local)
        FsRegistry.register(SingleFileSystem.gzip())
    }

    private fun target(name: String) = XFile("file", File(tmp.root, name).path, false)
    private fun pack(source: File, output: XFile, level: ArchiveWriter.Level = ArchiveWriter.Level.STANDARD,
                     paths: Map<String, String> = emptyMap()) = ArchiveWriter.compress(
        listOf(local.stat(source.path)!!), local.resolve(tmp.root.path), output, ArchiveWriter.Format.TAR_GZ,
        level = level, targetPaths = paths)

    @Test fun localModesOwnersTimesAndLinksAreStoredWithoutFollowingDirectoryCycles() {
        val source = tmp.newFolder("source")
        val script = File(source, "run.sh").apply { writeText("#!/bin/sh\nexit 0\n") }
        Files.setAttribute(script.toPath(), "unix:mode", 0x9ED) // 04755, including setuid.
        script.setLastModified(1600000000000)
        Files.setAttribute(source.toPath(), "unix:mode", 0x5E8) // 02750.
        File(source, "empty").mkdir()
        Files.createSymbolicLink(File(source, "cycle").toPath(), java.nio.file.Path.of("."))
        Files.createSymbolicLink(File(source, "missing").toPath(), java.nio.file.Path.of("does-not-exist"))
        Files.createSymbolicLink(File(source, "script-link").toPath(), java.nio.file.Path.of("run.sh"))
        val archive = target("metadata.tar.gz")
        val plan = ArchiveWriter.plan(listOf(local.resolve(source.path)), ArchiveWriter.Format.TAR_GZ)
        assertEquals(script.length(), plan.bytes)
        assertEquals(4, plan.files)
        assertEquals(2, plan.dirs)
        pack(source, archive)
        val seen = mutableSetOf<String>()
        GZIPInputStream(File(archive.path).inputStream()).use { gzip -> TarArchiveInputStream(gzip).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                seen += entry.name
                val original = File(tmp.root, entry.name.trimEnd('/'))
                val info = metadata(original.path)
                assertEquals(entry.name, info.mode and 0xFFF, entry.mode and 0xFFF)
                assertEquals(info.uid, entry.longUserId)
                assertEquals(info.gid, entry.longGroupId)
                assertEquals(info.modified / 1000, entry.lastModifiedDate.time / 1000)
                if (info.isSymbolicLink) {
                    assertTrue(entry.isSymbolicLink)
                    assertEquals(Files.readSymbolicLink(original.toPath()).toString(), entry.linkName)
                    assertEquals(0L, entry.size)
                } else if (!entry.isDirectory) assertArrayEquals(original.readBytes(), tar.readBytes())
            }
        } }
        assertEquals(setOf("source/", "source/empty/", "source/run.sh", "source/cycle", "source/missing", "source/script-link"), seen)
        System.getenv("TWIG_DUMP_DIR")?.let { File(it).mkdirs(); File(archive.path).copyTo(File(it, "metadata.tar.gz"), overwrite = true) }
    }

    @Test fun unicodeLongPathsAndEmptyFilesRoundTripAtEveryLevel() {
        val source = tmp.newFolder("unicode")
        val name = "目录/" + "long-name-".repeat(20) + ".txt"
        val payload = ByteArray(200000) { (it % 23).toByte() }
        File(source, name).apply { parentFile!!.mkdirs(); writeBytes(payload) }
        File(source, "zero").writeBytes(byteArrayOf())
        for (level in ArchiveWriter.Format.TAR_GZ.levels) {
            val archive = target("${level.name}.tar.gz")
            pack(source, archive, level, mapOf(local.resolve(source.path).toUri() to ""))
            val out = tmp.newFolder(level.name)
            CopyEngine.transferSequential(StreamingTar.open(local.resolve(archive.path)), local.resolve(out.path))
            assertArrayEquals(payload, File(out, name).readBytes())
            assertTrue(File(out, "zero").isFile)
            assertEquals(0L, File(out, "zero").length())
        }
    }

    @Test fun remoteDestinationUsesOnlySequentialOutputAndNoCache() {
        val source = tmp.newFile("data").apply { writeText("remote payload") }
        val destination = tmp.newFolder("destination")
        var writes = 0
        val fs = object : FileSystem by local {
            override val scheme = "tar-gzip-remote"
            override fun openRandom(file: XFile): RandomSource = error("Must not seek on destination")
            override fun createFile(parent: XFile, name: String) = local.createFile(parent.copy(scheme = "file"), name).copy(scheme = scheme)
            override fun rename(file: XFile, newName: String) = local.rename(file.copy(scheme = "file"), newName).copy(scheme = scheme)
            override fun openOutput(file: XFile, append: Boolean) = local.openOutput(file.copy(scheme = "file"), append).also { writes++ }
            override fun exists(file: XFile) = local.exists(file.copy(scheme = "file"))
            override fun delete(file: XFile) = local.delete(file.copy(scheme = "file"))
        }
        FsRegistry.register(fs)
        try {
            val dest = local.resolve(destination.path).copy(scheme = fs.scheme)
            val archive = XFile(fs.scheme, File(destination, "remote.tar.gz").path, false)
            val cache = tmp.newFolder("cache")
            ArchiveWriter.compress(listOf(local.resolve(source.path)), dest, archive, ArchiveWriter.Format.TAR_GZ, tmpDir = cache)
            assertEquals(1, writes)
            assertEquals(0, cache.list()!!.size)
            GZIPInputStream(File(archive.path).inputStream()).use { gzip -> TarArchiveInputStream(gzip).use { tar ->
                assertEquals("data", tar.nextTarEntry.name)
                assertEquals("remote payload", tar.readBytes().decodeToString())
            } }
        } finally { FsRegistry.unregister(fs.scheme) }
    }

    @Test fun cancellingOrFailingDuringWriteKeepsExistingArchiveAndRemovesPartial() {
        val source = tmp.newFile("large").apply { writeBytes(ByteArray(3 * 1024 * 1024)) }
        val archive = tmp.newFile("old.tar.gz").apply { writeText("original") }
        var cancelled = false
        assertThrows(ArchiveWriter.Cancelled::class.java) {
            ArchiveWriter.compress(listOf(local.resolve(source.path)), local.resolve(tmp.root.path), local.resolve(archive.path),
                ArchiveWriter.Format.TAR_GZ, cancelled = { cancelled }, listener = object : CopyEngine.ProgressListener {
                    override fun onFileBytes(copied: Long, size: Long) { cancelled = true }
                })
        }
        assertEquals("original", archive.readText())
        assertFalse(File(tmp.root, "old.tar.gz.twigpart").exists())
        val broken = object : FileSystem by local {
            override fun openInput(file: XFile): java.io.InputStream = throw IOException("Cannot read source")
        }
        FsRegistry.register(broken)
        assertThrows(IOException::class.java) { pack(source, local.resolve(archive.path)) }
        assertEquals("original", archive.readText())
        assertFalse(File(tmp.root, "old.tar.gz.twigpart").exists())
    }

    @Test fun storeModeIsRejectedBeforeOpeningTheOutput() {
        val source = tmp.newFile("input")
        val archive = tmp.newFile("old.tar.gz").apply { writeText("original") }
        assertThrows(IllegalArgumentException::class.java) { pack(source, local.resolve(archive.path), ArchiveWriter.Level.STORE) }
        assertEquals("original", archive.readText())
        assertFalse(File(tmp.root, "old.tar.gz.twigpart").exists())
    }

    @Test fun passwordsAreRejectedBeforeOpeningTheOutput() {
        val source = tmp.newFile("input")
        val archive = tmp.newFile("old.tar.gz").apply { writeText("original") }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveWriter.compress(listOf(local.resolve(source.path)), local.resolve(tmp.root.path), local.resolve(archive.path),
                ArchiveWriter.Format.TAR_GZ, password = "secret")
        }
        assertEquals("original", archive.readText())
        assertFalse(File(tmp.root, "old.tar.gz.twigpart").exists())
    }
}
