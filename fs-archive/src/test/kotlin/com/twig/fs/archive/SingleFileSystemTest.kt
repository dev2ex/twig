package com.twig.fs.archive

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

class SingleFileSystemTest {

    private lateinit var tmp: File
    private val gz = SingleFileSystem.gzip()
    private val xz = SingleFileSystem.xz()
    private val bz2 = SingleFileSystem.bzip2()

    /**
     * Identity decoder: the real one is JNI in `:fs-zstd` and this module is plain JVM,
     * so what is testable here is the wiring — that the host stream is opened and handed
     * to whatever was injected — plus the size parsing, which is ours and needs no codec.
     */
    private val zst = SingleFileSystem.zstd { it }

    /** Long enough that "size" cannot accidentally match the compressed length. */
    private val payload = "twig single-file compression\n".repeat(500)

    @Before
    fun setup() {
        FsRegistry.register(LocalFileSystem())
        FsRegistry.register(gz)
        FsRegistry.register(xz)
        FsRegistry.register(bz2)
        FsRegistry.register(zst)
        tmp = File.createTempFile("twigsingle", "").let { it.delete(); it.mkdirs(); it }
    }

    private fun local(f: File) = XFile("file", f.absolutePath, false)

    private fun gzFile(name: String, content: String = payload, mtime: Long = 0L): File {
        val f = File(tmp, name)
        if (mtime > 0) {
            GzipCompressorOutputStream(FileOutputStream(f), GzipParameters().apply { modificationTime = mtime })
                .use { it.write(content.toByteArray()) }
        } else {
            GZIPOutputStream(FileOutputStream(f)).use { it.write(content.toByteArray()) }
        }
        return f
    }

    private fun xzFile(name: String, content: String = payload): File =
        File(tmp, name).also { f ->
            XZOutputStream(FileOutputStream(f), LZMA2Options()).use { it.write(content.toByteArray()) }
        }

    private fun bz2File(name: String, content: String = payload): File =
        File(tmp, name).also { f ->
            BZip2CompressorOutputStream(FileOutputStream(f)).use { it.write(content.toByteArray()) }
        }

    /** The single entry is named after the host with the compression extension gone. */
    @Test
    fun mountsAsOneEntryNamedAfterTheHost() {
        val root = gz.rootOf(local(gzFile("notes.txt.gz")))
        val rows = gz.list(root)
        assertEquals(listOf("notes.txt"), rows.map { it.name })
        assertTrue(!rows.first().isDir)
        assertEquals(payload, gz.openInput(rows.first()).bufferedReader().use { it.readText() })
    }

    /** gzip: ISIZE in the trailer, so the real size shows without decompressing. */
    @Test
    fun gzipReportsUncompressedSize() {
        val f = gzFile("a.txt.gz")
        val row = gz.list(gz.rootOf(local(f))).first()
        assertEquals(payload.toByteArray().size.toLong(), row.size)
        assertTrue("compressed file should be smaller than its content", f.length() < row.size)
    }

    /** xz: exact, from the index the stream footer points at. */
    @Test
    fun xzReportsUncompressedSize() {
        val row = xz.list(xz.rootOf(local(xzFile("a.txt.xz")))).first()
        assertEquals(payload.toByteArray().size.toLong(), row.size)
        assertEquals(payload, xz.openInput(row).bufferedReader().use { it.readText() })
    }

    /**
     * bz2: the format stores no uncompressed size at all, so it stays unknown (0) —
     * we do not decompress the whole archive just to fill that field in.
     */
    @Test
    fun bzip2LeavesSizeUnknownButStillReads() {
        val row = bz2.list(bz2.rootOf(local(bz2File("a.txt.bz2")))).first()
        assertEquals(0L, row.size)
        assertEquals(payload, bz2.openInput(row).bufferedReader().use { it.readText() })
    }

    /** gzip carries the original file's mtime in its header; prefer it over the host's. */
    @Test
    fun gzipUsesHeaderModificationTime() {
        val stamp = 1_600_000_000_000L // seconds resolution in the header, so keep it round
        val f = gzFile("dated.txt.gz", mtime = stamp)
        f.setLastModified(stamp + 86_400_000L) // host mtime is deliberately different
        assertEquals(stamp, gz.list(gz.rootOf(local(f))).first().lastModified)
    }

    /** Concatenated members (`cat a.gz b.gz`) must read as one stream, not just the first. */
    @Test
    fun readsConcatenatedGzipMembers() {
        val joined = File(tmp, "joined.txt.gz")
        joined.outputStream().use { out ->
            out.write(gzFile("p1.gz", "first\n").readBytes())
            out.write(gzFile("p2.gz", "second\n").readBytes())
        }
        val row = gz.list(gz.rootOf(local(joined))).first()
        assertEquals("first\nsecond\n", gz.openInput(row).bufferedReader().use { it.readText() })
    }

    // ---- zstd ----
    //
    // The frame headers below are the real thing: produced by /usr/bin/zstd and pasted in
    // as their first 18 bytes, so they can fail if the parser's idea of the layout drifts
    // from the format. Reproduce with:
    //     zstd -q -f <file> -o out.zst && head -c 18 out.zst | xxd -p
    private fun zstdHead(hex: String, name: String): File =
        File(tmp, name).also { it.writeBytes(hex.chunked(2).map { b -> b.toInt(16).toByte() }.toByteArray()) }

    /** Every width of the content-size field, straight from real archives. */
    @Test
    fun zstdReadsContentSizeFromTheFrameHeader() {
        // single-segment, 1-byte size
        assertEquals(100L, sizeOfZst("28b52ffd2464210300664c502b577a497666", "a.bin.zst"))
        // 2-byte size, stored biased by 256 (0x02e8 = 744, + 256 = 1000)
        assertEquals(1000L, sizeOfZst("28b52ffd64e802ad1800867ec4190045de23", "b.bin.zst"))
        // 4-byte size
        assertEquals(100_000L, sizeOfZst("28b52ffda4a0860100b533090e6a189c4915", "c.bin.zst"))
        // single-segment *off*: a window descriptor sits before the size field
        assertEquals(8_572_410L, sizeOfZst("28b52ffd8458facd8200444a004ab4881314", "d.bin.zst"))
    }

    /** `… | zstd` cannot know the length, leaves the field out, and we must say unknown. */
    @Test
    fun zstdFromAPipeHasNoSize() {
        assertEquals(0L, sizeOfZst("28b52ffd0458b5180086bec41c10c907e7b0", "piped.bin.zst"))
    }

    /** A seekable-zstd file can lead with skippable frames; step over them. */
    @Test
    fun zstdSkipsLeadingSkippableFrames() {
        val skippable = "502a4d18" + "04000000" + "deadbeef" // magic, 4-byte payload, payload
        assertEquals(100L, sizeOfZst(skippable + "28b52ffd2464210300664c502b577a497666", "skip.bin.zst"))
    }

    private fun sizeOfZst(hex: String, name: String): Long =
        zst.list(zst.rootOf(local(zstdHead(hex, name)))).single().size

    /** The injected decoder is what actually produces the bytes. */
    @Test
    fun zstdReadsThroughTheInjectedDecoder() {
        val raw = "28b52ffd2464210300664c502b577a497666"
        val f = zstdHead(raw, "wired.txt.zst")
        val row = zst.list(zst.rootOf(local(f))).single()
        assertEquals("wired.txt", row.name)
        // identity decoder in, so what comes out is the host's own bytes
        assertEquals(raw, zst.openInput(row).use { it.readBytes() }.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun tzstMapsToZstdAndKeepsTheTarSuffix() {
        assertEquals(SingleFileSystem.ZSTD_SCHEME, Archives.schemeFor(XFile("file", "/x/a.tzst", false)))
        val f = zstdHead("28b52ffd2464210300664c502b577a497666", "bundle.tzst")
        assertEquals("bundle.tar", zst.list(zst.rootOf(local(f))).single().name)
    }

    /**
     * `.tgz` and friends fold the "tar" into the extension, so the entry name has to get
     * it back — otherwise the second layer sees "bundle" and does not recognise a tar.
     */
    @Test
    fun tarShorthandExtensionsKeepTheTarSuffix() {
        val tgz = File(tmp, "bundle.tgz")
        GZIPOutputStream(FileOutputStream(tgz)).use { it.write(payload.toByteArray()) }
        assertEquals("bundle.tar", gz.list(gz.rootOf(local(tgz))).single().name)

        val txz = File(tmp, "bundle.txz")
        XZOutputStream(FileOutputStream(txz), LZMA2Options()).use { it.write(payload.toByteArray()) }
        assertEquals("bundle.tar", xz.list(xz.rootOf(local(txz))).single().name)

        val tbz2 = File(tmp, "bundle.tbz2")
        BZip2CompressorOutputStream(FileOutputStream(tbz2)).use { it.write(payload.toByteArray()) }
        assertEquals("bundle.tar", bz2.list(bz2.rootOf(local(tbz2))).single().name)
    }

    /** Every shorthand must route to the codec it actually is. */
    @Test
    fun tarShorthandExtensionsMapToTheRightScheme() {
        fun schemeOf(name: String) = Archives.schemeFor(XFile("file", "/x/$name", false))
        assertEquals(SingleFileSystem.GZIP_SCHEME, schemeOf("a.tgz"))
        assertEquals(SingleFileSystem.XZ_SCHEME, schemeOf("a.txz"))
        assertEquals(SingleFileSystem.BZIP2_SCHEME, schemeOf("a.tbz"))
        assertEquals(SingleFileSystem.BZIP2_SCHEME, schemeOf("a.tbz2"))
    }

    /**
     * The whole point of the one-entry mount: `foo.tar.gz` opens to `foo.tar`, and that
     * entry mounts again as a real tar. No special case for tar.gz anywhere — just two
     * mounts stacked, with the gz entry acting as the tar's host.
     */
    @Test
    fun tarGzMountsThroughTwoLayers() {
        val tarBytes = java.io.ByteArrayOutputStream().also { bos ->
            TarArchiveOutputStream(bos).use { out ->
                val bytes = "inside\n".toByteArray()
                out.putArchiveEntry(TarArchiveEntry("inner.txt").apply { size = bytes.size.toLong() })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }.toByteArray()

        val tarFs = TarFileSystem()
        FsRegistry.register(tarFs)

        // Spelled either way, the two layers stack the same
        for (name in listOf("bundle.tar.gz", "shorthand.tgz")) {
            val f = File(tmp, name)
            GZIPOutputStream(FileOutputStream(f)).use { it.write(tarBytes) }

            val innerTar = gz.list(gz.rootOf(local(f))).single()
            assertEquals(name.substringBefore('.') + ".tar", innerTar.name)
            assertEquals(TarFileSystem.SCHEME, Archives.schemeFor(innerTar))

            val rows = tarFs.list(tarFs.rootOf(innerTar)) // host is the gz entry, not a local file
            assertEquals(listOf("inner.txt"), rows.map { it.name })
            assertEquals("inside\n", tarFs.openInput(rows.first()).bufferedReader().use { it.readText() })
        }
    }
}
