package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.media3.common.text.Cue
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bitmap subtitle (e.g. PGS) overlay layer that fills the screen.
 * A cue's position/line/size/bitmapHeight are ratios relative to the video frame; the
 * "video display rectangle" passed to [setVideoRect] maps them to screen coordinates
 * (in crop/stretch modes the rectangle can be larger than or distorted relative to the screen).
 *
 * Drawing rules:
 * - The subtitle bitmap **always keeps its original aspect ratio**, uniformly scaled by
 *   the vertical scale factor (in stretch-fill mode the picture distorts but the subtitle
 *   does not), with position aligned to the center of the frame-mapped rectangle.
 * - Cues whose bitmap bottom edge sits low are treated as main dialogue: in any picture
 *   mode they are re-anchored to the bottom of the screen; the rest (scene annotations)
 *   stay put in their original position, and may be clipped in crop mode.
 * - Integer-pixel alignment; when downscaling beyond half, step down via mipmaps to avoid
 *   aliasing.
 */
class BitmapCueView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var cues: List<Cue> = emptyList()
    private val video = RectF()
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bitmapCache = HashMap<Bitmap, Bitmap>() // original bitmap → processed (darkened / downsampled)

    fun setCues(list: List<Cue>) {
        if (cues.isEmpty() && list.isEmpty()) return
        cues = list
        // Moving subtitles reuse the same bitmap object across dozens or hundreds of new
        // coordinate sets (the parser reuses the bitmap, so the object identity stays the
        // same) — do not clear the whole cache here, only evict bitmaps that no longer
        // appear, so the mipmap results survive and do not have to be recomputed for each
        // coordinate set.
        if (bitmapCache.isNotEmpty()) {
            val alive = list.mapNotNullTo(HashSet()) { it.bitmap }
            bitmapCache.keys.retainAll(alive)
        }
        invalidate()
    }

    /** The display rectangle of the video picture on screen (centered; in crop/stretch modes it can exceed the screen or be distorted). */
    fun setVideoRect(left: Float, top: Float, w: Float, h: Float) {
        video.set(left, top, left + w, top + h)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (cues.isEmpty() || video.isEmpty) return
        val vw = video.width()
        val vh = video.height()
        val sw = width.toFloat()
        val sh = height.toFloat()
        // A single screen can contain multiple subtitle blocks (dialogue + scene annotations,
        // top and bottom blocks, left and right columns). Compute the target rectangle for
        // every block first, then re-anchor the main-dialogue group **as a whole** to the
        // bottom of the screen — anchoring each block individually to the bottom would
        // squish blocks that were originally offset onto the same row, overlapping each other.
        val bmps = ArrayList<Bitmap>(cues.size)
        val rects = ArrayList<RectF>(cues.size)
        val scales = ArrayList<Float>(cues.size)
        val dialogue = ArrayList<Int>(cues.size) // indices of the blocks classified as main dialogue in the tables above
        for (cue in cues) {
            val bmp = cue.bitmap ?: continue
            // Unified scale factor: prefer vertical (bitmapHeight relative to frame height),
            // unaffected by horizontal stretching
            val s = when {
                cue.bitmapHeight != Cue.DIMEN_UNSET -> cue.bitmapHeight * vh / bmp.height
                cue.size != Cue.DIMEN_UNSET -> cue.size * vw / bmp.width
                else -> 1f
            }
            val cw = bmp.width * s
            val ch = bmp.height * s
            // The frame-mapped rectangle (may be distorted by picture mode); place the
            // aspect-ratio-preserving subtitle by its center point
            val fw = if (cue.size != Cue.DIMEN_UNSET) cue.size * vw else cw
            val fh = if (cue.bitmapHeight != Cue.DIMEN_UNSET) cue.bitmapHeight * vh else ch
            var fx = if (cue.position != Cue.DIMEN_UNSET) video.left + cue.position * vw
            else video.left + (vw - fw) / 2
            var fy = if (cue.line != Cue.DIMEN_UNSET) video.top + cue.line * vh
            else video.top + vh * 0.85f - fh
            when (cue.positionAnchor) {
                Cue.ANCHOR_TYPE_MIDDLE -> fx -= fw / 2
                Cue.ANCHOR_TYPE_END -> fx -= fw
            }
            when (cue.lineAnchor) {
                Cue.ANCHOR_TYPE_MIDDLE -> fy -= fh / 2
                Cue.ANCHOR_TYPE_END -> fy -= fh
            }
            val cx = fx + fw / 2
            val cy = fy + fh / 2
            val r = RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
            // Main-dialogue classification uses the bitmap bottom edge (some sources' PGS
            // objects are nearly full-frame, so center-point classification would fail)
            if ((fy + fh) > video.top + vh * 0.75f) dialogue.add(rects.size)
            bmps.add(bmp)
            rects.add(r)
            scales.add(s)
        }
        if (dialogue.isNotEmpty()) {
            // Translate the main-dialogue group as a whole: anchor to the bottom of the
            // screen (leave 2% margin), keep the entire group inside the screen horizontally,
            // and preserve the relative positions of blocks within the group (top/bottom
            // rows, left/right columns).
            var bottom = Float.NEGATIVE_INFINITY
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            for (i in dialogue) {
                bottom = max(bottom, rects[i].bottom)
                left = minOf(left, rects[i].left)
                right = max(right, rects[i].right)
            }
            val dy = sh * 0.98f - bottom
            var dx = if (left < 0f) -left else 0f
            if (right + dx > sw) dx = sw - right // when the group is wider than the screen, prioritize the right edge (matches the order of the original per-block implementation)
            for (i in dialogue) rects[i].offset(dx, dy)
        }
        for (i in rects.indices) {
            val r = rects[i]
            val bmp = bmps[i]
            val s = scales[i]
            // Integer-pixel alignment to avoid half-pixel sampling causing fuzzy edges
            dst.set(
                r.left.roundToInt().toFloat(), r.top.roundToInt().toFloat(),
                (r.left.roundToInt() + r.width().roundToInt()).toFloat(),
                (r.top.roundToInt() + r.height().roundToInt()).toFloat(),
            )
            canvas.drawBitmap(prepared(bmp, s), null, dst, paint)
        }
    }

    /** When downscaling beyond half, step down via mipmaps to avoid aliasing; results are cached by original bitmap. */
    private fun prepared(bmp: Bitmap, scale: Float): Bitmap {
        if (scale > 0.5f) return bmp
        bitmapCache[bmp]?.let { return it }
        var cur = bmp
        var s = scale
        while (s <= 0.5f && cur.width > 1 && cur.height > 1) {
            cur = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
            s *= 2
        }
        bitmapCache[bmp] = cur
        return cur
    }
}
