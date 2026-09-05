package com.twig.app.ui

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for [Mp3FrameTrackOutput] — MP3 audio in an AVI is a byte stream, not frames.
 *
 * The incident: about half of all AVIs opened partway in (a ~14s position on a file opened at 0)
 * and then froze, seeking included. `test2.avi` (XVID + LAME 128kbps/48kHz) turned out to store
 * one ~534-byte audio slice per video frame, and 534 is not a multiple of that stream's 384-byte
 * frame: only 9 of the first 4190 frames started on a chunk boundary. media3's `AviExtractor`
 * forwards each chunk as one sample, so every buffer handed to the decoder began mid-frame. On
 * device the platform decoder answered `err 0xe/14`, the ffmpeg fallback then anchored the audio
 * clock on a garbage timestamp, and media3 killed playback with `Player stuck playing with no
 * progress for 10000 ms`.
 *
 * What the assertions are really pinning down: **every** emitted sample starts with a frame header
 * and is exactly one frame long (that is what a decoder needs), and the timeline comes from the
 * frames rather than from the container's per-chunk timestamps — the container spreads the
 * duration evenly over the chunk *count*, which an 8000-byte audio preload chunk at the head of
 * the file makes a lie for every chunk after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AviRepairTest {

    /** MPEG-1 Layer III, 128kbps, 48kHz, stereo — 384 bytes per frame, 1152 samples. */
    private val frameHeader = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x94.toByte(), 0x00)
    private val frameSize = 384
    private val frameDurationUs = 24_000L

    private class Recorder : TrackOutput {
        val sizes = mutableListOf<Int>()
        val times = mutableListOf<Long>()
        val bytes = java.io.ByteArrayOutputStream()
        private val pending = java.io.ByteArrayOutputStream()

        override fun format(format: Format) = Unit

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val tmp = ByteArray(length)
            val read = input.read(tmp, 0, length)
            if (read > 0) pending.write(tmp, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val tmp = ByteArray(length)
            data.readBytes(tmp, 0, length)
            pending.write(tmp)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            sizes += size
            times += timeUs
            bytes.write(pending.toByteArray())
            pending.reset()
        }
    }

    /** [garbage] leading bytes that belong to no frame, then [frames] complete CBR frames. */
    private fun stream(frames: Int, garbage: Int = 0): ByteArray {
        val out = ByteArray(garbage + frames * frameSize) { 0xAA.toByte() }
        for (i in 0 until frames) {
            System.arraycopy(frameHeader, 0, out, garbage + i * frameSize, 4)
        }
        return out
    }

    /** Feeds [data] in [chunk]-byte slices, each carrying the container timestamp AVI would give it. */
    private fun feed(track: Mp3FrameTrackOutput, data: ByteArray, chunk: Int, firstIndex: Long = 0L) {
        var off = 0
        var index = firstIndex
        while (off < data.size) {
            val n = minOf(chunk, data.size - off)
            track.sampleData(ParsableByteArray(data.copyOfRange(off, off + n)), n, TrackOutput.SAMPLE_DATA_PART_MAIN)
            track.sampleMetadata(index * CHUNK_US, C.BUFFER_FLAG_KEY_FRAME, n, 0, null)
            off += n
            index++
        }
    }

    private fun mp3Track(rec: Recorder): Mp3FrameTrackOutput =
        Mp3FrameTrackOutput(rec).apply {
            format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).setSampleRate(48000).setChannelCount(2).build())
        }

    @Test
    fun `chunks that never line up still come out as whole frames`() {
        val rec = Recorder()
        // 200 frames behind 224 bytes of leading filler, sliced the way the real file does it.
        feed(mp3Track(rec), stream(frames = 200, garbage = 224), chunk = 534)

        assertEquals(200, rec.sizes.size)
        assertTrue("every sample is exactly one frame", rec.sizes.all { it == frameSize })
        val out = rec.bytes.toByteArray()
        for (i in 0 until 200) {
            assertEquals("frame $i starts with a sync word", 0xFF.toByte(), out[i * frameSize])
            assertEquals("frame $i header", 0xFB.toByte(), out[i * frameSize + 1])
        }
    }

    @Test
    fun `timestamps advance by one frame, not by one chunk`() {
        val rec = Recorder()
        feed(mp3Track(rec), stream(frames = 100, garbage = 224), chunk = 534)

        assertEquals(0L, rec.times.first())
        rec.times.forEachIndexed { i, t -> assertEquals("frame $i", i * frameDurationUs, t) }
    }

    /**
     * ★ A run of `0xFF` is how more than one muxer opens the audio stream, and at the point where
     * it meets real data it parses as a **valid** MP3 frame header. Accepting the first plausible
     * header emitted two junk samples of 288 and 128 bytes before the real audio and skewed the
     * timeline by 20ms, which the audio sink answered with `Unexpected audio track timestamp
     * discontinuity` roughly twice a second — each one a hiccup, so a 15-second clip took 22.5
     * seconds to play. A candidate is only believed once a second header turns up exactly one
     * frame later.
     */
    @Test
    fun `a run of 0xFF before the audio produces no junk frames`() {
        val rec = Recorder()
        val data = ByteArray(355) { 0xFF.toByte() } + stream(frames = 60)
        feed(mp3Track(rec), data, chunk = 576)

        assertTrue("every sample is one frame", rec.sizes.all { it == frameSize })
        assertEquals(0L, rec.times.first())
        rec.times.forEachIndexed { i, t -> assertEquals("frame $i", i * frameDurationUs, t) }
    }

    /**
     * The head of this file is an 8000-byte audio preload followed by ~534-byte chunks. media3
     * gives all of them the same 33ms slot, so by the second chunk the container's timeline is
     * already half a second ahead of the audio actually stored there; anchoring once and following
     * the frames is what keeps the track in sync (and matches what ffmpeg does for AVI audio).
     */
    @Test
    fun `a big preload chunk does not drag the timeline`() {
        val rec = Recorder()
        val track = mp3Track(rec)
        val data = stream(frames = 100, garbage = 224)
        // First chunk 8000 bytes at t=0, then 534-byte chunks at 33372us intervals.
        track.sampleData(ParsableByteArray(data.copyOfRange(0, 8000)), 8000, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, 8000, 0, null)
        var off = 8000
        var index = 1L
        while (off < data.size) {
            val n = minOf(534, data.size - off)
            track.sampleData(ParsableByteArray(data.copyOfRange(off, off + n)), n, TrackOutput.SAMPLE_DATA_PART_MAIN)
            track.sampleMetadata(index * CHUNK_US, C.BUFFER_FLAG_KEY_FRAME, n, 0, null)
            off += n
            index++
        }
        rec.times.forEachIndexed { i, t -> assertEquals("frame $i", i * frameDurationUs, t) }
    }

    @Test
    fun `a seek re-anchors on the timestamp of the chunk it lands in`() {
        val rec = Recorder()
        val track = mp3Track(rec)
        feed(track, stream(frames = 20, garbage = 224), chunk = 534)
        val before = rec.times.size
        track.reset()
        // Land on chunk 3000 of the container.
        feed(track, stream(frames = 20), chunk = 534, firstIndex = 3000)
        assertEquals(3000 * CHUNK_US, rec.times[before])
        assertEquals(3000 * CHUNK_US + frameDurationUs, rec.times[before + 1])
    }

    /** Anything that is not MP3 (AVI also carries PCM, AC3, AAC) must reach the renderer untouched. */
    @Test
    fun `a non-mp3 track is passed through unchanged`() {
        val rec = Recorder()
        val track = Mp3FrameTrackOutput(rec)
        track.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).build())
        val data = ByteArray(1000) { it.toByte() }
        track.sampleData(ParsableByteArray(data), 1000, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(1234L, C.BUFFER_FLAG_KEY_FRAME, 1000, 0, null)

        assertEquals(listOf(1000), rec.sizes)
        assertEquals(listOf(1234L), rec.times)
    }

    // ---- Video: frame order, packed bitstream, stuffing ----

    /**
     * A real VOL header lifted from `test3.avi` (`vop_time_increment_resolution` = 2997, so
     * `vop_time_increment` is 12 bits wide). Without it the parser cannot know where `vop_coded`
     * sits, and every VOP counts as a real frame.
     */
    private val volHeader = hex("000001200886842ed7064940")

    /** A real not-coded P-VOP, the 7-byte stuffing frame a packed bitstream leaves behind. */
    private val notCodedVop = hex("000001b659d49f")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** One coded VOP: start code, coding type, a plausible header, then filler. */
    private fun vop(type: Char, size: Int = 200): ByteArray {
        // 000001b6 | 2 bits type | modulo_time_base 0 | marker 1 | 12 bits increment | marker 1 | coded 1
        val header = ("IPBS".indexOf(type) shl 6) or 0x18 // type, then 0 1 1000...
        val out = ByteArray(size) { 0x11 }
        out[0] = 0; out[1] = 0; out[2] = 1; out[3] = 0xB6.toByte()
        out[4] = header.toByte()
        out[5] = 0xA8.toByte(); out[6] = 0xC0.toByte(); out[7] = 0xE1.toByte()
        return out
    }

    private fun videoTrack(rec: Recorder) =
        Mpeg4VopTrackOutput(rec).apply {
            format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_MP4V).build())
        }

    /** Feeds one AVI chunk (which may hold several VOPs packed together). */
    private fun chunk(track: Mpeg4VopTrackOutput, index: Long, vararg parts: ByteArray) {
        val data = parts.reduce { a, b -> a + b }
        track.sampleData(ParsableByteArray(data), data.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(index * FRAME_US, if (index == 0L) C.BUFFER_FLAG_KEY_FRAME else 0, data.size, 0, null)
    }

    /** Feeds [pattern] as one chunk per character, each with the timestamp of its storage slot. */
    private fun feedVops(track: Mpeg4VopTrackOutput, pattern: String) {
        pattern.forEachIndexed { i, type -> chunk(track, i.toLong(), vop(type)) }
        track.flushGroup()
    }

    /**
     * `I P B B P B B` is stored in coding order and displayed as `I B B P B B P`. The samples must
     * still reach the decoder in coding order — only the timestamps move.
     */
    @Test
    fun `b-frames get the display timestamps, in coding order`() {
        val rec = Recorder()
        feedVops(videoTrack(rec), "IPBBPBB")

        assertEquals(7, rec.times.size)
        val expected = listOf(0L, 3L, 1L, 2L, 6L, 4L, 5L).map { it * FRAME_US }
        assertEquals(expected, rec.times)
    }

    /** A stream with no B-frames must come out exactly as it went in. */
    @Test
    fun `a stream without b-frames is untouched`() {
        val rec = Recorder()
        feedVops(videoTrack(rec), "IPPPIPPP")

        assertEquals((0 until 8).map { it * FRAME_US }, rec.times)
    }

    /**
     * A packed bitstream puts the P-VOP and the B-VOP that follows it in **one** chunk, then emits
     * a 7-byte not-coded VOP as its own chunk to keep the chunk count right. Handed the packed
     * chunk whole, a decoder decodes the first VOP and throws the rest away — a third of the frames
     * simply never appear. Split back out, all three frames arrive, and the stuffing chunk's
     * timestamp is exactly the slot the packed B needs.
     */
    @Test
    fun `a packed chunk is split and the stuffing frame gives up its slot`() {
        val rec = Recorder()
        val track = videoTrack(rec)
        chunk(track, 0, volHeader, vop('I'))
        chunk(track, 1, vop('P'), vop('B'))
        chunk(track, 2, vop('B'))
        chunk(track, 3, notCodedVop)
        chunk(track, 4, vop('P'), vop('B'))
        chunk(track, 5, vop('B'))
        chunk(track, 6, notCodedVop)
        track.flushGroup()

        // Coding order I P B B P B B, displayed as I B B P B B P.
        assertEquals(7, rec.times.size)
        assertEquals(listOf(0L, 3L, 1L, 2L, 6L, 4L, 5L).map { it * FRAME_US }, rec.times)
    }

    /**
     * ★ The first VOP of a chunk has to keep whatever sits in front of it. A keyframe chunk carries
     * the VOS/VO/VOL headers there, and `c2.android.mpeg4.decoder` looks for them in the first
     * buffer and nowhere else — slice the VOP out on its own start code and the codec answers
     * `PVInitVideoDecoder failed. Unsupported content?` before a single frame is decoded.
     */
    @Test
    fun `the first vop of a chunk keeps the headers in front of it`() {
        val rec = Recorder()
        val track = videoTrack(rec)
        chunk(track, 0, volHeader, vop('I'))
        chunk(track, 1, vop('P'))
        track.flushGroup()

        assertEquals(volHeader.size + 200, rec.sizes.first())
        val out = rec.bytes.toByteArray()
        assertEquals("the sample starts with the VOL start code", 0x20.toByte(), out[3])
    }

    /**
     * `7f` chunks carry no VOP at all ("repeat the last frame"). Fed to the decoder they produce
     * `failed to decode vop header` and take the whole file down, so they must be swallowed — and
     * the frames after them must keep their own slots, or the video would run ahead of the sound.
     */
    @Test
    fun `chunks with no vop are swallowed and cost no frames`() {
        val rec = Recorder()
        val track = videoTrack(rec)
        chunk(track, 0, volHeader, vop('I'))
        chunk(track, 1, hex("7f"))
        chunk(track, 2, hex("7f"))
        chunk(track, 3, vop('P'))
        chunk(track, 4, vop('B'))
        chunk(track, 5, vop('B'))
        track.flushGroup()

        assertEquals(4, rec.times.size)
        assertEquals(listOf(0L, 5L, 3L, 4L).map { it * FRAME_US }, rec.times)
        assertTrue("no stuffing byte reached the decoder", rec.sizes.none { it <= 8 })
    }

    /** A chunk with no VOP header at all counts as stuffing, so it never reaches the decoder. */
    @Test
    fun `a chunk without a vop header is not forwarded`() {
        val rec = Recorder()
        val track = videoTrack(rec)
        val data = ByteArray(64) { 0x77 }
        track.sampleData(ParsableByteArray(data), data.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(5 * FRAME_US, 0, data.size, 0, null)
        track.flushGroup()

        assertEquals(emptyList<Long>(), rec.times)
    }

    /** Anything that is not MPEG-4 (AVI also carries H.264, MJPEG) must reach the renderer as-is. */
    @Test
    fun `a non-mpeg4 video track is passed through unchanged`() {
        val rec = Recorder()
        val track = Mpeg4VopTrackOutput(rec)
        track.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        val data = vop('B')
        track.sampleData(ParsableByteArray(data), data.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(99L, 0, data.size, 0, null)

        assertEquals(listOf(data.size), rec.sizes)
        assertEquals(listOf(99L), rec.times)
    }

    private companion object {
        /** What AVI gives one chunk: the file's duration spread evenly over the chunk count. */
        const val CHUNK_US = 33_372L

        /** One video frame at 29.97fps. */
        const val FRAME_US = 33_367L
    }
}
