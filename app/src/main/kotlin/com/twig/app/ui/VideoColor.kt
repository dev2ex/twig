package com.twig.app.ui

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil

@UnstableApi
internal fun isHdrVideo(format: Format): Boolean {
    if (ColorInfo.isTransferHdr(format.colorInfo) ||
        format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
    ) return true
    if (format.sampleMimeType != MimeTypes.VIDEO_H265 ||
        (format.colorInfo?.colorTransfer ?: Format.NO_VALUE) != Format.NO_VALUE
    ) return false

    // Some MKVs carry the transfer function only in the HEVC parameter set.
    val transfer = try {
        hevcBaseLayerSps(format)?.colorTransfer
    } catch (_: RuntimeException) {
        null
    }
    return transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
}

@UnstableApi
internal fun hevcBaseLayerSps(format: Format): NalUnitUtil.H265SpsData? =
    format.initializationData.asSequence()
        .flatMap { CodecSpecificDataUtil.splitNalUnits(it)?.asSequence() ?: emptySequence() }
        .firstOrNull { nal ->
            nal.size > 6 && (nal[4].toInt() and 0x7e) shr 1 == 33 &&
                nal[4].toInt() and 1 == 0 && nal[5].toInt() and 0xf8 == 0
        }?.let { NalUnitUtil.parseH265SpsNalUnit(it, 4, it.size, null) }
