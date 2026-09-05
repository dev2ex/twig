package com.twig.app.ui

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Blu-ray PGS (Presentation Graphic Stream, graphical bitmap subtitles) sample
 * slicer at the TS layer — media3 itself never wrote this (the switch statement in
 * `DefaultTsPayloadReaderFactory` has no `stream_type 0x90` branch for HDMV PGS),
 * it only ships DVB subtitles (`DvbSubtitleReader`, a different European broadcast
 * standard). PGS **bitmap decoding** is in media3 (`androidx.media3.extractor.text.pgs.PgsParser`,
 * which MKV uses); what was missing was just the TS-side "how to cut a PES packet
 * into samples it accepts", which is what this adds.
 *
 * Key premise (verified with `ffprobe`/`mediainfo` plus the official BD-ROM PG docs):
 * **a single M2TS PES packet holds exactly one complete PGS segment** (1-byte
 * segment_type + 2-byte length + payload), timestamps come from the PES packet's
 * own PTS — unlike offline .sup files where every segment is preceded by a redundant
 * 'PG' magic + PTS + DTS (a non-multiplexed format's own invention to keep timing
 * metadata outside a container). [PgsParser.parse] expects **an entire Display Set**
 * (PCS→WDS→PDS (zero or more)→ODS (zero or more)→END, segments back-to-back as
 * `[type][len][payload]`, without magic/PTS/DTS) per call — it resets its internal
 * state at the top of every parse() call, so feeding a single segment can never build
 * a complete bitmap. Here we accumulate per PES packet, and only once we see a
 * segment whose type is END (0x80) do we emit what we've gathered as one sample via
 * `TrackOutput.sampleMetadata` (timestamp taken from the first PES packet in the
 * group, i.e. the PCS segment's time).
 */
@UnstableApi
class PgsTsReader(private val language: String?) : ElementaryStreamReader {

    private lateinit var output: TrackOutput
    private var started = false
    private var pesTimeUs = C.TIME_UNSET
    private var sampleTimeUs = C.TIME_UNSET
    private var sampleBytesWritten = 0

    // Segment-boundary state that survives across consume() calls (see the comment on
    // [consume]: one consume() only delivers one TS packet's payload, not a whole PES
    // packet, so we have to accumulate the boundary ourselves).
    private val header = ByteArray(3)
    private var headerBytes = 0
    private var segmentBytesLeft = 0
    private var sawEnd = false

    override fun seek() {
        started = false
        pesTimeUs = C.TIME_UNSET
        sampleTimeUs = C.TIME_UNSET
        sampleBytesWritten = 0
        headerBytes = 0
        segmentBytesLeft = 0
        sawEnd = false
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        output.format(
            Format.Builder()
                .setId(idGenerator.formatId)
                .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                .setSampleMimeType(MimeTypes.APPLICATION_PGS)
                .setLanguage(language)
                .build(),
        )
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        // Just remember "the current PES packet's timestamp"; where a sample starts and
        // which PTS to use is decided by [consume] against segment boundaries (a group
        // can span multiple PES packets, a single PES packet can also hold multiple groups).
        this.pesTimeUs = pesTimeUs
        started = true
    }

    override fun consume(data: ParsableByteArray) {
        if (!started) return
        // ★ One consume() call delivers the payload of **one TS packet** (≤184 bytes),
        //   not a whole PES packet — `PesReader` feeds it through as it arrives. So we
        //   cannot just do what the early implementation did and "every consume, treat
        //   the first byte as segment_type": a large bitmap (ODS) spans dozens or hundreds
        //   of TS packets, and for those chunks the first byte is bitmap data — the chance
        //   that value lands on 0x80 is about 1/256, so a 20KB ODS hitting a fake END
        //   is already ~30% — the sample gets cut off in the middle and emitted (subtitle
        //   fails to decode / glitches), and the leftover half becomes a sample of its own
        //   (decodes empty, wiping the whole subtitle for one frame). Moving-subtitle
        //   Display Sets are emitted densely, so both of these get amplified into visible
        //   "flicker + appearing/disappearing".
        //   Here we instead walk segment boundaries properly via
        //   [type][len][payload] (state preserved across consume/PES), and only treat
        //   having parsed a **segment header** with type==END as the end of the group —
        //   and we **cut and emit the sample right there**, without waiting for
        //   packetFinished. That way "one sample is exactly one Display Set" is a hard
        //   guarantee: regardless of whether the mux is one PES per segment or
        //   whole groups (even several back-to-back) packed into one PES, [PgsSubtitleParser]
        //   can decode each as "one parse() per group".
        val buf = data.data
        while (data.bytesLeft() > 0) {
            // A group's timestamp = the PTS of the PES packet containing its first byte
            // (a group crossing PES keeps the first; a second group inside the same PES
            // can only inherit that same PTS — there's nothing else to give it).
            if (sampleBytesWritten == 0) sampleTimeUs = pesTimeUs
            val start = data.position
            val end = start + data.bytesLeft()
            var i = start
            var boundary = -1
            while (i < end) {
                if (segmentBytesLeft > 0) {
                    val skip = minOf(segmentBytesLeft, end - i)
                    i += skip
                    segmentBytesLeft -= skip
                } else {
                    header[headerBytes++] = buf[i++]
                    if (headerBytes == 3) {
                        headerBytes = 0
                        val type = header[0].toInt() and 0xFF
                        segmentBytesLeft = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
                        if (type == SEGMENT_TYPE_END) sawEnd = true
                    }
                }
                // END segment fully collected (header complete + payload complete) = this group ends here.
                if (sawEnd && headerBytes == 0 && segmentBytesLeft == 0) { boundary = i; break }
            }
            // What actually goes into the sample is the complete bytes (including the
            // segment header itself — the parser starts parsing from that byte).
            val n = (if (boundary >= 0) boundary else end) - start
            if (n > 0) {
                output.sampleData(data, n)
                sampleBytesWritten += n
            }
            if (boundary < 0) return // this group isn't done yet, wait for the next TS/PES packets
            output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null)
            sampleBytesWritten = 0
            sawEnd = false
        }
    }

    override fun packetFinished(isEndOfInput: Boolean) {
        // Under normal conditions, [consume] already cuts the sample on the END segment;
        // this only catches a trailing packet that has no END on it.
        if (isEndOfInput && sampleBytesWritten > 0) {
            output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null)
            sampleBytesWritten = 0
            headerBytes = 0
            segmentBytesLeft = 0
            sawEnd = false
        }
    }

    companion object {
        private const val SEGMENT_TYPE_END = 0x80
    }
}
