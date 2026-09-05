package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.twig.app.Format
import kotlin.math.abs

/**
 * SpaceSniffer-style rectangular treemap (squarified treemap): fills the entire
 * level with children sized in proportion, no aggregation (small files become
 * thin strips but are still tappable / long-pressable). Directories warm-toned,
 * files cool-toned (hue shifted slightly by a hash of the name); name/size are
 * only drawn when the block is large enough. Tap / long-press callbacks are
 * delegated to the host (drill-down / menu).
 */
class TreemapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onTapTile: ((TreemapEntry) -> Unit)? = null
    var onLongTile: ((TreemapEntry) -> Unit)? = null
    /** Horizontal swipe (net displacement above the threshold and horizontal > vertical) → switch panes, same interaction as the tree list. */
    var onSwipe: ((Float) -> Unit)? = null
    var onTouchDown: (() -> Unit)? = null

    /** Selected block keys ("scheme:path"), drawn with a highlight border; maintained by the host. */
    var selectedKeys: Set<String> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    private class Tile(
        val entry: TreemapEntry,
        val rect: RectF,
        val color: Int,
        val name: String,
        val sub: String,
    )

    private var node: TreemapEntry? = null
    private var tiles: List<Tile> = emptyList()

    private val dpi = resources.displayMetrics.density
    private val fill = Paint()
    private val border = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x66FFFFFF
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD000000.toInt()
        textSize = 11 * dpi
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        textSize = 9.5f * dpi
    }
    private val selPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dpi
        color = 0xFF1B5E20.toInt() // primary_dark: stands out enough on colourful blocks
    }

    // Two-finger zoom + pan (geometric transform, text doesn't scale: zooming in reveals more label space)
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    fun show(n: TreemapEntry) {
        node = n
        resetZoom()
        relayout()
        invalidate()
    }

    fun clear() {
        node = null
        tiles = emptyList()
        resetZoom()
        invalidate()
    }

    private fun resetZoom() {
        scale = 1f; tx = 0f; ty = 0f
    }

    private fun clampPan() {
        tx = tx.coerceIn(width - width * scale, 0f)
        ty = ty.coerceIn(height - height * scale, 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        relayout()
    }

    // ---- Layout (squarified) ----

    private fun relayout() {
        val n = node ?: return
        if (width == 0 || height == 0) return
        val kids = (n.children ?: emptyList()).filter { it.size > 0 }
        val total = kids.sumOf { it.size }.toDouble()
        if (total <= 0) { tiles = emptyList(); invalidate(); return }

        val scale = width.toDouble() * height / total // px² / byte
        val items = kids.map { it to it.size * scale }

        val out = ArrayList<Tile>(items.size)
        squarify(items, RectF(0f, 0f, width.toFloat(), height.toFloat())) { e, r ->
            out.add(Tile(e, r, tileColor(e), e.name, Format.size(e.size)))
        }
        tiles = out
        invalidate()
    }

    /** Squarified treemap: lay out row by row along the short edge, switching rows when the worst aspect ratio stops improving. */
    private fun squarify(
        items: List<Pair<TreemapEntry, Double>>,
        bounds: RectF,
        emit: (TreemapEntry, RectF) -> Unit,
    ) {
        var free = RectF(bounds)
        var row = ArrayList<Pair<TreemapEntry, Double>>()
        var rowSum = 0.0
        var rowMin = Double.MAX_VALUE
        var rowMax = 0.0
        fun worst(sum: Double, mn: Double, mx: Double, side: Double): Double {
            if (sum <= 0 || side <= 0) return Double.MAX_VALUE
            val s2 = sum * sum
            val w2 = side * side
            return maxOf(w2 * mx / s2, s2 / (w2 * mn))
        }
        var i = 0
        while (i < items.size) {
            val a = items[i].second.coerceAtLeast(0.25)
            val side = minOf(free.width(), free.height()).toDouble()
            if (row.isEmpty() ||
                worst(rowSum, rowMin, rowMax, side) >=
                worst(rowSum + a, minOf(rowMin, a), maxOf(rowMax, a), side)
            ) {
                row.add(items[i])
                rowSum += a
                rowMin = minOf(rowMin, a)
                rowMax = maxOf(rowMax, a)
                i++
            } else {
                free = flushRow(row, rowSum, free, emit)
                row = ArrayList()
                rowSum = 0.0; rowMin = Double.MAX_VALUE; rowMax = 0.0
            }
        }
        if (row.isNotEmpty()) flushRow(row, rowSum, free, emit)
    }

    private fun flushRow(
        row: List<Pair<TreemapEntry, Double>>,
        rowSum: Double,
        free: RectF,
        emit: (TreemapEntry, RectF) -> Unit,
    ): RectF {
        val alongTop = free.width() < free.height() // shorter side is the width → lay this row across the top
        val side = (if (alongTop) free.width() else free.height()).toDouble()
        val thick = (rowSum / side).toFloat()
        var off = 0f
        for ((e, area) in row) {
            val len = (area / thick).toFloat()
            val r = if (alongTop) {
                RectF(free.left + off, free.top, free.left + off + len, free.top + thick)
            } else {
                RectF(free.left, free.top + off, free.left + thick, free.top + off + len)
            }
            emit(e, r)
            off += len
        }
        return if (alongTop) {
            RectF(free.left, free.top + thick, free.right, free.bottom)
        } else {
            RectF(free.left + thick, free.top, free.right, free.bottom)
        }
    }

    /** Directories warm orange, files blue-grey; within the same class hue/brightness are shifted slightly by a hash of the name so adjacent blocks stay distinguishable. */
    private fun tileColor(e: TreemapEntry): Int {
        val h = abs(e.name.hashCode())
        val hue = if (e.isDir) 26f + (h % 6) * 4f else 202f + (h % 6) * 5f
        val sat = 0.34f + ((h shr 3) % 4) * 0.07f
        val v = 0.84f + ((h shr 5) % 3) * 0.05f
        return Color.HSVToColor(floatArrayOf(hue, sat, v))
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        val pad = 2f // gap between blocks (screen pixels, doesn't scale with zoom)
        val w = width.toFloat()
        val h = height.toFloat()
        for (t in tiles) {
            // Layout coords → screen coords; skip blocks outside the viewport
            val sl = t.rect.left * scale + tx
            val st = t.rect.top * scale + ty
            val sr = t.rect.right * scale + tx
            val sb = t.rect.bottom * scale + ty
            if (sr < 0 || sl > w || sb < 0 || st > h) continue
            fill.color = t.color
            val r = RectF(sl, st, sr - pad, sb - pad)
            if (r.width() <= 0 || r.height() <= 0) { // thin strip: don't leave a gap, ensure it's visible and tappable
                canvas.drawRect(RectF(sl, st, sr, sb), fill)
                continue
            }
            canvas.drawRect(r, fill)
            canvas.drawRect(r, border)
            if (selectedKeys.isNotEmpty() && keyOf(t.entry) in selectedKeys) {
                val inset = selPaint.strokeWidth / 2
                canvas.drawRect(
                    RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset),
                    selPaint,
                )
            }
            // Fixed font size; show based on screen block size — zooming in reveals more labels
            if (r.width() > 42 * dpi && r.height() > 15 * dpi) {
                canvas.save()
                canvas.clipRect(r)
                val x = r.left + 4 * dpi
                canvas.drawText(t.name, x, r.top + 12 * dpi, namePaint)
                if (r.height() > 27 * dpi) {
                    canvas.drawText(t.sub, x, r.top + 23 * dpi, subPaint)
                }
                canvas.restore()
            }
        }
    }

    // ---- Gestures ----

    private val gd = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tileAt(e.x, e.y)?.let { onTapTile?.invoke(it.entry) }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                tileAt(e.x, e.y)?.let { onLongTile?.invoke(it.entry) }
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float,
            ): Boolean {
                if (scale <= 1f) return false
                tx -= dx; ty -= dy // after zooming in, single-finger drag pans
                clampPan()
                invalidate()
                return true
            }
        },
    )

    private val scaleGd = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val ns = (scale * d.scaleFactor).coerceIn(1f, 40f)
                // Keep the focal point on the content stationary
                tx = d.focusX - (d.focusX - tx) * (ns / scale)
                ty = d.focusY - (d.focusY - ty) * (ns / scale)
                scale = ns
                clampPan()
                invalidate()
                return true
            }
        },
    )

    /** Screen coords → layout coords then hit-test. */
    private fun tileAt(x: Float, y: Float): Tile? {
        val cx = (x - tx) / scale
        val cy = (y - ty) / scale
        return tiles.firstOrNull { it.rect.contains(cx, cy) }
    }

    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    private var gdCancelled = false // once pinching starts, don't feed gd this round, prevents accidental tap/long-press

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGd.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchDown?.invoke()
                downX = event.x; downY = event.y
                swiped = false; gdCancelled = false
            }
            MotionEvent.ACTION_MOVE -> {
                // Only horizontal swipe switches panes when not zoomed in; single-finger drag pans after zoom
                if (!swiped && !scaleGd.isInProgress && scale <= 1f) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > 48 * dpi && abs(dx) > abs(dy)) {
                        swiped = true
                        onSwipe?.invoke(dx)
                    }
                }
            }
        }
        if (scaleGd.isInProgress) {
            swiped = true
            if (!gdCancelled) {
                gdCancelled = true
                val c = MotionEvent.obtain(event)
                c.action = MotionEvent.ACTION_CANCEL
                gd.onTouchEvent(c)
                c.recycle()
            }
        } else if (!gdCancelled) {
            gd.onTouchEvent(event)
        }
        return true
    }

    companion object {
        fun keyOf(e: TreemapEntry): String = "${e.file.scheme}:${e.file.path}"
    }
}
