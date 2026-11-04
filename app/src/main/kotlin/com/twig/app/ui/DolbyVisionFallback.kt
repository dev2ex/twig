package com.twig.app.ui

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.container.NalUnitUtil
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/** Profile 7 is excluded from media3 1.9's automatic HEVC fallback. */
@UnstableApi
internal object DolbyVisionFallback {
    fun format(original: Format, supports: (Format) -> Boolean = ::decoderSupports): Format {
        if (original.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION ||
            original.codecs?.matches(Regex("dvhe\\.07\\.\\d{2}")) != true ||
            original.drmInitData != null
        ) return original

        // Derive the real HEVC profile/level from the base-layer SPS. Relabelling dvhe.07.06
        // alone leaves codec selection using Dolby Vision's unrelated profile/level numbers.
        val base = try {
            val sps = original.initializationData.asSequence()
                .flatMap { CodecSpecificDataUtil.splitNalUnits(it)?.asSequence() ?: emptySequence() }
                .firstOrNull { nal ->
                    nal.size > 6 && (nal[4].toInt() and 0x7e) shr 1 == 33 &&
                        nal[4].toInt() and 1 == 0 && nal[5].toInt() and 0xf8 == 0
                }?.let { NalUnitUtil.parseH265SpsNalUnit(it, 4, it.size, null) }
                ?: return original
            val codecs = NalUnitUtil.getH265BaseLayerCodecsString(original.initializationData)
                ?: return original
            // MatroskaExtractor does not copy SPS colour fields into Format. Some remuxes,
            // including the reported file, have no container Colour element at all.
            val color = original.colorInfo
            val space = color?.colorSpace?.takeUnless { it == Format.NO_VALUE } ?: sps.colorSpace
            val transfer = color?.colorTransfer?.takeUnless { it == Format.NO_VALUE } ?: sps.colorTransfer
            if (space != C.COLOR_SPACE_BT2020 || transfer != C.COLOR_TRANSFER_ST2084) return original
            val hdr = (color?.buildUpon() ?: ColorInfo.Builder())
                .setColorSpace(space).setColorTransfer(transfer)
                .setColorRange(color?.colorRange?.takeUnless { it == Format.NO_VALUE } ?: sps.colorRange)
                .setLumaBitdepth(color?.lumaBitdepth?.takeUnless { it == Format.NO_VALUE } ?: (sps.bitDepthLumaMinus8 + 8))
                .setChromaBitdepth(color?.chromaBitdepth?.takeUnless { it == Format.NO_VALUE } ?: (sps.bitDepthChromaMinus8 + 8))
                .build()
            original.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(codecs).setColorInfo(hdr).build()
        } catch (_: RuntimeException) {
            return original // Incomplete/malformed configuration must not become guessed HEVC support.
        }
        return try {
            if (supports(original) || !supports(base)) original else base
        } catch (_: MediaCodecUtil.DecoderQueryException) {
            original
        }
    }

    private fun decoderSupports(format: Format): Boolean =
        MediaCodecUtil.getDecoderInfos(requireNotNull(format.sampleMimeType), false, false)
            .any { it.isFormatSupported(format) }
}

/** Rewrites video formats before track selection; sample bytes and timestamps pass through.
 * HEVC decoders consume the compatible base layer and ignore Dolby enhancement NAL units.
 */
@UnstableApi
internal class DolbyVisionFallbackExtractorsFactory(
    private val base: ExtractorsFactory,
    private val transform: (Format) -> Format = { DolbyVisionFallback.format(it) },
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(base.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(base.createExtractors(uri, responseHeaders))

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
        base.setSubtitleParserFactory(factory)
        return this
    }

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        extractors.map { extractor ->
            object : ForwardingExtractor(extractor) {
                override fun init(output: ExtractorOutput) {
                    super.init(object : ExtractorOutput by output {
                        override fun track(id: Int, type: Int): TrackOutput {
                            val track = output.track(id, type)
                            if (type != C.TRACK_TYPE_VIDEO) return track
                            return object : ForwardingTrackOutput(track) {
                                override fun format(format: Format) {
                                    val effective = transform(format)
                                    if (effective !== format) {
                                        Log.d("twig", "player: HDR10 base-layer fallback ${format.codecs} -> ${effective.codecs}")
                                    }
                                    super.format(effective)
                                }
                            }
                        }
                    })
                }
            }
        }.toTypedArray()
}
