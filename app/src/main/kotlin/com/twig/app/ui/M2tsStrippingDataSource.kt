package com.twig.app.ui

import android.net.Uri
import android.util.Log
import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.DtsReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.RandomAccessFile

/** Standard TS packet size (bytes). */
const val TS_PACKET_SIZE = 188

/** Raw packet size (bytes) of true BDAV M2TS — 4-byte timestamp prefix + 188-byte standard TS packet. */
const val RAW_M2TS_PACKET_SIZE = 192

/**
 * True M2TS (Blu-ray BDAV container) uses one packet every 192 bytes — a 4-byte timestamp prefix
 * plus a 188-byte standard TS packet, not the pure 188-byte stream that media3's `TsExtractor`
 * hardcodes (its sniff/read step in fixed 188-byte increments looking for a sync byte, so a stream
 * that does not match this layout is reported as "unrecognized container"). This reads while
 * stripping the leading 4 bytes of each packet, feeding the standard TsExtractor a clean
 * 188-byte stream; works for both local and network sources (wraps any [DataSource]).
 */
@UnstableApi
class M2tsStrippingDataSource(
    private val upstream: DataSource,
    /** Total bytes of the underlying raw file (the 192-byte/packet version, before prefix stripping). */
    private val totalRawLength: Long,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var strippedLeft = 0L
    private var posInBlock = 0 // current position within a 192-byte packet, PREFIX..RAW_M2TS_PACKET_SIZE
    private var opened = false
    // Each packet-boundary crossing means skipping a 4-byte prefix; one large read (e.g. a ~110KB
    // seek probe) crosses hundreds of boundaries — reuse this small buffer instead of allocating
    // a fresh ByteArray(PREFIX) for every prefix skip; otherwise the allocation rate is high
    // enough to crush the GC (in practice this — not network slowness — is the real reason seeks
    // are slow and thumbnails time out).
    private val skipBuf = ByteArray(PREFIX)
    private var openAtMs = 0L
    private var sessionBytesRead = 0L
    private var sessionReadCalls = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val block = dataSpec.position / TS_PACKET_SIZE
        val offsetInBlock = (dataSpec.position % TS_PACKET_SIZE).toInt()
        val rawStart = block * RAW_M2TS_PACKET_SIZE + PREFIX + offsetInBlock
        posInBlock = PREFIX + offsetInBlock
        openAtMs = android.os.SystemClock.elapsedRealtime()
        sessionBytesRead = 0L
        sessionReadCalls = 0
        Log.d("twig", "m2ts: open pos=${dataSpec.position} rawStart=$rawStart len=${dataSpec.length}")
        upstream.open(DataSpec.Builder().setUri(dataSpec.uri).setPosition(rawStart).build())
        Log.d("twig", "m2ts: upstream.open done in ${android.os.SystemClock.elapsedRealtime() - openAtMs}ms")
        val strippedTotal = (totalRawLength / RAW_M2TS_PACKET_SIZE) * TS_PACKET_SIZE
        strippedLeft = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
        else (strippedTotal - dataSpec.position).coerceAtLeast(0)
        opened = true
        transferStarted(dataSpec)
        return strippedLeft
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (strippedLeft <= 0L) return C.RESULT_END_OF_INPUT
        // The inner loop tries to fill the caller's requested `length` per call, instead of only
        // returning what's left after a single packet — TsBinarySearchSeeker's seek probe asks
        // for ~110KB at once; at 192 bytes/packet, returning only 188 bytes per read would force
        // the caller to issue hundreds of read() calls per probe. Cutting outer call count is a
        // side benefit; the real root cause of slow seeks was the skipBuf field's absence — each
        // packet-boundary crossing used to allocate a fresh ByteArray(4), and the allocation rate
        // was high enough to crush the GC (in practice logcat showed "Waiting for a blocking GC"
        // continuously for a full minute).
        var written = 0
        while (written < length && strippedLeft > 0L) {
            if (posInBlock >= RAW_M2TS_PACKET_SIZE) {
                // Skip the 4-byte timestamp prefix at the start of the next packet; reuse skipBuf
                // and don't allocate fresh
                var skipped = 0
                while (skipped < PREFIX) {
                    val n = upstream.read(skipBuf, skipped, PREFIX - skipped)
                    if (n <= 0) return if (written > 0) written else C.RESULT_END_OF_INPUT
                    skipped += n
                }
                posInBlock = PREFIX
            }
            val remainInBlock = RAW_M2TS_PACKET_SIZE - posInBlock
            val toRead = minOf((length - written).toLong(), remainInBlock.toLong(), strippedLeft).toInt()
            val n = upstream.read(buffer, offset + written, toRead)
            if (n <= 0) return if (written > 0) written else C.RESULT_END_OF_INPUT
            posInBlock += n
            strippedLeft -= n
            written += n
            bytesTransferred(n)
            // If the upstream did not deliver the entire remaining packet in one shot (common with
            // network streaming reads where data is still arriving), return first instead of
            // blocking here — the caller will call read() again to continue; semantically still safe.
            if (n < toRead) break
        }
        sessionBytesRead += written
        sessionReadCalls++
        return if (written > 0) written else C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val elapsed = android.os.SystemClock.elapsedRealtime() - openAtMs
        Log.d(
            "twig",
            "m2ts: close after ${elapsed}ms, read $sessionBytesRead bytes in $sessionReadCalls calls",
        )
        runCatching { upstream.close() }
        if (opened) { opened = false; transferEnded() }
    }

    companion object {
        private const val PREFIX = RAW_M2TS_PACKET_SIZE - TS_PACKET_SIZE
    }
}

/**
 * [ExtractorsFactory] for the prefix-stripped stream fed to [TsExtractor]: explicitly enables
 * [DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS] — Blu-ray M2TS audio
 * tracks are commonly HDMV DTS (the DTS-HD/DTS-X stream_type); without this flag the default
 * factory skips them and never builds a track. Even with the flag set, the highest-quality main
 * audio track is still missing: in practice, an 8-channel DTS-X 7.1 remux uses stream_type
 * 0x86 for its main track — this byte is SCTE-35 insertion signaling in the ATSC/DVB standard
 * registry, but is reused in the Blu-ray/HDMV private namespace for DTS-HD Master Audio, so the
 * two collide on the same byte value. media3 only honors the standard-registry meaning, treats
 * this track's data as SCTE-35, and never builds a track. This app only plays local files and
 * has no use for real SCTE-35 signaling, so [BluRayTsPayloadReaderFactory] handles 0x86 as
 * DTS-HD, which better matches the actual scenario.
 */
@UnstableApi
fun newM2tsExtractorsFactory(): ExtractorsFactory = ExtractorsFactory {
    arrayOf<Extractor>(
        TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            /* extractorFlags= */ 0, // without FLAG_EMIT_RAW_SUBTITLE_DATA — see the comment on subtitleParserFactory below
            // The newer TextRenderer no longer supports the "raw subtitle samples + legacy
            // SubtitleDecoder" path (`IllegalStateException: Legacy decoding is disabled`);
            // a real SubtitleParser.Factory must convert samples into
            // application/x-media3-cues at extraction time. Passing UNSUPPORTED crashes the
            // renderer. [TwigSubtitleParserFactory] recognizes application/pgs (samples produced
            // by [PgsTsReader]) and forwards to [PgsSubtitleParser] — the official PgsParser
            // only emits one Cue per Display Set, so multi-segment subtitles on the same screen
            // get dropped.
            TwigSubtitleParserFactory(),
            TimestampAdjuster(0),
            BluRayTsPayloadReaderFactory(),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        ),
    )
}

/**
 * Detects the real packet size of an .m2ts file ([TS_PACKET_SIZE] or [RAW_M2TS_PACKET_SIZE]) —
 * some tools also store ordinary 188-byte TS streams with an .m2ts extension, so do not assume
 * a prefix needs to be stripped just from the extension. [shared] being null means a local file;
 * read directly by [file]'s path.
 */
fun detectM2tsPacketSize(file: XFile, shared: RandomSource?): Int {
    val head = ByteArray(RAW_M2TS_PACKET_SIZE * 8)
    val headLen = if (shared != null) {
        shared.readAt(0, head, 0, head.size)
    } else {
        RandomAccessFile(file.path, "r").use { it.read(head) }
    }.coerceAtLeast(0)

    // Check whether the 0x47 sync byte is continuously aligned. True BDAV M2TS also has a 4-byte
    // timestamp prefix before the first packet (the sync byte lands at offset=4, not 0) — this
    // was once stepped on, do not assume the stream starts with the sync byte at byte 0.
    fun aligned(size: Int, offset: Int): Boolean {
        if (headLen < offset + size * 4) return false
        var pos = offset
        var count = 0
        while (pos < headLen && count < 8) {
            if (head[pos] != 0x47.toByte()) return false
            pos += size
            count++
        }
        return count >= 4
    }
    val prefix = RAW_M2TS_PACKET_SIZE - TS_PACKET_SIZE
    return if (aligned(RAW_M2TS_PACKET_SIZE, prefix)) RAW_M2TS_PACKET_SIZE else TS_PACKET_SIZE
}

/**
 * Delegates to [DefaultTsPayloadReaderFactory] but additionally handles three Blu-ray-private
 * stream_types it does not recognize:
 * - 0x86: the HDMV namespace reuses this for DTS-HD Master Audio, colliding with the standard
 *   registry's SCTE-35 — see the comment on [newM2tsExtractorsFactory].
 * - 0x90: HDMV PGS (graphical bitmap subtitles); official media3 does not write a TS-layer
 *   demuxer for it at all — see the comment on [PgsTsReader].
 * - 0x83: Blu-ray TrueHD/Atmos audio tracks. The entire media3 extractor.ts package has **no
 *   ElementaryStreamReader for TrueHD/MLP at all** (not a missing case — it was never implemented);
 *   full support requires writing an MLP frame synchronizer, which is comparable in effort to
 *   writing a codec. As a fallback: the Blu-ray spec requires every TrueHD stream to embed a
 *   backwards-compatible AC-3 core (5.1, for devices that do not support TrueHD) — use [Ac3Reader]
 *   to scan for the sync word in this stream; it skips the interspersed MLP frames and only picks
 *   out the real AC-3 core frames for decoding. We give up Atmos immersive channels and TrueHD
 *   lossless to get audio (better than the entire track being dropped at the PMT stage with no
 *   sound at all).
 */
@UnstableApi
private class BluRayTsPayloadReaderFactory(
    private val delegate: TsPayloadReader.Factory =
        DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS),
) : TsPayloadReader.Factory {

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = delegate.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
        val reader = when (streamType) {
            STREAM_TYPE_HDMV_DTS_HD_MA ->
                // DtsReader.EXTSS_HEADER_SIZE_MAX is package-private inside the media3 package,
                // not accessible from outside; hard-code its source value (4096) here.
                PesReader(DtsReader(esInfo.language, esInfo.getRoleFlags(), 4096, MimeTypes.VIDEO_MP2T))
            STREAM_TYPE_HDMV_PGS -> PesReader(PgsTsReader(esInfo.language))
            STREAM_TYPE_HDMV_TRUEHD -> PesReader(Ac3Reader(esInfo.language, esInfo.getRoleFlags(), MimeTypes.VIDEO_MP2T))
            else -> delegate.createPayloadReader(streamType, esInfo)
        }
        // Some built-in media3 readers do not guard against malformed input; in practice we hit
        // an array-out-of-bounds in Ac3Util.parseAc3 SyncframeInfo (some remux's AC3 frame header
        // frmsizecod is out of the standard range) — this kind of exception happens on the
        // extraction thread, not a decode failure that can be rescued later by swapping renderers;
        // propagating it as-is drags the whole player down. Wrap one defensive layer so a bad
        // packet doesn't take out an entire playback.
        return reader?.let { SafeTsPayloadReader(it) }
    }

    companion object {
        private const val STREAM_TYPE_HDMV_DTS_HD_MA = 0x86
        private const val STREAM_TYPE_HDMV_PGS = 0x90
        private const val STREAM_TYPE_HDMV_TRUEHD = 0x83
    }
}

/**
 * Wraps a defensive try-catch layer: any runtime exception thrown by [delegate] (e.g. an array
 * out-of-bounds in the built-in Ac3Reader parsing a malformed AC3 frame header, or SectionReader
 * parsing malformed SCTE-35 out-of-bounds) is swallowed and the entire packet of data is dropped;
 * then [TsPayloadReader.seek] is called to let the wrapped reader reset its internal state and
 * continue decoding subsequent packets — better than letting the exception propagate and bring
 * down the whole player.
 */
@UnstableApi
private class SafeTsPayloadReader(private val delegate: TsPayloadReader) : TsPayloadReader {
    override fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) = delegate.init(timestampAdjuster, extractorOutput, idGenerator)

    override fun seek() = delegate.seek()

    override fun consume(data: ParsableByteArray, flags: Int) {
        try {
            delegate.consume(data, flags)
        } catch (e: Exception) {
            Log.w("twig", "player: TsPayloadReader consume failed, dropping packet: $e")
            runCatching { delegate.seek() }
        }
    }
}
