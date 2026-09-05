package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import com.twig.app.OpenFiles
import com.twig.core.XFile
import java.io.File
import kotlin.math.roundToInt

/**
 * Image decoding: downsampling + EXIF rotation + on-zoom re-decode of a hi-res region.
 *
 * Originally lived inside `ImageViewerActivity`; lifted out because **the image-compare page needs the same
 * decoding quality** — a slimmed-down alternative would mean zooming in to inspect details renders blurry,
 * and "zoom in to compare details" is precisely the main use of image compare.
 * These are all stateless functions that anyone can call.
 */

/** Memory cap for hi-res chunks; see [decodeRegion]. */
private const val MAX_HIRES_SCREENS = 2
private const val MAX_HIRES_PX = 12_000_000L

/**
 * @param bmp     Downsampled bitmap with EXIF rotation applied.
 * @param actual  Bitmap → original image pixel ratio (after density scaling no longer equals inSampleSize;
 *               it's the actual width ratio).
 * @param exifRot EXIF rotation angle already baked into [bmp].
 * @param path    Local file path, used when zooming in to re-decode a hi-res region.
 * @param origW/origH Original image dimensions in the file coordinate system (unrotated).
 */
class Decoded(
    val bmp: Bitmap,
    val actual: Float,
    val exifRot: Int,
    val path: String,
    val origW: Int,
    val origH: Int,
)

internal fun decodeImage(ctx: Context, file: XFile): Decoded? {
    val local = if (file.scheme == "file") File(file.path) else OpenFiles.materialize(ctx, file)
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(local.absolutePath, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    val ow = opts.outWidth
    val oh = opts.outHeight
    val rot = exifDegrees(local.absolutePath)

    // ★ Target size is computed from "how many pixels FIT-to-fill the screen actually needs", not a hard cut at
    // the screen's long edge. The orientation after EXIF rotation is the display orientation; autoFit may also
    // rotate 90° more; take the larger of the two requirements — otherwise we'd have to upscale with interpolation
    // after the rotation (see screenFitRotation).
    val swapped = rot == 90 || rot == 270
    val dw = if (swapped) oh else ow
    val dh = if (swapped) ow else oh
    val dm = ctx.resources.displayMetrics
    val sw = dm.widthPixels.toFloat()
    val sh = dm.heightPixels.toFloat()
    val need = maxOf(
        minOf(sw / dw, sh / dh), // fill the screen in the current orientation
        minOf(sw / dh, sh / dw), // fill after autoFit's 90° rotation
    ).coerceAtMost(1f) // original is smaller than the screen → decode at native size, don't upscale

    // inSampleSize only takes powers of 2; take the smallest one that's "no smaller than the target", and let
    // density scaling mop up the remainder — relying on powers of 2 alone is off by up to 2× in the worst case
    // (e.g. 4096 over the limit falls back to 2048, missing the 2465 the screen needs), forcing the hi-res region
    // path to kick in even at FIT level, and that region is more expensive than the base bitmap itself.
    var sample = 1
    while (need * (sample * 2) <= 1f) sample *= 2
    val targetW = (ow * need).roundToInt().coerceAtLeast(1)

    // A single bitmap can be tens of MB; if memory runs short, step down and retry — still better than failing to display it at all
    var bmp: Bitmap? = null
    while (sample <= 64) {
        val s = sample
        val afterW = ow / s
        try {
            bmp = BitmapFactory.decodeFile(
                local.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = s
                    if (targetW < afterW) { // still larger than the target after the power-of-2 downsample; use density scaling for exact size
                        inScaled = true
                        inDensity = afterW
                        inTargetDensity = targetW
                    }
                },
            )
            break // returning null is a format issue, retrying won't help
        } catch (_: OutOfMemoryError) {
            sample *= 2
        }
    }
    if (bmp == null) return null
    // ★ inDensity / inTargetDensity use pixel counts as "density"; BitmapFactory writes inTargetDensity into the
    // bitmap's density field, then BitmapDrawable rescales again by "bitmap density : canvas density",
    // turning the image into a small thumbnail in the top-left corner. After decoding, density must be reset
    // to the screen's (Coil has the same step).
    bmp.density = dm.densityDpi
    // Bitmap → original image ratio: after density scaling it no longer equals inSampleSize; compute from the actual width
    // ratio (rotation swaps width and height, so compute this first)
    val actual = ow.toFloat() / bmp.width
    if (rot != 0) bmp = rotate(bmp, rot)
    return Decoded(bmp, actual, rot, local.absolutePath, ow, oh)
}

/**
 * [rect] is a rectangle in the **display bitmap** coordinate system (already containing the [rot] rotation
 * and the d.actual× downsample); it must first be inverse-transformed by [rot] back into the file coordinate
 * system to feed BitmapRegionDecoder, then the decoded region rotated back the right way.
 */
internal fun decodeRegion(d: Decoded, rot: Int, rect: RectF, scale: Float, vw: Int, vh: Int): Bitmap? {
    val s = d.actual
    val l = (rect.left * s).toInt(); val t = (rect.top * s).toInt()
    val r = (rect.right * s).toInt(); val bo = (rect.bottom * s).toInt()
    val ow = d.origW; val oh = d.origH
    // Inverse mapping of file coordinates (X,Y) --clockwise rot--> display coordinates (x,y)
    val src = when (rot) {
        90 -> Rect(t, oh - r, bo, oh - l)
        180 -> Rect(ow - r, oh - bo, ow - l, oh - t)
        270 -> Rect(ow - bo, l, ow - t, r)
        else -> Rect(l, t, r, bo)
    }
    if (!src.intersect(0, 0, ow, oh) || src.width() < 1 || src.height() < 1) return null

    // The region itself is also downsampled: decoded to roughly 1:1 with the screen is enough.
// ★ Don't compare the region's size against the view's width and height separately with && —
// when the region and view have different aspect ratios, whichever side first dips below the view would stop
// downsampling, and at scales just over 1 (visible area almost the whole image) this would decode the entire
// original into memory (tens of MB), then double it on rotate, guaranteed OOM.
// The original image's pixel density is s/scale times the screen's; rs is just the power-of-2 of that, independent
// of the aspect ratio.
    var rs = 1
    while (rs * 2 <= s / scale) rs *= 2
    // Fallback: after rounding rs down to a power of 2 the region can still reach 4× the viewport; with the
    // expansion margin on top it's even more; enforce a hard cap by total pixel count.
    // ★ Don't write it as just "a few screens" — on a 4K screen (1644×3840) two screens is 12.6M pixels = 50MB,
    // making the cap toothless
    val maxPx = minOf(vw.toLong() * vh * MAX_HIRES_SCREENS, MAX_HIRES_PX)
    while ((src.width().toLong() / rs) * (src.height() / rs) > maxPx) rs *= 2
    // The cap pushed rs to where it's no denser than the base bitmap — decoding this region would be wasted; save the memory and decode
    if (rs >= s) return null

    @Suppress("DEPRECATION")
    val dec = runCatching { BitmapRegionDecoder.newInstance(d.path, false) }.getOrNull() ?: return null
    val piece = try {
        // OOM can't be swallowed only by the outer runCatching: once the heap is tied up by this huge region,
        // what crashes is usually an unrelated allocation on the main thread
        var out: Bitmap? = null
        while (rs <= 64) {
            val cur = rs
            try {
                out = dec.decodeRegion(src, BitmapFactory.Options().apply { inSampleSize = cur })
                break
            } catch (_: OutOfMemoryError) {
                rs *= 2
            } catch (_: Throwable) {
                break // invalid region / unsupported format, retrying won't help
            }
        }
        out
    } finally {
        runCatching { dec.recycle() }
    } ?: return null
    if (rot == 0) return piece
    return try { rotate(piece, rot) } catch (_: OutOfMemoryError) { null } // rotation requires another copy
}

internal fun exifDegrees(path: String): Int = runCatching {
    when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}.getOrDefault(0)

internal fun rotate(bmp: Bitmap, deg: Int): Bitmap {
    val mtx = Matrix().apply { postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mtx, true)
}
