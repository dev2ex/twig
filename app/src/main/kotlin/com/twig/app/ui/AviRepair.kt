package com.twig.app.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.text.SubtitleParser
import kotlin.math.max

/**
 * Wraps an [ExtractorsFactory] so the AVI extractor gets its two known defects repaired
 * ([AviRepairExtractor]); every other container is passed through untouched.
 */
@UnstableApi
class AviRepairExtractorsFactory(private val base: ExtractorsFactory) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = wrap(base.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(base.createExtractors(uri, responseHeaders))

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        base.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { i ->
            val e = extractors[i]
            if (e is AviExtractor) AviRepairExtractor(e) else e
        }
}

/**
 * media3's `AviExtractor` gets two things wrong about the files this container era actually
 * produced, and this wrapper repairs both on the way out. Neither is reachable through the public
 * API — they are properties of the samples, not of the extractor's configuration — so the repair
 * sits between the extractor and the renderers, as a pair of [TrackOutput] wrappers.
 *
 * **1. MP3 audio in an AVI is a byte stream, not frames.** `strh.sampleSize` is 1 and `dwLength`
 * counts **bytes**, so the muxer cuts the `01wb` chunks wherever it likes. A LAME/VirtualDub-era
 * file interleaves one ~534-byte slice per video frame, and 534 is not a multiple of the 384-byte
 * frame of a 128kbps/48kHz stream — in a real file (`test2.avi`) only 9 of the first 4190 frames
 * start on a chunk boundary. `AviExtractor` forwards each chunk as **one sample**, and an MP3
 * decoder given a buffer that does not begin with a frame header decodes nothing. Traced on
 * device: `err 0xe/14` from the platform decoder, [MediaPlayerActivity]'s fallback rebuilds with
 * the ffmpeg renderer, the audio clock anchors on a garbage timestamp (~14s into a file opened at
 * 0) and never advances, and media3 gives up 10s later with `Player stuck playing with no
 * progress`. See [Mp3FrameTrackOutput].
 *
 * **2. AVI carries no presentation timestamps, so B-frames arrive out of order.** MPEG-4 ASP
 * stores frames in coding order (`I P B B P B B …`); every other container records the display
 * time separately, AVI does not, and `AviExtractor` gives each chunk the timestamp of its
 * *storage* slot. See [Mpeg4VopTrackOutput].
 */
@UnstableApi
class AviRepairExtractor(private val inner: Extractor) : Extractor {

    private val output = RepairOutput()

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        this.output.delegate = output
        inner.init(this.output)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = inner.read(input, seekPosition)
        // The video reorder holds a group of frames until the next non-B frame reveals how long
        // that group was; at the end of the file no such frame ever comes, so the tail has to be
        // released here or the last few frames are never played.
        if (result == Extractor.RESULT_END_OF_INPUT) output.flushVideo()
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        // Drop the half-assembled MP3 frame, its timestamp anchor and the pending video group
        // before the extractor jumps: the bytes that arrive next belong elsewhere in the stream.
        output.reset()
        inner.seek(position, timeUs)
    }

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation

    private class RepairOutput : ExtractorOutput {
        lateinit var delegate: ExtractorOutput
        private var audio: Mp3FrameTrackOutput? = null
        private var video: Mpeg4VopTrackOutput? = null

        override fun track(id: Int, type: Int): TrackOutput {
            val track = delegate.track(id, type)
            return when (type) {
                C.TRACK_TYPE_AUDIO -> Mp3FrameTrackOutput(track).also { audio = it }
                C.TRACK_TYPE_VIDEO -> Mpeg4VopTrackOutput(track).also { video = it }
                else -> track
            }
        }

        override fun endTracks() = delegate.endTracks()

        override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)

        fun flushVideo() {
            video?.flushGroup()
        }

        fun reset() {
            audio?.reset()
            video?.reset()
        }
    }
}

/**
 * Buffers the AVI audio chunks and forwards one sample per complete MP3 frame. A track that is not
 * `audio/mpeg` passes straight through, so this is safe to install on any AVI audio track.
 */
@UnstableApi
internal class Mp3FrameTrackOutput(private val out: TrackOutput) : TrackOutput {

    private var reframe = false
    private val header = MpegAudioUtil.Header()
    private val view = ParsableByteArray()
    private var buf = ByteArray(INITIAL_BUFFER_SIZE)
    private var len = 0

    /**
     * Timestamp of the first frame emitted since the last seek, plus how many samples have been
     * emitted since. The container's own per-chunk timing is derived from a chunk *count* and is
     * only as good as the chunks being uniform, so it is used once — to place the first frame —
     * and the frames carry the timeline from there. That is also what ffmpeg does for AVI audio
     * with `sampleSize == 1`, where the timestamp comes from the byte offset, not the chunk index.
     */
    private var anchorUs = C.TIME_UNSET
    private var samplesSinceAnchor = 0L
    private var sampleRate = 0

    /**
     * Whether the next frame is known to start exactly where the last one ended. A run of `0xFF`
     * padding — which is how more than one muxer opens the audio stream — parses as a perfectly
     * valid frame header, so an unlocked search has to see a **second** header exactly one frame
     * later before it believes the first. Once locked, frames follow each other and no
     * confirmation is needed; a header that does not appear where it should unlocks the search
     * again.
     */
    private var locked = false

    override fun format(format: Format) {
        reframe = MimeTypes.AUDIO_MPEG == format.sampleMimeType
        out.format(format)
    }

    override fun durationUs(durationUs: Long) = out.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!reframe || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        ensureCapacity(len + length)
        val read = input.read(buf, len, length)
        if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        len += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!reframe || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            out.sampleData(data, length, sampleDataPart)
            return
        }
        ensureCapacity(len + length)
        data.readBytes(buf, len, length)
        len += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!reframe) {
            out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        if (anchorUs == C.TIME_UNSET) anchorUs = timeUs
        emitFrames()
    }

    /** Forgets everything buffered; called when the extractor seeks. */
    fun reset() {
        len = 0
        anchorUs = C.TIME_UNSET
        samplesSinceAnchor = 0L
        locked = false
    }

    private fun nextFrameTimeUs(): Long =
        if (sampleRate <= 0) {
            anchorUs
        } else {
            anchorUs + Util.scaleLargeTimestamp(samplesSinceAnchor, C.MICROS_PER_SECOND, sampleRate.toLong())
        }

    private fun emitFrames() {
        var pos = 0
        while (true) {
            val sync = findSync(pos)
            if (sync == NEED_MORE_DATA) {
                // A candidate that needs the next frame to confirm it: keep everything and wait.
                break
            }
            if (sync < 0) {
                // Nothing usable left. Keep the last three bytes: a header is four bytes wide and
                // can straddle a chunk boundary.
                pos = max(pos, len - 3)
                break
            }
            if (len - sync < header.frameSize) {
                // The header is there but the frame has not arrived in full yet.
                pos = sync
                break
            }
            if (sampleRate != header.sampleRate) {
                // The first frame, or a stream that switches rate part-way: restart the count here
                // rather than scale the old sample total against the new rate.
                anchorUs = nextFrameTimeUs()
                samplesSinceAnchor = 0L
                sampleRate = header.sampleRate
            }
            view.reset(buf, len)
            view.setPosition(sync)
            out.sampleData(view, header.frameSize)
            out.sampleMetadata(nextFrameTimeUs(), C.BUFFER_FLAG_KEY_FRAME, header.frameSize, 0, null)
            samplesSinceAnchor += header.samplesPerFrame
            pos = sync + header.frameSize
            locked = true
        }
        discard(pos)
    }

    /**
     * Index of the next MP3 frame header at or after [from], -1 when there is none, or
     * [NEED_MORE_DATA] when a candidate looks right but cannot be confirmed yet. Fills in [header].
     */
    private fun findSync(from: Int): Int {
        var i = max(0, from)
        while (i + 4 <= len) {
            if (buf[i] == SYNC_BYTE && header.setForHeaderData(headerAt(i))) {
                if (locked && i == from) return i
                when (confirm(i, header.frameSize)) {
                    CONFIRM_OK -> return i
                    CONFIRM_PENDING -> return NEED_MORE_DATA
                    else -> Unit // a lookalike inside padding or frame data; keep scanning
                }
            }
            i++
        }
        return -1
    }

    /** Checks for a second header exactly one frame after [at], the classic MP3 sync confirmation. */
    private fun confirm(at: Int, frameSize: Int): Int {
        val next = at + frameSize
        if (next + 4 > len) return CONFIRM_PENDING
        if (buf[next] != SYNC_BYTE) return CONFIRM_NO
        val expected = headerAt(at) and HEADER_STABLE_MASK
        return if ((headerAt(next) and HEADER_STABLE_MASK) == expected) CONFIRM_OK else CONFIRM_NO
    }

    private fun headerAt(i: Int): Int =
        ((buf[i].toInt() and 0xFF) shl 24) or ((buf[i + 1].toInt() and 0xFF) shl 16) or
            ((buf[i + 2].toInt() and 0xFF) shl 8) or (buf[i + 3].toInt() and 0xFF)

    /** Drops the first [count] bytes and slides the rest to the front. */
    private fun discard(count: Int) {
        if (count <= 0) return
        System.arraycopy(buf, count, buf, 0, len - count)
        len -= count
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buf.size) return
        buf = buf.copyOf(max(needed, buf.size * 2))
    }

    private companion object {
        const val INITIAL_BUFFER_SIZE = 8 * 1024
        const val SYNC_BYTE = 0xFF.toByte()

        /** [findSync] saw a plausible header but needs the following frame to confirm it. */
        const val NEED_MORE_DATA = -2

        const val CONFIRM_OK = 0
        const val CONFIRM_NO = 1
        const val CONFIRM_PENDING = 2

        /**
         * The header bits that cannot change between two frames of one stream: sync, version,
         * layer and sampling rate. Bitrate and padding may vary (VBR), so they are left out.
         */
        const val HEADER_STABLE_MASK = -0x200000 or 0x1E0000 or 0xC00 // FFE00000 | 001E0000 | 00000C00
    }
}

/**
 * Repairs MPEG-4 video read out of an AVI. Any other codec passes straight through, and so does a
 * plain one-frame-per-chunk stream with no B-frames — every group is then one frame long and every
 * timestamp comes out exactly as it went in.
 *
 * Three things go wrong, all of them invisible from outside the samples:
 *
 * **Frame order.** MPEG-4 ASP stores frames in **coding** order and displays them in another:
 * `I0 P3 B1 B2 P6 B4 B5` (subscript = display slot). Containers that carry a presentation timestamp
 * per sample record the difference; AVI has none, so `AviExtractor` can only number the chunks as
 * it meets them, which labels `P3` as if it were frame 1. The picture jumps three frames forward
 * and two back, over and over — reported as "the image keeps shaking".
 *
 * **Packed bitstream.** DivX/XVID-era encoders pack a P-VOP and the B-VOP that follows it into
 * **one** AVI chunk and then emit a 7-byte not-coded VOP as a separate chunk to keep the chunk
 * count equal to the frame count. A decoder handed the packed chunk decodes the first VOP and
 * discards the rest, so a third of the frames never appear at all — the file plays at two thirds
 * speed with a visible stutter. The VOPs have to be split back out into one sample each.
 *
 * **Stuffing chunks.** Beside the 7-byte not-coded VOP, files also carry 1-byte `7f` chunks that
 * hold no VOP at all ("repeat the last frame"). Fed to `c2.android.mpeg4.decoder` they produce
 * `failed to decode vop header`, the codec goes to the error state, and the whole file is reported
 * as undecodable — which is why one of these files would neither play nor produce a thumbnail.
 * Neither kind of stuffing may reach the decoder.
 *
 * The repair works on VOPs rather than chunks. A group is one anchor (I/P/S) plus the B-VOPs that
 * follow it in coding order, and it owns the timestamps ("slots") of every chunk it spans —
 * including the stuffing chunks, which is exactly where a packed chunk's second VOP gets its slot
 * from. The display times are then that group's slots **rotated by one**: each B takes the slot
 * before it, the anchor takes the last one. Emission stays in coding order, which is what the
 * decoder needs.
 *
 * ★ **`c2.android.mpeg4.decoder` advertises `profile/levels: [1/256 (Simple/6)]` and decodes
 * B-VOPs perfectly well anyway.** Do not read that as "this device cannot play Advanced Simple
 * Profile" and start dropping frames — the advertised profile list is not a statement about what
 * the decoder will accept, and every file here plays at full frame rate once the samples reach it
 * in one piece. There would be nothing to fall back to either: media3's ffmpeg extension decodes
 * audio only (`ExperimentalFfmpegVideoRenderer` is a stub whose `createDecoder` returns null, and
 * `libffmpegJNI.so` exports no video entry points), whatever the `Loaded FfmpegVideoRenderer` line
 * in logcat suggests — bundling a software MPEG-4 decoder would be the only other way, and none
 * of these files needs it.
 */
@UnstableApi
internal class Mpeg4VopTrackOutput(private val out: TrackOutput) : TrackOutput {

    /** One coded VOP, as a slice of [buf]; [offset] moves when the buffer is compacted. */
    private class Vop(var offset: Int, val size: Int, val flags: Int, val isB: Boolean)

    private var active = false
    private val view = ParsableByteArray()
    private var buf = ByteArray(0)
    private var len = 0

    /** Where in [buf] the chunk currently being written started. */
    private var chunkStart = 0

    private val group = ArrayList<Vop>()
    private val slots = ArrayList<Long>()

    /**
     * `vop_time_increment_resolution` from the VOL header, which is what fixes the width of the
     * `vop_time_increment` field and therefore where `vop_coded` sits. 0 until a VOL is seen, and
     * until then every VOP is taken as coded — the old behaviour, rather than a guess.
     */
    private var timeResolution = 0

    override fun format(format: Format) {
        active = MimeTypes.VIDEO_MP4V == format.sampleMimeType
        out.format(format)
    }

    override fun durationUs(durationUs: Long) = out.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!active || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        ensureCapacity(len + length)
        val read = input.read(buf, len, length)
        if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        len += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!active || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            out.sampleData(data, length, sampleDataPart)
            return
        }
        ensureCapacity(len + length)
        data.readBytes(buf, len, length)
        len += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!active) {
            out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        if (timeResolution == 0) timeResolution = Mpeg4Headers.volTimeResolution(buf, chunkStart, len)
        val vops = codedVops(flags)
        // An anchor closes the group before it: only now is it known how many B-VOPs trailed the
        // previous anchor, and how many chunks — stuffing included — that group spanned.
        if (vops.isNotEmpty() && !vops[0].isB) {
            val shift = flushGroup()
            for (v in vops) v.offset -= shift
        }
        slots.add(timeUs)
        if (vops.isEmpty()) {
            // Stuffing: it contributes a slot and nothing else, and must not reach the decoder.
            len = chunkStart
            return
        }
        group.addAll(vops)
        chunkStart = len
    }

    /**
     * Emits the buffered group in coding order, with its slots rotated into display order, then
     * compacts [buf]. Returns how far the bytes of the chunk still being written moved, so the
     * caller can correct offsets it computed before the call.
     */
    fun flushGroup(): Int {
        val anchorFirst = group.isNotEmpty() && !group[0].isB
        for (i in group.indices) {
            val v = group[i]
            val timeUs = when {
                // The anchor is displayed after every B that follows it in coding order.
                anchorFirst && i == 0 -> slotAt(group.size - 1)
                anchorFirst -> slotAt(i - 1)
                else -> slotAt(i)
            }
            view.reset(buf, len)
            view.setPosition(v.offset)
            out.sampleData(view, v.size)
            out.sampleMetadata(timeUs, v.flags, v.size, 0, null)
        }
        group.clear()
        slots.clear()
        // Whatever sits after chunkStart belongs to the chunk still being written; keep it.
        val shift = chunkStart
        val keep = len - shift
        System.arraycopy(buf, shift, buf, 0, keep)
        len = keep
        chunkStart = 0
        return shift
    }

    /** Forgets the pending group; called when the extractor seeks. */
    fun reset() {
        group.clear()
        slots.clear()
        len = 0
        chunkStart = 0
    }

    private fun slotAt(index: Int): Long =
        slots.getOrElse(index) { slots.lastOrNull() ?: 0L }

    /**
     * Splits the chunk in `buf[chunkStart, len)` into its coded VOPs. Not-coded VOPs (the 7-byte
     * stuffing frames a packed bitstream leaves behind) and chunks with no VOP start code at all
     * are left out; only the chunk's first VOP inherits the chunk's [chunkFlags], since a keyframe
     * chunk is a keyframe because of its first VOP.
     */
    private fun codedVops(chunkFlags: Int): List<Vop> {
        val vops = ArrayList<Vop>(2)
        var i = chunkStart
        var first = true
        while (i <= len - 5) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() &&
                buf[i + 2] == 1.toByte() && buf[i + 3] == VOP_START_CODE
            ) {
                val next = nextVopStart(i + 4)
                val end = if (next >= 0) next else len
                val type = (buf[i + 4].toInt() shr 6) and 3
                if (timeResolution == 0 || Mpeg4Headers.vopCoded(buf, i + 4, len, timeResolution)) {
                    // ★ The first VOP of a chunk starts at the chunk, not at its own start code:
                    // a keyframe chunk carries the VOS/VO/VOL headers in front of it, and that is
                    // the only place `c2.android.mpeg4.decoder` ever looks for them — cut them off
                    // and it answers `PVInitVideoDecoder failed. Unsupported content?`.
                    val start = if (first) chunkStart else i
                    vops.add(Vop(start, end - start, if (first) chunkFlags else 0, type == VOP_TYPE_B))
                    first = false
                }
                i = end
            } else {
                i++
            }
        }
        return vops
    }

    private fun nextVopStart(from: Int): Int {
        var i = from
        while (i <= len - 4) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() &&
                buf[i + 2] == 1.toByte() && buf[i + 3] == VOP_START_CODE
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buf.size) return
        buf = buf.copyOf(max(needed, max(buf.size * 2, INITIAL_VIDEO_BUFFER_SIZE)))
    }

    private companion object {
        const val INITIAL_VIDEO_BUFFER_SIZE = 128 * 1024
        const val VOP_START_CODE = 0xB6.toByte()
        const val VOP_TYPE_B = 2
    }
}

/**
 * Just enough MPEG-4 part 2 header parsing to tell a real frame from a stuffing frame. Both fields
 * are bit-packed, and `vop_coded` cannot be reached without first knowing
 * `vop_time_increment_resolution` from the VOL header — which is why the resolution is read once
 * and kept.
 */
internal object Mpeg4Headers {

    private const val VOL_START_CODE_MIN = 0x20
    private const val VOL_START_CODE_MAX = 0x2F

    /** Reads `vop_time_increment_resolution` from a VOL header inside `data[from, to)`, else 0. */
    fun volTimeResolution(data: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i <= to - 5) {
            val code = data[i + 3].toInt() and 0xFF
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() &&
                code >= VOL_START_CODE_MIN && code <= VOL_START_CODE_MAX
            ) {
                return runCatching { readVol(BitReader(data, (i + 4) * 8, to * 8)) }.getOrDefault(0)
            }
            i++
        }
        return 0
    }

    private fun readVol(r: BitReader): Int {
        r.skip(1) // random_accessible_vol
        r.skip(8) // video_object_type_indication
        if (r.bit() == 1) r.skip(7) // video_object_layer_verid + priority
        if (r.read(4) == 0xF) r.skip(16) // extended aspect ratio: par_width + par_height
        if (r.bit() == 1) { // vol_control_parameters
            r.skip(3) // chroma_format + low_delay
            if (r.bit() == 1) r.skip(79) // vbv_parameters
        }
        if (r.read(2) != 0) return 0 // only rectangular shape is worth the parsing
        r.skip(1) // marker_bit
        return r.read(16)
    }

    /** `vop_coded` of the VOP whose header starts at [from] (the byte after the start code). */
    fun vopCoded(data: ByteArray, from: Int, to: Int, timeResolution: Int): Boolean =
        runCatching {
            val r = BitReader(data, from * 8, to * 8)
            r.skip(2) // vop_coding_type
            while (r.bit() == 1) Unit // modulo_time_base, terminated by a 0
            r.skip(1) // marker_bit
            r.skip(timeIncrementBits(timeResolution))
            r.skip(1) // marker_bit
            r.bit() == 1
        }.getOrDefault(true) // a header we cannot read is treated as a real frame

    /** ceil(log2(resolution)), at least one bit — the width of `vop_time_increment`. */
    private fun timeIncrementBits(resolution: Int): Int {
        var bits = 1
        while ((1 shl bits) < resolution) bits++
        return bits
    }

    private class BitReader(private val data: ByteArray, private var pos: Int, private val end: Int) {
        fun bit(): Int {
            if (pos >= end) throw IndexOutOfBoundsException()
            val b = (data[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return b
        }

        fun read(count: Int): Int {
            var v = 0
            repeat(count) { v = (v shl 1) or bit() }
            return v
        }

        fun skip(count: Int) {
            repeat(count) { bit() }
        }
    }
}
