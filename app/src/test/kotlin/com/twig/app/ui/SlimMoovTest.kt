package com.twig.app.ui

import com.twig.core.RandomSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Regression for [Thumbs.planSlimMoov] — downloading only the part of a large moov that a
 * frame grab actually needs.
 *
 * The incident: a 28 GB 4K/60fps HEVC release with **8 audio tracks** never produced a
 * thumbnail over SMB (and its properties card took just as long). It was not a decode
 * failure. A moov's sample tables are proportional to sample count *per track*, and this
 * file's measured out at 42.76 MB, of which the video trak is 9.70 MB — the other 33 MB is
 * audio sample tables plus a 1.16 MB udta cover, all downloaded, parsed, and then ignored
 * for a single video frame. On top of that the `VIDEO_HEAD_CAP` head read is, for any
 * faststart file, a byte-for-byte duplicate of moov's first 8 MB. Roughly 59 MB per
 * thumbnail, comfortably past [Thumbs.VIDEO_TIMEOUT_MS].
 *
 * The fix cannot hand MMR a *shorter* moov: moov's own size is what tells MMR where mdat
 * begins, so the file layout has to stay byte-identical. Instead each unwanted child box is
 * served as an 8-byte `free` header **of the same size** — a standard ISO BMFF skip box, so
 * MMR jumps over the body and we never fetch it. Verified on the desktop against the real
 * file: a frame decoded from the slimmed layout is byte-identical (same md5) to one decoded
 * from the full moov.
 *
 * The two things worth guarding here are exactly the two ways this can silently break:
 * a stub whose size doesn't match the box it replaces (MMR would then walk into the middle
 * of a box and see garbage instead of mdat), and the compact copy we parse ourselves
 * disagreeing with the full moov about where the keyframe is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlimMoovTest {

    // ---- Minimal ISO BMFF builder ----

    private fun u32(v: Long) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun u64(v: Long) = ByteArray(8) { i -> (v ushr (56 - i * 8)).toByte() }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return u32((body.size + 8).toLong()) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private val timescale = 1000L
    private val sampleCount = 200L
    private val sampleDelta = 100L // 10 fps at timescale 1000 → 20 s.
    private val samplesPerChunk = 5L
    private val sampleSize = 10_000L
    private val chunk0 = 1_000_000L
    private val chunkStride = 50_000L

    /** stbl for a video track: keyframe every 10 samples, fixed sample size, 64-bit chunk offsets (the real file uses co64 — it is 28 GB). */
    private fun videoStbl(): ByteArray {
        val stts = box("stts", u32(0), u32(1), u32(sampleCount) + u32(sampleDelta))
        val syncs = ByteArrayOutputStream()
        var s = 1L
        var syncN = 0L
        while (s <= sampleCount) { syncs.write(u32(s)); syncN++; s += 10 }
        val stss = box("stss", u32(0), u32(syncN), syncs.toByteArray())
        val stsc = box("stsc", u32(0), u32(1), u32(1) + u32(samplesPerChunk) + u32(1))
        val stsz = box("stsz", u32(0), u32(sampleSize), u32(sampleCount))
        val chunks = sampleCount / samplesPerChunk
        val offsets = ByteArrayOutputStream()
        for (c in 0 until chunks) offsets.write(u64(chunk0 + c * chunkStride))
        val co64 = box("co64", u32(0), u32(chunks), offsets.toByteArray())
        return box("stbl", box("stsd", u32(0), u32(0)), stts, stss, stsc, stsz, co64)
    }

    private fun trak(handler: String, stbl: ByteArray): ByteArray {
        val mdhd = box("mdhd", u32(0), u32(0), u32(0), u32(timescale), u32(sampleCount * sampleDelta), u32(0))
        val hdlr = box("hdlr", u32(0), u32(0), handler.toByteArray(Charsets.ISO_8859_1), ByteArray(13))
        val minf = box("minf", stbl)
        return box("trak", box("tkhd", u32(0), ByteArray(80)), box("mdia", mdhd, hdlr, minf))
    }

    /** An audio track whose sample tables are deliberately huge — that is the whole problem. */
    private fun bigAudioTrak(padBytes: Int) =
        trak("soun", box("stbl", box("stsd", u32(0), u32(0)), box("co64", u32(0), u32(0), ByteArray(padBytes))))

    private fun mvhd() = box("mvhd", u32(0), u32(0), u32(0), u32(timescale), u32(sampleCount * sampleDelta), ByteArray(80))

    private val ftyp = box("ftyp", "isom".toByteArray(Charsets.ISO_8859_1), u32(512))

    private class Fake(private val data: ByteArray) : RandomSource {
        var bytesRead = 0L
            private set

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(length, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            bytesRead += n
            return n
        }

        override fun length(): Long = data.size.toLong()
        override fun close() = Unit
    }

    /** ftyp + moov(mvhd, video trak, 3 fat audio traks, fat udta) + a stub mdat header. */
    private fun buildFile(): Triple<ByteArray, Long, Long> {
        val moov = box(
            "moov",
            mvhd(),
            trak("vide", videoStbl()),
            bigAudioTrak(1_500_000),
            bigAudioTrak(1_200_000),
            bigAudioTrak(900_000),
            box("udta", ByteArray(1_100_000)),
        )
        val file = ftyp + moov + u32(8) + "mdat".toByteArray(Charsets.ISO_8859_1)
        return Triple(file, ftyp.size.toLong(), moov.size.toLong())
    }

    /** Walk the moov's children the way MMR does: read 8 bytes at the box header, trust its size, jump. [read] serves the slimmed view. */
    private fun walkChildren(moovStart: Long, moovSize: Long, read: (Long, Int) -> ByteArray): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        var pos = moovStart + 8
        while (pos + 8 <= moovStart + moovSize) {
            val hdr = read(pos, 8)
            val size = ((hdr[0].toLong() and 0xFF) shl 24) or ((hdr[1].toLong() and 0xFF) shl 16) or
                ((hdr[2].toLong() and 0xFF) shl 8) or (hdr[3].toLong() and 0xFF)
            out.add(String(hdr, 4, 4, Charsets.ISO_8859_1) to size)
            if (size < 8) break
            pos += size
        }
        return out
    }

    @Test
    fun `only the video track is downloaded, everything else becomes a same-size free box`() {
        val (file, moovStart, moovSize) = buildFile()
        val src = Fake(file)
        val plan = Thumbs.planSlimMoov(src, moovStart, moovSize)
        assertNotNull("plain moov layout must be slimmable", plan)
        plan!!

        // The three audio traks and udta are ~4.7 MB of the moov and must not be fetched.
        assertTrue("moov is the fat kind this is about", moovSize > 4_000_000)
        assertTrue(
            "downloaded ${plan.bytes} of $moovSize — the audio tables were fetched anyway",
            plan.bytes < moovSize / 4,
        )
        assertEquals("the plan's byte count must be what was really read", plan.bytes, src.bytesRead)

        // Serve the plan's regions the way NetVideoDataSource does, and walk the box chain
        // over it: a stub whose size disagreed with the box it replaces would derail this
        // walk and, in the real file, land MMR somewhere other than mdat.
        val serve = { pos: Long, len: Int ->
            val region = plan.regions.first { pos >= it.first && pos < it.first + it.second.size }
            region.second.copyOfRange((pos - region.first).toInt(), (pos - region.first).toInt() + len)
        }
        val slimmed = walkChildren(moovStart, moovSize, serve)
        val original = walkChildren(moovStart, moovSize) { pos, len ->
            file.copyOfRange(pos.toInt(), pos.toInt() + len)
        }
        assertEquals("box sizes must be untouched", original.map { it.second }, slimmed.map { it.second })
        assertEquals(
            listOf("mvhd", "trak", "free", "free", "free", "free"),
            slimmed.map { it.first },
        )
    }

    @Test
    fun `the compact copy locates the same keyframe as the full moov`() {
        val (file, moovStart, moovSize) = buildFile()
        val plan = Thumbs.planSlimMoov(Fake(file), moovStart, moovSize)!!
        val full = file.copyOfRange(moovStart.toInt(), (moovStart + moovSize).toInt())

        // 1/10 of a 20 s track = 2 s = sample 21, which is also the chunk boundary
        // (5 samples per chunk), so the keyframe sits at the start of chunk 5.
        val targetUs = 2_000_000L
        val expected = chunk0 + 4 * chunkStride
        assertEquals(expected, Thumbs.findKeyframeOffset(full, targetUs))
        assertEquals(
            "chunk offsets are absolute file positions, so dropping boxes around the video trak changes nothing",
            Thumbs.findKeyframeOffset(full, targetUs),
            Thumbs.findKeyframeOffset(plan.compact, targetUs),
        )
        assertTrue("the compact copy is the small one", plan.compact.size < moovSize / 4)
    }

    @Test
    fun `declines rather than guesses when the children do not tile the moov`() {
        val (file, moovStart, moovSize) = buildFile()
        // Claim the moov is one byte longer than its children add up to: the last child no
        // longer ends where moov does, so we cannot tell what the trailing byte belongs to.
        // Stubbing boxes inside a structure we misread is how you hand MMR a broken file.
        assertNull(Thumbs.planSlimMoov(Fake(file), moovStart, moovSize + 1))
    }

    @Test
    fun `declines when there is no video track`() {
        val moov = box("moov", mvhd(), bigAudioTrak(1_500_000), bigAudioTrak(1_200_000))
        val file = ftyp + moov
        assertNull(Thumbs.planSlimMoov(Fake(file), ftyp.size.toLong(), moov.size.toLong()))
    }
}
