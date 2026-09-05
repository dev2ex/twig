package com.twig.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Hand-rolled frosted glass: shrink the cover to ~64px, run a few passes of box blur, then
 * hand it to the View to upscale smoothly, with a theme-dependent scrim overlay.
 * Zero dependencies (no deprecated RenderScript), runs in a few milliseconds on the CPU.
 */
object Blur {

    /**
     * Dynamic-range compression factor: every pixel is pulled toward the overall mean color
     * (0 = a flat color at the mean, 1 = original). Blurring only smears edges; the bright
     * and dark patches themselves are still there — 0.5 cuts the contrast in half, bright
     * areas get darker and dark areas get lighter, so the background becomes "flatter" and
     * foreground text laid over it looks the same everywhere (contrast is computed against
     * the mean, so the flatter the background the more accurate the contrast).
     */
    private const val FLATTEN = 0.5f

    /**
     * Scrim opacity (0 = no overlay). On dark themes a black overlay is applied to darken;
     * **on light themes no overlay is applied** — layering white is like pouring a layer of
     * milk on the cover: combined with flattening, the background becomes uniformly white
     * and flat, with the cover's color almost entirely gone, which looks terrible. Text
     * readability does not depend on it: [MusicTint] derives the foreground from the actual
     * background color, so a light background automatically resolves to dark text.
     */
    private const val SCRIM_DARK = 140
    private const val SCRIM_LIGHT = 0

    /** Produce a blurred background bitmap (with size [out]), flatten dynamic range, and overlay the theme's scrim (no scrim on light themes). */
    fun background(src: Bitmap, out: Int, dark: Boolean): Bitmap {
        val small = scaleDown(src, 64)
        val blurred = flatten(boxBlur(boxBlur(boxBlur(small, 2), 3), 4), FLATTEN)
        val result = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
        val c = Canvas(result)
        // scale up to fill (center-crop to a square)
        val s = out.toFloat() / minOf(blurred.width, blurred.height)
        val dw = (blurred.width * s); val dh = (blurred.height * s)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        c.save()
        c.translate((out - dw) / 2f, (out - dh) / 2f)
        c.scale(s, s)
        c.drawBitmap(blurred, 0f, 0f, paint)
        c.restore()
        val scrim = if (dark) SCRIM_DARK else SCRIM_LIGHT
        if (scrim > 0) {
            c.drawColor(if (dark) Color.argb(scrim, 0, 0, 0) else Color.argb(scrim, 255, 255, 255))
        }
        return result
    }

    /**
     * Interpolate each pixel toward the whole-image mean color by [k] (means are computed
     * per channel, so the overall hue/tone is preserved — only the brightness and saturation
     * undulations are compressed). Mutates [src] in place (the caller passes a freshly
     * generated temporary bitmap).
     */
    private fun flatten(src: Bitmap, k: Float): Bitmap {
        if (k >= 1f) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var sr = 0L; var sg = 0L; var sb = 0L
        for (c in px) { sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
        val n = px.size
        val mr = (sr / n).toFloat(); val mg = (sg / n).toFloat(); val mb = (sb / n).toFloat()
        for (i in px.indices) {
            val c = px[i]
            val r = (mr + (((c shr 16) and 0xFF) - mr) * k).toInt().coerceIn(0, 255)
            val g = (mg + (((c shr 8) and 0xFF) - mg) * k).toInt().coerceIn(0, 255)
            val b = (mb + ((c and 0xFF) - mb) * k).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        src.setPixels(px, 0, w, 0, 0, w, h)
        return src
    }

    private fun scaleDown(src: Bitmap, target: Int): Bitmap {
        val s = target.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** Horizontal+vertical box blur (simple mean) of radius r; produces a new bitmap in place. */
    private fun boxBlur(src: Bitmap, r: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        blurPass(px, tmp, w, h, r, true)
        blurPass(tmp, px, w, h, r, false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurPass(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = r * 2 + 1
        for (line in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val idx = idx(line, i.coerceIn(0, len - 1), w, horizontal)
                val c = input[idx]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (i in 0 until len) {
                output[idx(line, i, w, horizontal)] =
                    (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = input[idx(line, (i + r + 1).coerceIn(0, len - 1), w, horizontal)]
                val sub = input[idx(line, (i - r).coerceIn(0, len - 1), w, horizontal)]
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    private fun idx(line: Int, i: Int, w: Int, horizontal: Boolean) =
        if (horizontal) line * w + i else i * w + line
}
