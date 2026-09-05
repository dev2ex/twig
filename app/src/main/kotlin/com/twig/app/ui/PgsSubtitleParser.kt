package com.twig.app.ui

import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Our own PGS (Blu-ray graphical bitmap subtitle) parser, replacing media3's official
 * `androidx.media3.extractor.text.pgs.PgsParser` — **the official one emits only one
 * Cue per Display Set**, so when a frame carries multiple subtitle blocks
 * (speaker caption + on-screen note, top + bottom, left + right columns) only one
 * of them shows.
 *
 * Concrete gaps in the official implementation (verified against the 1.9.0
 * decompilation):
 * - `CueBuilder` only has a single set of `bitmapX/bitmapY/bitmapWidth/bitmapHeight`
 *   plus one `bitmapData`; after PCS's `number_of_composition_objects`, **only the
 *   first composition object's coordinates are read** (the source has a hard
 *   `skipBytes(11)` that jumps past object_id/window_id/cropped_flag), so the rest
 *   of the objects don't even have their position parsed.
 * - Multiple ODSs (Object Definition Segment) overwrite each other: the second ODS
 *   arriving resets `bitmapData`, sets a new width/height, so we end up drawing
 *   **the last object's bitmap** with **the first object's coordinates**, leaving only
 *   one block.
 * - Every `parse()` call starts with `cueBuilder.reset()` and keeps no epoch state,
 *   so a Display Set that "reuses the previous objects, only updates the palette"
 *   (the common fade pattern) decodes to empty and the subtitle just blinks out.
 *
 * Here we implement the BD-ROM PG spec in full: one Display Set → **one Cue per
 * composition object**; objects (ODS) and palettes (PDS) are cached by epoch and
 * only cleared on Epoch Start, so a pure palette-update Display Set still produces
 * images. Coordinate/size semantics match the official (plane-relative fraction +
 * top-left anchor), so [BitmapCueView] doesn't need any rendering-side changes.
 *
 * The input is a whole Display Set (PCS→WDS→PDS*→ODS*→END, segments back to back as
 * `[type][len][payload]`), sliced by the container and fed in here by
 * [PgsTsReader] (M2TS) or MatroskaExtractor (MKV).
 */
@UnstableApi
class PgsSubtitleParser : SubtitleParser {

    /**
     * ODS-in-progress object: the raw RLE bytes (may span multiple ODS fragments),
     * with decoding deferred until Cue time so it can use the current palette.
     * The decoded bitmap is kept together with "which palette version we used":
     * moving subtitles are the **same object at different coordinates** fired off
     * dozens or hundreds of times, and re-decoding hundreds of thousands of pixels
     * of RLE per group is pure CPU burn, plus it makes [BitmapCueView]'s
     * downsample cache (keyed by bitmap object identity) miss every time → dropped
     * frames, looking like flicker.
     */
    private class PgsObject(val width: Int, val height: Int) {
        val rle = ByteArrayOutputStream()
        var complete = false
        var bitmap: Bitmap? = null
        var bitmapPaletteId = -1
        var bitmapPaletteRev = -1
        // Crop result is also cached: the other way to do a scrolling/moving
        // subtitle is to leave the object still and just move the crop window.
        var cropSrc: Bitmap? = null
        var cropX = -1
        var cropY = -1
        var cropBitmap: Bitmap? = null
    }

    /** One composition object inside a PCS: references some ODS and gives its landing point on the frame (optionally with a crop window). */
    private class Composition(val objectId: Int, val x: Int, val y: Int, val crop: IntArray?)

    private val buffer = ParsableByteArray()
    private val inflatedBuffer = ParsableByteArray()
    private var inflater: Inflater? = null

    // ---- Epoch state (survives across Display Sets; cleared on Epoch Start / seek) ----
    private val objects = HashMap<Int, PgsObject>()
    private val palettes = HashMap<Int, IntArray>()
    /** Palette contents change → +1, used to decide whether a cached object bitmap is still valid (fades keep mutating it). */
    private val paletteRevs = HashMap<Int, Int>()
    private var planeWidth = 0
    private var planeHeight = 0
    private var pendingObjectId = -1
    private var pendingObject: PgsObject? = null

    // ---- Current Display Set state ----
    private val compositions = ArrayList<Composition>()
    private var activePaletteId = 0
    private var sawPresentation = false

    override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

    override fun reset() {
        objects.clear()
        palettes.clear()
        paletteRevs.clear()
        compositions.clear()
        pendingObjectId = -1
        pendingObject = null
        planeWidth = 0
        planeHeight = 0
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        buffer.reset(data, offset + length)
        buffer.setPosition(offset)
        // The PGS track inside MKV may be entirely zlib-compressed (the official PgsParser
// also does this step — keep behavior identical).
        val inf = inflater ?: Inflater().also { inflater = it }
        if (Util.maybeInflate(buffer, inflatedBuffer, inf)) {
            buffer.reset(inflatedBuffer.data, inflatedBuffer.limit())
        }
        compositions.clear()
        sawPresentation = false
        while (buffer.bytesLeft() >= 3) {
            val type = buffer.readUnsignedByte()
            val len = buffer.readUnsignedShort()
            if (len > buffer.bytesLeft()) break
            val end = buffer.position + len
            when (type) {
                SEGMENT_PALETTE -> parsePalette(end)
                SEGMENT_OBJECT -> parseObject(end)
                SEGMENT_PRESENTATION -> parsePresentation(end)
            }
            buffer.setPosition(end)
        }
        // One sample is exactly one Display Set: emit once after the whole set is decoded.
        // Empty compositions (PCS's number_of_composition_objects == 0) is a "clear screen" command —
        // emit an empty list to wipe the subtitle.
        val cues = buildCues()
        // ★ Only emit after we've read a full PCS group. Truncated/half samples (damaged
        //   stream, seek landing in the middle, container's sample slicing off) decode to
        //   nothing, and emitting an empty list there means wiping the whole subtitle for
        //   one frame — one bad block makes the whole screen flicker. Not emitting means
        //   dropping this sample, the screen keeps the previous group, no visible glitch.
        if (sawPresentation && (compositions.isEmpty() || cues.isNotEmpty())) {
            output.accept(CuesWithTiming(cues, C.TIME_UNSET, C.TIME_UNSET))
        }
    }

    /** PCS: plane size + epoch control + which objects this group shows and where each lands. */
    private fun parsePresentation(end: Int) {
        if (end - buffer.position < 11) return
        sawPresentation = true
        planeWidth = buffer.readUnsignedShort()
        planeHeight = buffer.readUnsignedShort()
        buffer.skipBytes(3) // frame_rate(1) + composition_number(2)
        val state = buffer.readUnsignedByte()
        if (state and COMPOSITION_STATE_EPOCH_START != 0) {
            // New epoch: every cached object/palette from before is invalidated.
            objects.clear()
            palettes.clear()
            pendingObject = null
            pendingObjectId = -1
        }
        buffer.skipBytes(1) // palette_update_flag
        activePaletteId = buffer.readUnsignedByte()
        val count = buffer.readUnsignedByte()
        repeat(count) {
            if (end - buffer.position < 8) return
            val objectId = buffer.readUnsignedShort()
            buffer.skipBytes(1) // window_id
            val cropped = buffer.readUnsignedByte() and 0x80 != 0
            val x = buffer.readUnsignedShort()
            val y = buffer.readUnsignedShort()
            var crop: IntArray? = null
            if (cropped) {
                if (end - buffer.position < 8) return
                crop = intArrayOf(
                    buffer.readUnsignedShort(), buffer.readUnsignedShort(),
                    buffer.readUnsignedShort(), buffer.readUnsignedShort(),
                )
            }
            compositions.add(Composition(objectId, x, y, crop))
        }
    }

    /** PDS: palettes accumulate per id (entries not listed keep their previous values, per spec — fades rely on this). */
    private fun parsePalette(end: Int) {
        if (end - buffer.position < 2) return
        val id = buffer.readUnsignedByte()
        buffer.skipBytes(1) // palette_version
        val colors = palettes.getOrPut(id) { IntArray(256) }
        paletteRevs[id] = (paletteRevs[id] ?: 0) + 1
        while (end - buffer.position >= 5) {
            val index = buffer.readUnsignedByte()
            val y = buffer.readUnsignedByte()
            val cr = buffer.readUnsignedByte()
            val cb = buffer.readUnsignedByte()
            val a = buffer.readUnsignedByte()
            val r = (y + 1.40200 * (cr - 128)).toInt()
            val g = (y - 0.34414 * (cb - 128) - 0.71414 * (cr - 128)).toInt()
            val b = (y + 1.77200 * (cb - 128)).toInt()
            colors[index] = (a shl 24) or
                (Util.constrainValue(r, 0, 255) shl 16) or
                (Util.constrainValue(g, 0, 255) shl 8) or
                Util.constrainValue(b, 0, 255)
        }
    }

    /** ODS: bitmap object definition; large objects are split across multiple ODS fragments (the first carries the size, the last carries the last flag). */
    private fun parseObject(end: Int) {
        if (end - buffer.position < 4) return
        val id = buffer.readUnsignedShort()
        buffer.skipBytes(1) // object_version
        val sequence = buffer.readUnsignedByte()
        if (sequence and SEQUENCE_FIRST != 0) {
            if (end - buffer.position < 7) return
            buffer.skipBytes(3) // object_data_length (includes the 4 size bytes below, we don't need it)
            val w = buffer.readUnsignedShort()
            val h = buffer.readUnsignedShort()
            if (w <= 0 || h <= 0 || w > MAX_DIMEN || h > MAX_DIMEN) { pendingObject = null; return }
            pendingObjectId = id
            pendingObject = PgsObject(w, h)
        } else if (pendingObjectId != id) {
            // Seeked in mid-stream and missed the first fragment — drop this object entirely,
            // don't treat the fragments as a complete bitmap.
            return
        }
        val obj = pendingObject ?: return
        val n = end - buffer.position
        if (n > 0) {
            obj.rle.write(buffer.data, buffer.position, n)
            buffer.skipBytes(n)
        }
        if (sequence and SEQUENCE_LAST != 0) {
            obj.complete = true
            // An epoch normally holds one or two objects; if we hit a stream that keeps
            // defining new objects in a long epoch, cap the cache so it can't grow without bound.
            if (objects.size >= MAX_CACHED_OBJECTS && !objects.containsKey(id)) objects.clear()
            objects[id] = obj
            pendingObject = null
            pendingObjectId = -1
        }
    }

    /** Emit one Cue per composition object in this group (the official implementation only emits one — that is the gap). */
    private fun buildCues(): List<Cue> {
        if (planeWidth <= 0 || planeHeight <= 0 || compositions.isEmpty()) return emptyList()
        val colors = palettes[activePaletteId] ?: return emptyList()
        val rev = paletteRevs[activePaletteId] ?: 0
        val cues = ArrayList<Cue>(compositions.size)
        for (c in compositions) {
            val obj = objects[c.objectId] ?: continue
            if (!obj.complete) continue
            var bitmap = decoded(obj, colors, rev) ?: continue
            val crop = c.crop
            if (crop != null) {
                // The crop window picks a sub-region out of the object; the landing point is
                // still the x/y the composition provides (per spec: that is the position of
                // the cropped piece).
                val cx = crop[0].coerceIn(0, bitmap.width - 1)
                val cy = crop[1].coerceIn(0, bitmap.height - 1)
                val cw = crop[2].coerceIn(1, bitmap.width - cx)
                val ch = crop[3].coerceIn(1, bitmap.height - cy)
                bitmap = cropped(obj, bitmap, cx, cy, cw, ch)
            }
            cues.add(
                Cue.Builder()
                    .setBitmap(bitmap)
                    .setPosition(c.x.toFloat() / planeWidth)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                    .setLine(c.y.toFloat() / planeHeight, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_START)
                    .setSize(bitmap.width.toFloat() / planeWidth)
                    .setBitmapHeight(bitmap.height.toFloat() / planeHeight)
                    .build(),
            )
        }
        return cues
    }

    /**
     * PGS run-length encoding (RLE) decoding. The five code words in the spec:
     * `C` (non-zero) = 1 C-colored pixel; `00 00` = newline; `00 0L` = L transparent pixels;
     * `00 4L LL` = LL transparent pixels; `00 8L C` = L C-colored pixels; `00 CL LL C` = LL C-colored pixels.
     * Any under-width portion of a row is padded with transparent.
     */
    /** Reuse crop results by "source bitmap + crop origin"; if the crop window hasn't moved, return the same bitmap (so the cache hits). */
    private fun cropped(obj: PgsObject, src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
        val prev = obj.cropBitmap
        if (prev != null && obj.cropSrc === src && obj.cropX == x && obj.cropY == y &&
            prev.width == w && prev.height == h
        ) {
            return prev
        }
        val out = Bitmap.createBitmap(src, x, y, w, h)
        obj.cropSrc = src
        obj.cropX = x
        obj.cropY = y
        obj.cropBitmap = out
        return out
    }

    private fun decoded(obj: PgsObject, colors: IntArray, paletteRev: Int): Bitmap? {
        val cached = obj.bitmap
        if (cached != null && obj.bitmapPaletteId == activePaletteId && obj.bitmapPaletteRev == paletteRev) {
            return cached
        }
        val bmp = decode(obj, colors)
        obj.bitmap = bmp
        obj.bitmapPaletteId = activePaletteId
        obj.bitmapPaletteRev = paletteRev
        return bmp
    }

    private fun decode(obj: PgsObject, colors: IntArray): Bitmap? {
        val w = obj.width
        val h = obj.height
        val data = obj.rle.toByteArray()
        val argb = IntArray(w * h)
        var index = 0
        var pos = 0
        while (pos < data.size && index < argb.size) {
            val first = data[pos++].toInt() and 0xFF
            var color: Int
            var run: Int
            if (first != 0) {
                color = colors[first]
                run = 1
            } else {
                if (pos >= data.size) break
                val second = data[pos++].toInt() and 0xFF
                if (second == 0) {
                    // Newline marker: the spec requires every row to be encoded to w pixels,
                    // so here we do **nothing** and let the pixel index keep walking linearly
                    // (ffmpeg pgssub and media3 both behave this way). Don't casually write
                    // "jump to the start of the next row": a fully-encoded row would then leave
                    // an entire blank row, making the image shift every other row; and
                    // "only top up rows that aren't full" doesn't work either — a row that
                    // only encoded `00 00` is also at row start, and the two cases are
                    // indistinguishable at this position.
                    continue
                }
                val longRun = second and 0x40 != 0
                run = if (longRun) {
                    if (pos >= data.size) break
                    ((second and 0x3F) shl 8) or (data[pos++].toInt() and 0xFF)
                } else {
                    second and 0x3F
                }
                color = if (second and 0x80 == 0) {
                    0 // transparent
                } else {
                    if (pos >= data.size) break
                    colors[data[pos++].toInt() and 0xFF]
                }
            }
            // Fill in linear pixel order (matches ffmpeg/media3: code words not crossing
            // row boundaries is the encoder's job — don't guess, don't reorder here).
            val to = minOf(index + run, argb.size)
            java.util.Arrays.fill(argb, index, to, color)
            index = to
        }
        return runCatching { Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
    }

    companion object {
        private const val SEGMENT_PALETTE = 0x14
        private const val SEGMENT_OBJECT = 0x15
        private const val SEGMENT_PRESENTATION = 0x16
        private const val COMPOSITION_STATE_EPOCH_START = 0x80
        private const val SEQUENCE_FIRST = 0x80
        private const val SEQUENCE_LAST = 0x40
        private const val MAX_DIMEN = 8192
        private const val MAX_CACHED_OBJECTS = 64
    }
}

/**
 * Routes PGS to [PgsSubtitleParser], while letting other formats (SRT/ASS/DVB/CEA…)
 * continue to go through [DefaultSubtitleParserFactory]. The extraction stage needs
 * this in order to convert samples to `application/x-media3-cues` (the new TextRenderer
 * no longer supports the legacy decode path), so every path that builds a MediaSource —
 * [MediaSources], [newM2tsExtractorsFactory], the player's default MediaSource factory —
 * has to install this.
 */
@UnstableApi
class TwigSubtitleParserFactory : SubtitleParser.Factory {

    private val delegate = DefaultSubtitleParserFactory()

    private fun isPgs(format: Format) =
        MimeTypes.APPLICATION_PGS.equals(format.sampleMimeType, ignoreCase = true)

    override fun supportsFormat(format: Format) = isPgs(format) || delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format) =
        if (isPgs(format)) Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        else delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser =
        if (isPgs(format)) PgsSubtitleParser() else delegate.create(format)
}
