package com.twig.fs.archive

import com.twig.core.FsException
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.tukaani.xz.SeekableInputStream
import org.tukaani.xz.SeekableXZInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.util.zip.GZIPInputStream

/**
 * Single-file compression: `.gz` / `.xz` / `.bz2`.
 *
 * These are not archives. There is one data stream inside and nothing else — no file
 * names, no directories, no table of contents. So they are mounted as "an archive with
 * exactly one entry", named after the host with the compression extension stripped:
 * `foo.tar.gz` opens to `foo.tar`, and expanding *that* gives the real tar. Two nested
 * mounts are all tar.gz needs, with no special case anywhere — and the app layer already
 * materialises a nested archive whose host cannot seek cheaply
 * (`PaneViewModel.archiveTarget`, keyed off [fastRandom] being false), so the inner tar
 * is walked over a real local file instead of re-inflating the gzip stream per seek.
 *
 * **Whether the uncompressed size is free depends on the format, and all three answer
 * differently.** Everything below reads a handful of bytes from the head or tail and
 * decompresses nothing:
 *  - **gz**: the trailing 4-byte ISIZE. It is stored mod 2^32, so anything over 4 GiB
 *    wraps and the format itself cannot tell you that it did. `gzip -l` is wrong in
 *    exactly the same way; we are wrong with it rather than inventing a heuristic that
 *    guesses right slightly more often and wrong unpredictably.
 *  - **xz**: the stream footer points back at the index, which records the uncompressed
 *    size of every block. Exact 64-bit value, always present.
 *  - **bz2**: the format has no such field at all — the only way to learn the size is to
 *    decompress the whole thing and count. We report unknown instead. Pulling an entire
 *    remote archive over the network to display one number is not a trade worth making.
 *  - **zst**: the frame header carries an optional Frame_Content_Size. `zstd file` writes
 *    it because it knows the length up front; `… | zstd` cannot and leaves it out, and
 *    then it stays unknown. Parsed here by hand rather than through the JNI layer — it
 *    is a dozen bytes of header, and doing it in Kotlin keeps it unit-testable off-device
 *    and keeps the size path working even in a build with no native library loaded.
 *
 * Unknown size surfaces as 0 (the base class' [fileXFile] floors negatives). That is
 * only cosmetic plus a copy progress bar that cannot fill: [com.twig.core.CopyEngine]
 * uses the size for progress and never validates against it.
 */
class SingleFileSystem private constructor(
    override val scheme: String,
    override val displayName: String,
    private val codec: Codec,
    /** Only zstd needs one; the other three decode with what the JDK and our existing
     *  dependencies already provide. See [zstd]. */
    private val decoder: Decoder? = null,
) : ArchiveFileSystem() {

    enum class Codec { GZIP, XZ, BZIP2, ZSTD }

    /**
     * Supplies the decompressing stream for a codec this module cannot implement itself.
     * zstd lives behind JNI in `:fs-zstd`, an Android library — and this module is plain
     * JVM (that is what keeps its tests millisecond-fast), so it cannot depend on it.
     * The app injects `ZstdInputStream` here at registration time.
     */
    fun interface Decoder {
        fun wrap(input: InputStream): InputStream
    }

    override fun writable(): Boolean = false

    override fun readEntries(archivePath: String): List<ArchiveEntry> = listOf(
        ArchiveEntry(
            name = innerName(archivePath),
            isDir = false,
            size = sizeOf(archivePath),
            time = timeOf(archivePath),
        ),
    )

    override fun openEntry(archivePath: String, inner: String): InputStream {
        if (inner != innerName(archivePath)) throw FsException("No such file: $inner")
        // Sequential read of the host; Channels.newInputStream keeps one code path for
        // local files and remote hosts alike. 64 KB on top because all three decoders
        // pull in small bites and the remote channel would otherwise round-trip per bite.
        val ins = BufferedInputStream(Channels.newInputStream(openChannel(archivePath)), 1 shl 16)
        return try {
            when (codec) {
                // All three read concatenated streams (`cat a.gz b.gz` is legal for every
                // one of these formats, and pbzip2/pixz produce multi-stream output).
                Codec.GZIP -> GZIPInputStream(ins, 1 shl 16)
                Codec.XZ -> XZCompressorInputStream(ins, true)
                Codec.BZIP2 -> BZip2CompressorInputStream(ins, true)
                // Concatenated frames are handled inside ZstdInputStream's decoder context
                Codec.ZSTD -> (decoder ?: throw FsException("zstd support is not available"))
                    .wrap(ins)
            }
        } catch (t: Throwable) {
            ins.close()
            throw t
        }
    }

    /** Host name minus the compression extension; that is the single entry's name. */
    private fun innerName(archivePath: String): String {
        val host = archivePath.trimEnd('/').substringAfterLast('/')
        val stripped = host.substringBeforeLast('.', "")
        // A file literally called ".gz" leaves nothing behind — give it something to show
        if (stripped.isEmpty()) return "data"
        // `.tgz` and friends fold "tar" into the extension, so put it back: the entry has
        // to be named foo.tar for the tar layer to recognise it on the way in.
        val ext = host.substringAfterLast('.', "").lowercase()
        return if (ext in TAR_SHORTHAND) "$stripped.tar" else stripped
    }

    // ---- uncompressed size (see the class comment: three formats, three answers) ----

    private fun sizeOf(archivePath: String): Long = when (codec) {
        Codec.GZIP -> gzipIsize(archivePath)
        Codec.XZ -> xzLength(archivePath)
        Codec.BZIP2 -> -1L
        Codec.ZSTD -> zstdContentSize(archivePath)
    }

    /** RFC 1952: the last 4 bytes are ISIZE, little endian, mod 2^32. */
    private fun gzipIsize(archivePath: String): Long = runCatching {
        openChannel(archivePath).use { ch ->
            val len = ch.size()
            if (len < MIN_GZIP) return@use -1L
            ch.position(len - 4)
            val buf = readFully(ch, 4) ?: return@use -1L
            var v = 0L
            for (i in 0 until 4) v = v or ((buf[i].toLong() and 0xFF) shl (8 * i))
            v
        }
    }.getOrDefault(-1L)

    /** The xz index carries every block's uncompressed size; the library sums them. */
    private fun xzLength(archivePath: String): Long = runCatching {
        openChannel(archivePath).use { ch ->
            SeekableXZInputStream(ChannelSeekableInput(ch)).use { it.length() }
        }
    }.getOrDefault(-1L)

    /**
     * zstd's Frame_Content_Size, straight out of the frame header (RFC 8878 §3.1.1):
     *
     * ```
     * magic (4) | descriptor (1) | window (0..1) | dict id (0..4) | content size (0..8)
     * ```
     *
     * The descriptor says how wide each of the variable fields is; a content-size width
     * of 0 means the compressor did not know the length (piped input) and the answer is
     * unknown. ★ The 2-byte form is stored biased by 256 — miss that and every file
     * between 256 B and 64 KB reads 256 bytes short.
     *
     * Like gzip's ISIZE this describes the **first** frame only. Concatenated frames
     * would need every block header walked to find where the next frame starts, and those
     * are spread across the whole file — the same "one walk per read" trap tar had. Plain
     * `zstd file` produces a single frame, which is what this is for.
     */
    private fun zstdContentSize(archivePath: String): Long = runCatching {
        openChannel(archivePath).use { ch ->
            var start = 0L
            // A seekable-zstd file may lead with skippable frames; step over them
            repeat(4) {
                ch.position(start)
                val head = readFully(ch, ZSTD_HEAD) ?: return@use -1L
                val magic = le(head, 0, 4)
                if (magic in SKIPPABLE_MIN..SKIPPABLE_MAX) {
                    start += 8 + le(head, 4, 4)
                    return@repeat
                }
                if (magic != ZSTD_MAGIC) return@use -1L

                val d = head[4].toInt() and 0xFF
                val fcsFlag = (d shr 6) and 3
                val singleSegment = (d shr 5) and 1
                val didSize = intArrayOf(0, 1, 2, 4)[d and 3]
                val fcsSize = when (fcsFlag) {
                    0 -> singleSegment // 0 here means "no field at all" unless single-segment
                    1 -> 2
                    2 -> 4
                    else -> 8
                }
                if (fcsSize == 0) return@use -1L // compressor did not know the length

                val at = 5 + (1 - singleSegment) + didSize // window descriptor only when not single-segment
                val v = le(head, at, fcsSize)
                return@use if (fcsSize == 2) v + 256 else v
            }
            -1L
        }
    }.getOrDefault(-1L)

    /** Little-endian unsigned read of [n] bytes (n <= 8). */
    private fun le(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /**
     * Entry time. gzip stores the *original* file's mtime in its header (RFC 1952,
     * bytes 4..7, 0 when unset) — better than the host's own timestamp, which is when
     * the archive was written. xz and bz2 store nothing, so they fall back to the host.
     */
    private fun timeOf(archivePath: String): Long {
        if (codec == Codec.GZIP) {
            val t = runCatching {
                openChannel(archivePath).use { ch ->
                    if (ch.size() < MIN_GZIP) return@use 0L
                    ch.position(4)
                    val buf = readFully(ch, 4) ?: return@use 0L
                    var v = 0L
                    for (i in 0 until 4) v = v or ((buf[i].toLong() and 0xFF) shl (8 * i))
                    v * 1000L
                }
            }.getOrDefault(0L)
            if (t > 0) return t
        }
        val host = hostOf(archivePath)
        return if (host.scheme == HOST_SCHEME) java.io.File(archivePath).lastModified()
        else host.lastModified
    }

    private fun readFully(ch: SeekableByteChannel, n: Int): ByteArray? {
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) if (ch.read(buf) <= 0) return null
        return buf.array()
    }

    /** Adapts our seekable channel to the xz library's own seekable-stream abstraction. */
    private class ChannelSeekableInput(private val ch: SeekableByteChannel) : SeekableInputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = ch.read(ByteBuffer.wrap(b, off, len))
            return if (n <= 0) -1 else n
        }

        override fun length(): Long = ch.size()
        override fun position(): Long = ch.position()
        override fun seek(pos: Long) { ch.position(pos) }
        override fun close() = ch.close()
    }

    companion object {
        const val GZIP_SCHEME = "gz"
        const val XZ_SCHEME = "xz"
        const val BZIP2_SCHEME = "bz2"
        const val ZSTD_SCHEME = "zst"

        /** Header (10) + deflate block (2) + CRC and ISIZE (8) — nothing valid is smaller. */
        private const val MIN_GZIP = 20L

        /** Widest possible zstd frame header: magic + descriptor + window + dict + size. */
        private const val ZSTD_HEAD = 18
        private const val ZSTD_MAGIC = 0xFD2FB528L
        private const val SKIPPABLE_MIN = 0x184D2A50L
        private const val SKIPPABLE_MAX = 0x184D2A5FL

        /**
         * Extensions that fold "tar" into themselves. Declared here rather than in
         * [Archives] because both sides of the rule live here: the scheme they mount as,
         * and the ".tar" that [innerName] has to hand back. Archives builds its table
         * from this map, so adding one is a single edit.
         */
        val TAR_SHORTHAND = mapOf(
            "tgz" to GZIP_SCHEME,
            "txz" to XZ_SCHEME,
            "tbz" to BZIP2_SCHEME,
            "tbz2" to BZIP2_SCHEME,
            "tzst" to ZSTD_SCHEME,
        )

        fun gzip() = SingleFileSystem(GZIP_SCHEME, "Gzip", Codec.GZIP)
        fun xz() = SingleFileSystem(XZ_SCHEME, "XZ", Codec.XZ)
        fun bzip2() = SingleFileSystem(BZIP2_SCHEME, "Bzip2", Codec.BZIP2)

        /**
         * Whether a size of 0 from this scheme means "the container never stated it"
         * rather than "the file is empty" — the UI hides the figure instead of printing
         * a misleading `0 B`.
         *
         * bz2 has no such field at all. zstd has one, but it sits in the *frame header*,
         * so anything compressed from a pipe (`tar --zstd -cf`, the usual way a .tar.zst
         * is made) leaves it empty — the length is not known yet when the header is
         * written. gz and xz are absent from this list on purpose: their length lives at
         * the *end* of the stream, written once compression is done, so even piped output
         * carries it and a 0 there really is an empty file.
         */
        fun sizeMayBeUnknown(scheme: String): Boolean =
            scheme == BZIP2_SCHEME || scheme == ZSTD_SCHEME

        /** zstd needs its decoder handed in — see [Decoder]. */
        fun zstd(decoder: Decoder) = SingleFileSystem(ZSTD_SCHEME, "Zstd", Codec.ZSTD, decoder)
    }
}
