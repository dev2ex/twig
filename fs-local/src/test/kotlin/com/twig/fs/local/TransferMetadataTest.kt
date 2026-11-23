package com.twig.fs.local

import com.twig.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class TransferMetadataTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var fs: LocalFileSystem
    private val written = mutableListOf<Pair<String, TransferAttributes>>()

    private fun stat(path: String): UnixMetadata {
        val attrs = Files.readAttributes(Path.of(path), "unix:*", NOFOLLOW_LINKS)
        fun n(key: String) = (attrs.getValue(key) as Number).toLong()
        fun t(key: String) = (attrs.getValue(key) as FileTime).toMillis()
        return UnixMetadata(n("mode").toInt(), n("uid"), n("gid"), n("size"),
            t("lastModifiedTime"), t("lastAccessTime"), t("ctime"))
    }

    @Before fun setup() {
        fs = LocalFileSystem(readUnix = ::stat, readLink = { path ->
            if (Files.isSymbolicLink(Path.of(path))) Files.readSymbolicLink(Path.of(path)).toString() else null
        }, writeMetadata = { path, attributes ->
            written += path to attributes
            val current = stat(path)
            val owner = (attributes.uid == null || attributes.uid == current.uid) && (attributes.gid == null || attributes.gid == current.gid)
            if (!current.isSymbolicLink) {
                attributes.mode?.let { Files.setAttribute(Path.of(path), "unix:mode", it and 0xFFF, NOFOLLOW_LINKS) }
                attributes.modified?.let { Files.setLastModifiedTime(Path.of(path), FileTime.fromMillis(it)) }
            }
            MetadataResult(owner, attributes.modified != null && !current.isSymbolicLink)
        }, writeLink = { path, target -> Files.createSymbolicLink(Path.of(path), Path.of(target)); true })
        FsRegistry.register(fs)
    }

    @Test fun directoryPermissionsAndTimesAreAppliedAfterChildrenAndLinksAreNotFollowed() {
        val src = tmp.newFolder("source")
        val data = File(src, "run").apply { writeText("executable") }
        Files.setAttribute(data.toPath(), "unix:mode", 0x1E9) // 0751.
        data.setLastModified(1600000000000)
        Files.createSymbolicLink(File(src, "cycle").toPath(), Path.of("."))
        Files.setAttribute(src.toPath(), "unix:mode", 0x168) // 0550.
        src.setLastModified(1600000100000)
        val out = tmp.newFolder("out")
        val plan = CopyEngine.plan(listOf(fs.resolve(src.path)))
        assertEquals(data.length(), plan.bytes)
        assertEquals(2, plan.files)
        CopyEngine.transfer(listOf(fs.resolve(src.path)), fs.resolve(out.path), false, checkpoint = TransferCheckpoint())
        assertEquals("executable", File(out, "source/run").readText())
        assertEquals(0x1E9, stat(File(out, "source/run").path).mode and 0xFFF)
        assertEquals(1600000000000L, File(out, "source/run").lastModified())
        assertEquals(0x168, stat(File(out, "source").path).mode and 0xFFF)
        assertEquals(1600000100000L, File(out, "source").lastModified())
        assertEquals(Path.of("."), Files.readSymbolicLink(File(out, "source/cycle").toPath()))
        assertEquals(File(out, "source").path, written.last().first)
        Files.setAttribute(src.toPath(), "unix:mode", 0x1ED)
        Files.setAttribute(File(out, "source").toPath(), "unix:mode", 0x1ED)
    }

    @Test fun resumableOverwriteUsesSourcePermissionsAndSafeReplacementKeepsExistingPermissions() {
        val src = tmp.newFile("source").apply { writeText("new payload"); setLastModified(1600000000000) }
        Files.setAttribute(src.toPath(), "unix:mode", 0x1E8)
        val out = tmp.newFolder("out")
        val target = File(out, "source").apply { writeText("old") }
        Files.setAttribute(target.toPath(), "unix:mode", 0x180)
        CopyEngine.transfer(listOf(fs.resolve(src.path)), fs.resolve(out.path), false, checkpoint = TransferCheckpoint())
        assertEquals("new payload", target.readText())
        assertEquals(0x1E8, stat(target.path).mode and 0xFFF)
        assertEquals(1600000000000L, target.lastModified())
        SafeWrite.replace(fs, fs.resolve(out.path), target.name, fs.resolve(target.path)) { it.write("edited".toByteArray()) }
        assertEquals("edited", target.readText())
        assertEquals(0x1E8, stat(target.path).mode and 0xFFF)
        assertEquals(listOf("source"), out.list()!!.toList())
    }

    @Test fun crossDomainCopiesKeepModeAndTimeWithoutApplyingNumericOwnership() {
        val src = tmp.newFile("cross").apply { writeText("data"); setLastModified(1600000000000) }
        val out = tmp.newFolder("out")
        val source = object : FileSystem by fs, UnixMetadataSource {
            override val scheme = "other-host"
            override val ownershipDomain = "other-host"
            override fun unixMetadata(file: XFile, includeContext: Boolean) = this@TransferMetadataTest.stat(file.path).copy(uid = 900001, gid = 900002)
            override fun openInput(file: XFile) = fs.openInput(file.copy(scheme = "file"))
        }
        FsRegistry.register(source)
        try {
            CopyEngine.transfer(listOf(fs.resolve(src.path).copy(scheme = source.scheme)), fs.resolve(out.path), false)
            val applied = written.single().second
            assertNull(applied.uid)
            assertNull(applied.gid)
            assertEquals(stat(src.path).mode, applied.mode)
            assertEquals(src.lastModified(), File(out, "cross").lastModified())
        } finally { FsRegistry.unregister(source.scheme) }
    }

    @Test fun replacingWithoutSourceAttributesKeepsTheExistingModeAndOwner() {
        val src = tmp.newFile("payload").apply { writeText("new") }
        val out = tmp.newFolder("out")
        val target = File(out, "payload").apply { writeText("old") }
        Files.setAttribute(target.toPath(), "unix:mode", 0x1C0)
        val source = object : FileSystem by fs {
            override val scheme = "no-attributes"
            override fun openInput(file: XFile) = fs.openInput(file.copy(scheme = "file"))
        }
        FsRegistry.register(source)
        try {
            CopyEngine.transfer(listOf(XFile(source.scheme, src.path, false, src.length())), fs.resolve(out.path), false, checkpoint = TransferCheckpoint())
            assertEquals("new", target.readText())
            assertEquals(0x1C0, stat(target.path).mode and 0xFFF)
            assertEquals(stat(target.path).uid, written.last().second.uid)
        } finally { FsRegistry.unregister(source.scheme) }
    }

    @Test fun refusingMetadataRetainsTheContentAndReportsPartialPreservation() {
        val src = tmp.newFile("data").apply { writeText("keep content") }
        val out = tmp.newFolder("out")
        val destination = object : FileSystem by fs, UnixMetadataWrite {
            override fun applyMetadata(file: XFile, attributes: TransferAttributes) = MetadataResult(false)
            override fun createSymbolicLink(parent: XFile, name: String, target: String) = fs.createSymbolicLink(parent, name, target)
        }
        FsRegistry.register(destination)
        var warnings = 0
        CopyEngine.transfer(listOf(fs.resolve(src.path)), fs.resolve(out.path), false,
            listener = object : CopyEngine.ProgressListener { override fun onMetadataNotPreserved(file: XFile) { warnings++ } })
        assertEquals("keep content", File(out, "data").readText())
        assertEquals(1, warnings)
    }

    @Test fun movesKeepTheSourceWhenAttributesFailAndRetryRestoresThemWithoutRecopying() {
        val source = tmp.newFolder("move-source")
        val out = tmp.newFolder("move-out")
        val data = File(source, "data").apply { writeText("retain metadata"); setLastModified(1600000000000) }
        Files.setAttribute(data.toPath(), "unix:mode", 0x1E8)
        var reads = 0
        var fail = true
        val sourceFs = object : FileSystem by fs, UnixMetadataSource by fs {
            override val scheme = "move-attributes"
            override fun stat(path: String) = fs.stat(path)?.copy(scheme = scheme)
            override fun delete(file: XFile) = fs.delete(file.copy(scheme = "file"))
            override fun unixMetadata(file: XFile, includeContext: Boolean) = fs.unixMetadata(file.copy(scheme = "file"), includeContext)
            override fun openInput(file: XFile) = fs.openInput(file.copy(scheme = "file")).also { reads++ }
        }
        val destination = object : FileSystem by fs, UnixMetadataWrite, UnixMetadataSource by fs {
            override fun applyMetadata(file: XFile, attributes: TransferAttributes) =
                if (fail) MetadataResult(false) else fs.applyMetadata(file, attributes)
            override fun createSymbolicLink(parent: XFile, name: String, target: String) = fs.createSymbolicLink(parent, name, target)
        }
        FsRegistry.register(sourceFs); FsRegistry.register(destination)
        try {
            val checkpoint = TransferCheckpoint()
            val item = fs.resolve(data.path).copy(scheme = sourceFs.scheme)
            CopyEngine.transfer(listOf(item), fs.resolve(out.path), true, checkpoint = checkpoint)
            assertTrue(data.exists())
            assertEquals("retain metadata", File(out, "data").readText())
            assertEquals(1, reads)
            assertEquals(1, checkpoint.metadataFailures.size)
            fail = false
            CopyEngine.transfer(listOf(item), fs.resolve(out.path), true, checkpoint = checkpoint)
            assertFalse(data.exists())
            assertEquals(1, reads)
            assertTrue(checkpoint.metadataFailures.isEmpty())
            assertEquals(0x1E8, stat(File(out, "data").path).mode and 0xFFF)
        } finally { FsRegistry.unregister(sourceFs.scheme) }
    }

    @Test fun replacingLinksNeverWritesOrChangesPermissionsOnTheirTargets() {
        val outside = tmp.newFile("outside").apply { writeText("untouched") }
        val src = tmp.newFolder("source")
        val out = tmp.newFolder("out")
        Files.createSymbolicLink(File(src, "link").toPath(), Path.of("../elsewhere"))
        Files.createSymbolicLink(File(out, "link").toPath(), outside.toPath())
        val mode = stat(outside.path).mode
        CopyEngine.transfer(fs.list(fs.resolve(src.path)), fs.resolve(out.path), false)
        assertEquals(Path.of("../elsewhere"), Files.readSymbolicLink(File(out, "link").toPath()))
        assertEquals("untouched", outside.readText())
        assertEquals(mode, stat(outside.path).mode)
    }
}
