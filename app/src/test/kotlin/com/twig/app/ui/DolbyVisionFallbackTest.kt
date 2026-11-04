package com.twig.app.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.*
import androidx.media3.extractor.mkv.MatroskaExtractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DolbyVisionFallbackTest {
    // Real hvcC from the reported UHD remux: Main 10, high tier, level 5.1.
    private val hvcc = (
        "01222000000090000000000099f000fcfdfafa00000f03a00001001840010c01" +
        "ffff222000000300900000030000030099959409a10001003d42010122200000" +
        "0300900000030000030099a001e020021c4d96565924c2f016a1220136c20000" +
        "0300020000030030c13920bc000055d4a80000aba951a2000100084401c17285" +
        "8b4224"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun vision(): Format = Format.Builder()
        .setId("video").setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
        .setCodecs("dvhe.07.06").setWidth(3840).setHeight(2160).setFrameRate(24f)
        .setInitializationData(HevcConfig.parse(ParsableByteArray(hvcc)).initializationData)
        .setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084).setColorRange(C.COLOR_RANGE_LIMITED)
            .setLumaBitdepth(10).setChromaBitdepth(10)
            .setHdrStaticInfo(ByteArray(25) { it.toByte() }).build())
        .build()

    @Test fun unsupportedVisionUsesRealHevcProfileAndPreservesHdrMetadata() {
        val original = vision()
        val result = DolbyVisionFallback.format(original) { it.sampleMimeType == MimeTypes.VIDEO_H265 }
        assertEquals(MimeTypes.VIDEO_H265, result.sampleMimeType)
        assertEquals("hvc1.2.4.H153.90", result.codecs)
        assertEquals(original.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs(result.codecs).build(), result)
    }

    @Test fun nativeVisionSupportKeepsOriginalFormat() {
        val original = vision()
        assertSame(original, DolbyVisionFallback.format(original) { true })
    }

    @Test fun unsupportedHevcDoesNotAdvertisePlayableVideo() {
        val original = vision()
        assertSame(original, DolbyVisionFallback.format(original) { false })
    }

    @Test fun reportedRemuxHeaderProducesHevcVideoAndRetainsAudioAndSubtitles() {
        // Only EBML/Info/Tracks from the reported file; no media samples or attachments.
        val bytes = javaClass.getResourceAsStream("/dolby-vision-profile7-header.mkv")!!.use { it.readBytes() }
        val uri = Uri.parse("twig:///media.mkv")
        val formats = mutableListOf<Format>()
        val extractor = DolbyVisionFallbackExtractorsFactory(
            ExtractorsFactory { arrayOf(MatroskaExtractor()) },
        ) { format ->
            DolbyVisionFallback.format(format) { it.sampleMimeType == MimeTypes.VIDEO_H265 }
        }.createExtractors(uri, emptyMap())[0]
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                object : ForwardingTrackOutput(DummyTrackOutput()) {
                    override fun format(format: Format) { formats += format }
                }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        val source = ByteArrayDataSource(bytes)
        source.open(DataSpec(uri))
        try {
            val input = DefaultExtractorInput(source, 0, bytes.size.toLong())
            val position = PositionHolder()
            while (extractor.read(input, position) != Extractor.RESULT_END_OF_INPUT) {
                assertEquals(0L, position.position)
            }
        } finally {
            extractor.release()
            source.close()
        }
        val video = formats.single { it.width == 3840 }
        assertEquals(MimeTypes.VIDEO_H265, video.sampleMimeType)
        assertEquals("hvc1.2.4.H153.90", video.codecs)
        assertEquals(C.COLOR_TRANSFER_ST2084, video.colorInfo!!.colorTransfer)
        assertTrue(formats.any { it.sampleMimeType == MimeTypes.AUDIO_TRUEHD })
        assertTrue(formats.any { it.sampleMimeType == MimeTypes.AUDIO_AC3 })
        assertEquals(2, formats.count { it.sampleMimeType == MimeTypes.APPLICATION_PGS })
    }

    @Test fun incompatibleOrUnverifiedFormatsNeverFallBack() {
        val original = vision()
        val formats = listOf(
            original.buildUpon().setCodecs("dvhe.05.06").build(),
            original.buildUpon().setCodecs("dvhe.08.06").build(),
            original.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            original.buildUpon().setColorInfo(original.colorInfo!!.buildUpon()
                .setColorTransfer(C.COLOR_TRANSFER_HLG).build()).build(),
            original.buildUpon().setInitializationData(emptyList()).build(),
            original.buildUpon().setInitializationData(listOf(byteArrayOf(0, 0, 1, 0x42, 1))).build(),
        )
        for (format in formats) {
            assertSame(format, DolbyVisionFallback.format(format) { error("Must not query codecs") })
        }
    }

    @Test fun bothFactoryOverloadsRewriteOnlyVideoAndForwardSamplesAndSeeks() {
        for (withUri in listOf(false, true)) {
            val original = vision()
            val effective = DolbyVisionFallback.format(original) { it.sampleMimeType == MimeTypes.VIDEO_H265 }
            var innerOutput: ExtractorOutput? = null
            var seek: Pair<Long, Long>? = null
            val inner = object : Extractor {
                override fun sniff(input: ExtractorInput) = true
                override fun init(output: ExtractorOutput) { innerOutput = output }
                override fun read(input: ExtractorInput, position: PositionHolder) = Extractor.RESULT_END_OF_INPUT
                override fun seek(position: Long, timeUs: Long) { seek = position to timeUs }
                override fun release() = Unit
            }
            val formats = mutableListOf<Format>()
            val bytes = mutableListOf<Byte>()
            var sample: List<Long>? = null
            val recorder = object : ForwardingTrackOutput(DummyTrackOutput()) {
                override fun format(format: Format) { formats += format }
                override fun sampleData(data: ParsableByteArray, length: Int, part: Int) {
                    repeat(length) { bytes += data.readUnsignedByte().toByte() }
                }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
                    sample = listOf(timeUs, flags.toLong(), size.toLong(), offset.toLong())
                }
            }
            val factory = DolbyVisionFallbackExtractorsFactory(ExtractorsFactory { arrayOf(inner) }) {
                assertSame(original, it)
                effective
            }
            val extractor = if (withUri) factory.createExtractors(Uri.parse("twig:///media.mkv"), emptyMap())[0]
                else factory.createExtractors()[0]
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = recorder
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })
            val video = innerOutput!!.track(0, C.TRACK_TYPE_VIDEO)
            video.format(original)
            val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).build()
            innerOutput!!.track(1, C.TRACK_TYPE_AUDIO).format(audio)
            val pgs = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build()
            innerOutput!!.track(2, C.TRACK_TYPE_TEXT).format(pgs)
            video.sampleData(ParsableByteArray(byteArrayOf(1, 2, 3)), 3, TrackOutput.SAMPLE_DATA_PART_MAIN)
            video.sampleMetadata(123456, C.BUFFER_FLAG_KEY_FRAME, 3, 0, null)
            extractor.seek(2048, 123456)
            assertEquals(listOf(effective, audio, pgs), formats)
            assertEquals(listOf<Byte>(1, 2, 3), bytes)
            assertEquals(listOf(123456L, C.BUFFER_FLAG_KEY_FRAME.toLong(), 3L, 0L), sample)
            assertEquals(2048L to 123456L, seek)
        }
    }
}
