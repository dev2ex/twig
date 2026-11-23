package com.twig.fs.archive

import com.twig.core.*
import com.twig.fs.local.LocalFileSystem
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream

class StreamingTarTest {
    @get:Rule val tmp = TemporaryFolder()
    private val local = LocalFileSystem()

    @Before fun setup() {
        FsRegistry.register(local)
        FsRegistry.register(SingleFileSystem.gzip())
        FsRegistry.register(SingleFileSystem.xz())
        FsRegistry.register(SingleFileSystem.bzip2())
    }

    private fun archive(name: String = "bundle.tar.gz", entries: List<Pair<String, ByteArray>>): File {
        val file = tmp.newFile(name)
        val output = file.outputStream()
        val compressed = when {
            name.endsWith("xz") -> XZCompressorOutputStream(output)
            name.endsWith("bz2") -> BZip2CompressorOutputStream(output)
            else -> GZIPOutputStream(output)
        }
        TarArchiveOutputStream(compressed).use { tar ->
            for ((path, bytes) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(path).apply { size = if (isDirectory) 0 else bytes.size.toLong() })
                if (!path.endsWith('/')) tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    private class SequentialHost(private val file: File) : FileSystem by LocalFileSystem() {
        override val scheme = "sequential-tar-host"
        var opens = 0
        var closes = 0
        var bytesRead = 0L
        override fun openRandom(file: XFile): RandomSource = error("Streaming extraction must not seek")
        override fun openInput(file: XFile): InputStream {
            opens++
            return object : FilterInputStream(this@SequentialHost.file.inputStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also {
                    if (it > 0) bytesRead += it
                }
                override fun read(): Int = `in`.read().also { if (it >= 0) bytesRead++ }
                override fun close() { super.close(); closes++ }
            }
        }
    }

    @Test fun interleavedDirectoriesExtractInOneForwardPassWithoutSeeking() {
        val bytes = ByteArray(300_000).also { java.util.Random(42).nextBytes(it) }
        val file = archive(entries = listOf("./one/a" to bytes, "two/b" to bytes, "one/c" to bytes, "empty/" to byteArrayOf()))
        val host = SequentialHost(file)
        FsRegistry.register(host)
        val original = local.resolve(file.path).copy(scheme = host.scheme)
        val dest = tmp.newFolder("out")
        CopyEngine.transferSequential(StreamingTar.open(original), local.resolve(dest.path), checkpoint = TransferCheckpoint())
        for (name in listOf("one/a", "two/b", "one/c")) assertArrayEquals(bytes, File(dest, name).readBytes())
        assertTrue(File(dest, "empty").isDirectory)
        assertEquals(1, host.opens)
        assertEquals(host.opens, host.closes)
        assertEquals(file.length(), host.bytesRead)
    }

    @Test fun probeStopsAtTheSecondRootWithoutReadingTheWholeArchive() {
        val bytes = ByteArray(2_000_000).also { java.util.Random(42).nextBytes(it) }
        val file = archive(entries = listOf("one" to byteArrayOf(1), "two" to bytes))
        val host = SequentialHost(file)
        FsRegistry.register(host)
        val probe = StreamingTar.probe(local.resolve(file.path).copy(scheme = host.scheme))
        assertEquals(listOf("one", "two"), probe.roots)
        assertFalse(probe.complete)
        assertTrue(host.bytesRead < file.length() / 4)
        assertEquals(host.opens, host.closes)
    }

    @Test fun singleRootProbeCompletesAndDotPrefixesDoNotCountAsFolders() {
        val file = archive(entries = listOf("./" to byteArrayOf(), "./one/a" to byteArrayOf(1), "one/b" to byteArrayOf(2)))
        val probe = StreamingTar.probe(local.resolve(file.path))
        assertEquals(listOf("one"), probe.roots)
        assertTrue(probe.complete)
    }

    @Test fun supportedCompressionCodecsUseTheSameSequentialExtraction() {
        for (name in listOf("gzip.tar.gz", "lzma.tar.xz", "bzip.tar.bz2")) {
            val file = archive(name, listOf("hello" to "payload".toByteArray()))
            val dest = tmp.newFolder("out-$name")
            CopyEngine.transferSequential(StreamingTar.open(local.resolve(file.path)), local.resolve(dest.path))
            assertEquals("payload", File(dest, "hello").readText())
        }
    }

    @Test fun cancellationKeepsOwnedPartialsAndRetryFinishesWithoutRewritingCompletedEntries() {
        val large = ByteArray(300_000).also { java.util.Random(42).nextBytes(it) }
        val file = archive(entries = listOf("first" to "done".toByteArray(), "large" to large))
        val dest = tmp.newFolder("resume")
        val checkpoint = TransferCheckpoint()
        var cancel = false
        CopyEngine.transferSequential(StreamingTar.open(local.resolve(file.path)), local.resolve(dest.path),
            listener = object : CopyEngine.ProgressListener {
                override fun onFileBytes(copied: Long, size: Long) { if (size == large.size.toLong() && copied > 0) cancel = true }
            }, cancelled = { cancel }, checkpoint = checkpoint)
        assertEquals("done", File(dest, "first").readText())
        assertFalse(checkpoint.partials.isEmpty())
        File(dest, "first").writeText("retained")
        CopyEngine.transferSequential(StreamingTar.open(local.resolve(file.path)), local.resolve(dest.path), checkpoint = checkpoint)
        assertEquals("retained", File(dest, "first").readText())
        assertArrayEquals(large, File(dest, "large").readBytes())
        assertTrue(checkpoint.partials.isEmpty())
    }

    @Test fun cancellationDuringProbeClosesTheCompressedSource() {
        val file = archive(entries = listOf("first" to ByteArray(300_000)))
        val host = SequentialHost(file)
        FsRegistry.register(host)
        assertThrows(CancellationException::class.java) {
            StreamingTar.probe(local.resolve(file.path).copy(scheme = host.scheme)) { host.bytesRead > 0 }
        }
        assertEquals(host.opens, host.closes)
    }

    @Test fun traversalEntriesAreRefusedAndLaterHealthyEntriesStillExtract() {
        val file = archive(entries = listOf("../escape" to byteArrayOf(1), "a/../../escape" to byteArrayOf(2), "safe" to "safe".toByteArray()))
        val dest = tmp.newFolder("safe")
        var refused = 0
        CopyEngine.transferSequential(StreamingTar.open(local.resolve(file.path)), local.resolve(dest.path),
            listener = object : CopyEngine.ProgressListener { override fun onRefused(file: XFile) { refused++ } })
        assertEquals(2, refused)
        assertEquals("safe", File(dest, "safe").readText())
        assertFalse(File(tmp.root, "escape").exists())
    }

    @Test fun badCompressionTrailerFailsEvenAfterTheLastTarEntry() {
        val file = archive(entries = listOf("hello" to "payload".toByteArray()))
        val corrupted = file.readBytes().apply { this[size - 8] = (this[size - 8].toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        val dest = tmp.newFolder("corrupt")
        assertThrows(Exception::class.java) {
            CopyEngine.transferSequential(StreamingTar.open(local.resolve(file.path)), local.resolve(dest.path))
        }
    }

    @Test fun skippingAConflictConsumesItsDataBeforeReadingTheNextHeader() {
        val file = archive(entries = listOf("one" to ByteArray(300_000), "two" to "second".toByteArray()))
        val dest = tmp.newFolder("conflict")
        File(dest, "one").writeText("keep")
        CopyEngine.transferSequential(StreamingTar.open(local.resolve(file.path)), local.resolve(dest.path),
            resolver = CopyEngine.ConflictResolver { _, _ -> CopyEngine.Decision.SKIP })
        assertEquals("keep", File(dest, "one").readText())
        assertEquals("second", File(dest, "two").readText())
    }

    @Test fun duplicateEntriesHaveSeparateCheckpointsAndTheLastOverwriteWins() {
        val file = archive(entries = listOf("same" to "first".toByteArray(), "same" to "final".toByteArray()))
        val dest = tmp.newFolder("duplicates")
        CopyEngine.transferSequential(StreamingTar.open(local.resolve(file.path)), local.resolve(dest.path),
            checkpoint = TransferCheckpoint())
        assertEquals("final", File(dest, "same").readText())
    }

    @Test fun aFailedParentCanBeSkippedOnRetryWithoutBlockingLaterEntries() {
        val file = archive(entries = listOf("blocked/child" to byteArrayOf(1), "healthy" to byteArrayOf(2)))
        val dest = tmp.newFolder("skip-failed")
        File(dest, "blocked").writeText("keep")
        val checkpoint = TransferCheckpoint()
        assertThrows(FsException::class.java) {
            CopyEngine.transferSequential(StreamingTar.open(local.resolve(file.path)), local.resolve(dest.path), checkpoint = checkpoint)
        }
        checkpoint.skip(requireNotNull(checkpoint.failure))
        CopyEngine.transferSequential(StreamingTar.open(local.resolve(file.path)), local.resolve(dest.path), checkpoint = checkpoint)
        assertEquals("keep", File(dest, "blocked").readText())
        assertArrayEquals(byteArrayOf(2), File(dest, "healthy").readBytes())
    }
}
