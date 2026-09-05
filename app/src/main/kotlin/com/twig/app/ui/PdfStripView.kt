package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.twig.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The PDF reading surface: a hand-written vertical strip of pages that owns its own
 * scrolling, zooming and page-bitmap cache (the same "draw it ourselves" route
 * [TreemapView] and [HexLayout] take, for the same reason — no library does this shape).
 *
 * **Continuous and one-page-per-screen are one implementation, not two.** Both lay pages
 * out in the same strip; [pageMode] only changes two things — the vertical scroll range
 * narrows to the current page, and settling snaps to a page edge. Everything else (zoom
 * modes, margin cropping, tile rendering) is shared, which is why the second mode costs
 * almost nothing.
 *
 * Coordinates: layout is in **PDF points**, the viewport is `screen = doc * scale + t`.
 * Keeping one document-space and a single scale/translate pair (the same shape
 * [ZoomableImageView] uses) is what keeps zoom, snapping and hit-testing from each
 * growing their own geometry.
 *
 * Two-layer bitmaps, mirroring [ZoomableImageView]'s hi-res overlay:
 *  - a **preview** per page, deliberately low-res ([PREVIEW_MAX_W]) and held in an LRU —
 *    the placeholder you see while actually moving;
 *  - a **hi-res tile** per visible page covering the viewport at the current scale,
 *    requested once a gesture settles. It runs at *every* zoom level, not just zoomed in:
 *    it is what makes a page sharp at all, so previews are free to stay small.
 *
 * ★ Preview resolution is a memory decision, not a quality one. Full fit-width previews
 * are ~7 MB each, which left the LRU unable to hold even the visible pages: each scroll
 * evicted a page still on screen, re-requested it, and evicted another — and *that*
 * thrash is what looked like flickering, along with lookahead pages being queued ahead of
 * visible ones on a strictly FIFO render thread.
 *
 * ★ Unlike images, zoom quality here is free: PDF is vector, so a tile re-rendered at the
 * current scale is genuinely sharp — there is no source-pixel ceiling to work around. What
 * is *not* free is render time (tens of ms per page, all of it serialised by [PdfDoc]),
 * which is why tiles wait for [SETTLE_MS] instead of chasing every frame of a pinch.
 */
class PdfStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Single tap — the activity uses it to toggle its chrome. */
    var onTap: (() -> Unit)? = null

    /**
     * The viewport moved — scrolled, flung or zoomed.
     *
     * ★ Fired on movement rather than on page changes, because the page counter has to appear
     * while scrolling *within* a page too; a page-changed callback stays silent through the
     * whole of a long page. Callers read [currentPage] / [pageCount] themselves.
     */
    var onViewport: (() -> Unit)? = null

    private var doc: PdfDoc? = null

    /** Cumulative page tops in points, `tops[n]` = full document height (no trailing gap). */
    private var tops = FloatArray(0)
    private var docW = 1f
    private var docH = 1f

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var mode = MODE_FIT
    private var curPage = 0

    /** Per-page content boxes; a stored null means "scanned, nothing usable". Main thread only. */
    private val boxCache = HashMap<Int, FloatArray?>()

    /**
     * The page the active crop was measured from.
     *
     * ★ The crop is pinned to that page and **not** recomputed as you scroll. Margins differ
     * from page to page, so re-measuring per visible page would move the zoom under the reader
     * mid-scroll; measuring on the double-tap and holding it keeps the gesture the only thing
     * that ever changes zoom.
     */
    private var cropPage = -1

    private var boxPending = -1

    /** One page per screen: narrows the scroll range to [curPage] and snaps on settle. */
    var pageMode = false
        set(value) {
            if (field == value) return
            field = value
            // Keep the reading position across the switch: whichever page the viewport is on
            // stays the page you are on. Without this every toggle throws away your place.
            val keep = curPage
            applyMode()
            goToPage(keep, animate = false)
        }

    private val previews: LruCache<Int, Bitmap> = object : LruCache<Int, Bitmap>(
        // Sized in KB like [ImageViewerActivity]'s: a fit-width page is several MB, so a
        // count-based cache would either hold nothing useful or OOM on a large screen.
        ((Runtime.getRuntime().maxMemory() / 8) / 1024).toInt().coerceAtLeast(12 * 1024),
    ) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
    }

    /** Pages whose preview render is queued; main-thread only. */
    private val inFlight = HashSet<Int>()

    /** Page range still worth rendering, read from the render thread to drop requests a fling has outrun. */
    @Volatile private var wantFirst = 0

    @Volatile private var wantLast = 0

    /** Scale previews are rendered at (fit-width, capped by [PREVIEW_MAX_W]). */
    private var previewScale = 1f

    /** Bumped whenever [previewScale] changes; in-flight renders tagged with an older value are discarded. */
    private var previewGen = 0

    private class Tile(val bmp: Bitmap, val scale: Float, val left: Float, val top: Float)

    private val hires = HashMap<Int, Tile>()
    private var hiresGen = 0

    private val paint = Paint().apply { isFilterBitmap = true }
    private val pagePaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val hitPaint = Paint().apply { color = context.getColor(R.color.pdf_hit) }
    private val hitCurrentPaint = Paint().apply { color = context.getColor(R.color.pdf_hit_current) }

    /** Search hits in document order, so ▲▼ walk the document rather than the order pages happened to be searched in. */
    private val matches = ArrayList<Match>()

    /** The same hits keyed by page, so drawing does not scan the whole result set per frame. */
    private val matchesByPage = HashMap<Int, MutableList<Match>>()

    private var curMatch = -1

    // ---- Selection state (all page-point coordinates on [selPage]) ----
    private val selPaint = Paint().apply { color = context.getColor(R.color.pdf_selection) }
    private val handlePaint = Paint().apply {
        color = context.getColor(R.color.pdf_handle)
        isAntiAlias = true
    }
    private var selPage = -1
    private var selRects: List<RectF> = emptyList()
    private var selText = ""
    private val selStart = PointF()
    private val selStop = PointF()

    /** Which handle the finger is dragging: 0 none, 1 start, 2 stop. Non-zero suppresses scrolling. */
    private var dragHandle = 0

    /** A selection query is in flight; [selDirty] means the boundary moved again while it ran. */
    private var selBusy = false
    private var selDirty = false

    private val scroller = OverScroller(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val srcRect = Rect()
    private val dstRect = RectF()

    // Axis lock: 0 undecided, 1 vertical only, 2 horizontal only.
    private var lockAxis = 0
    private var accX = 0f
    private var accY = 0f

    private val tileReq = Runnable { requestHiRes() }

    private val actualScale get() = resources.displayMetrics.densityDpi / 72f

    private val fitWidthScale get() = if (docW <= 0f) 1f else width / docW

    private val fitPageScale get() =
        if (docW <= 0f) 1f else minOf(width / docW, height / pageHeight(curPage))

    /** Fit means "whole page" when reading page-by-page and "full width" when scrolling continuously. */
    private val fitScale get() = if (pageMode) fitPageScale else fitWidthScale

    private val cropScale get(): Float {
        val box = boxCache[cropPage] ?: return fitWidthScale
        val w = (box[1] - box[0]) * docW + 2 * CROP_PAD * docW
        return if (w <= 0f) fitWidthScale else width / w
    }

    private val minScale get() = minOf(fitPageScale, fitWidthScale) * 0.5f
    private val maxScale get() = maxOf(actualScale, fitWidthScale) * 6f

    fun setDocument(d: PdfDoc) {
        doc = d
        curPage = 0
        rebuildLayout(preserve = false)
        if (width > 0) {
            recomputePreviewScale()
            applyMode()
        }
        onViewport?.invoke()
    }

    /** Real page sizes arrived (or were corrected) — relay the strip without losing the reading position. */
    fun onSizesChanged() {
        rebuildLayout(preserve = true)
        invalidate()
    }

    // ---- Scrollbar geometry (drives the shared FastScrollBar) ----

    fun scrollable(): Boolean = docH * scale > height + 1f

    /** Where the viewport sits in the document, 0..1. */
    fun scrollFraction(): Float {
        if (docH <= 0f || scale <= 0f) return 0f
        val range = docH - height / scale
        if (range <= 0f) return 0f
        return ((-ty / scale) / range).coerceIn(0f, 1f)
    }

    /**
     * Jump to a 0..1 position in the document.
     *
     * ★ In [pageMode] this cannot just move `ty`: the vertical range is clamped to the current
     * page, so a drag would fight the clamp and go nowhere. There, moving means changing page.
     */
    fun scrollToFraction(f: Float) {
        val d = doc ?: return
        if (docH <= 0f || tops.isEmpty() || scale <= 0f) return
        val range = (docH - height / scale).coerceAtLeast(0f)
        val docY = range * f.coerceIn(0f, 1f)
        if (pageMode) {
            val i = tops.binarySearch(docY).let { if (it < 0) -it - 2 else it }
            goToPage(i.coerceIn(0, d.pageCount - 1), animate = false)
        } else {
            scroller.forceFinished(true)
            ty = -docY * scale
            clampAndApply()
        }
    }

    // ---- Selection ----

    /** Selected text, or "" when the selection was cleared. Drives the activity's copy affordance. */
    var onSelection: ((String) -> Unit)? = null

    fun hasSelection(): Boolean = selPage >= 0 && selRects.isNotEmpty()

    fun selectedText(): String = selText

    fun clearSelection() {
        if (selPage < 0) return
        selPage = -1
        selRects = emptyList()
        selText = ""
        dragHandle = 0
        invalidate()
        onSelection?.invoke("")
    }

    /** Screen-space bounds of the selection, for positioning the floating copy bar. */
    fun selectionBounds(out: Rect) {
        out.setEmpty()
        if (selPage < 0 || selRects.isEmpty()) return
        val l = pageLeft(selPage) * scale + tx
        val t = tops[selPage] * scale + ty
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (rc in selRects) {
            left = minOf(left, l + rc.left * scale)
            top = minOf(top, t + rc.top * scale)
            right = maxOf(right, l + rc.right * scale)
            bottom = maxOf(bottom, t + rc.bottom * scale)
        }
        out.set(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /** Which page a screen point lands on, plus that point in the page's own points; null off-page. */
    private fun pageAt(x: Float, y: Float): Pair<Int, PointF>? {
        val d = doc ?: return null
        if (tops.isEmpty()) return null
        val docY = (y - ty) / scale
        var i = tops.binarySearch(docY).let { if (it < 0) -it - 2 else it }
        i = i.coerceIn(0, d.pageCount - 1)
        val px = (x - tx) / scale - pageLeft(i)
        val py = docY - tops[i]
        if (px < 0f || py < 0f || px > pageWidth(i) || py > pageHeight(i)) return null
        return i to PointF(px, py)
    }

    /** Screen position of a selection handle: 1 = start (bottom-left of the first rect), 2 = stop (bottom-right of the last). */
    private fun handlePos(which: Int): PointF? {
        if (selPage < 0 || selRects.isEmpty()) return null
        val l = pageLeft(selPage) * scale + tx
        val t = tops[selPage] * scale + ty
        val rc = if (which == 1) selRects.first() else selRects.last()
        val x = if (which == 1) rc.left else rc.right
        return PointF(l + x * scale, t + rc.bottom * scale)
    }

    private fun handleAt(x: Float, y: Float): Int {
        val touch = HANDLE_TOUCH_DP * resources.displayMetrics.density
        for (which in intArrayOf(1, 2)) {
            val p = handlePos(which) ?: continue
            if (abs(p.x - x) <= touch && abs(p.y - y) <= touch) return which
        }
        return 0
    }

    private fun moveBoundary(which: Int, x: Float, y: Float) {
        if (selPage < 0) return
        // Clamp into the selection's own page: selectContent cannot span sheets, and letting the
        // boundary wander onto the next page would silently produce an empty result.
        val px = ((x - tx) / scale - pageLeft(selPage)).coerceIn(0f, pageWidth(selPage))
        val py = ((y - ty) / scale - tops[selPage]).coerceIn(0f, pageHeight(selPage))
        if (which == 1) selStart.set(px, py) else selStop.set(px, py)
        requestSelection()
    }

    /**
     * Ask for the current boundary pair's selection.
     *
     * ★ At most one query in flight, with the latest boundary winning. Dragging a handle produces
     * a move event per frame, and each query is a page open on the one render thread — firing
     * them all would queue dozens deep and the highlight would trail the finger by seconds.
     */
    private fun requestSelection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val d = doc ?: return
        if (selPage < 0) return
        if (selBusy) {
            selDirty = true
            return
        }
        selBusy = true
        selDirty = false
        val page = selPage
        d.selectText(
            page,
            selStart.x.toInt(), selStart.y.toInt(),
            selStop.x.toInt(), selStop.y.toInt(),
        ) { text, rects ->
            post {
                selBusy = false
                if (selPage != page) return@post
                // Keep the previous selection when a drag strays into a margin and comes back
                // empty — snapping to nothing under the finger reads as the selection breaking.
                if (rects.isNotEmpty()) {
                    selText = text
                    selRects = rects
                    invalidate()
                    onSelection?.invoke(text)
                }
                if (selDirty) requestSelection()
            }
        }
    }

    // ---- Search ----

    /** One hit: the page it is on, plus the rectangles (page points) it covers — a hit can wrap lines. */
    class Match(val page: Int, val rects: List<RectF>)

    fun clearMatches() {
        if (matches.isEmpty()) return
        matches.clear()
        matchesByPage.clear()
        curMatch = -1
        invalidate()
    }

    /**
     * Add one page's hits. Returns the index the first of them landed at, so the caller can jump
     * to it — results arrive in search order, but the list is kept in document order.
     */
    fun addMatches(page: Int, hits: List<List<RectF>>): Int {
        var firstIndex = -1
        val onPage = matchesByPage.getOrPut(page) { ArrayList() }
        for (rects in hits) {
            if (rects.isEmpty()) continue
            val m = Match(page, rects)
            onPage.add(m)
            var lo = 0
            var hi = matches.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val o = matches[mid]
                val before = if (o.page != m.page) o.page < m.page else o.rects[0].top < m.rects[0].top
                if (before) lo = mid + 1 else hi = mid
            }
            matches.add(lo, m)
            // Keep the highlighted hit pointing at the same match as earlier pages fill in above it.
            if (curMatch >= lo) curMatch++
            if (firstIndex < 0 || lo < firstIndex) firstIndex = lo
        }
        if (firstIndex >= 0) invalidate()
        return firstIndex
    }

    fun matchCount(): Int = matches.size

    fun currentMatch(): Int = curMatch

    /** Scroll the hit at [index] into view and make it the highlighted one. */
    fun goToMatch(index: Int) {
        if (index < 0 || index >= matches.size || tops.isEmpty()) return
        curMatch = index
        val m = matches[index]
        val r = m.rects[0]
        curPage = m.page
        // Park the hit a third of the way down rather than at the very top: a match sitting on
        // the top edge gives no sense of the sentence it is in.
        ty = -(tops[m.page] + r.top) * scale + height / 3f
        // When zoomed in past the viewport, a hit can be off to one side; bring it in too.
        val left = (pageLeft(m.page) + r.left) * scale + tx
        val right = (pageLeft(m.page) + r.right) * scale + tx
        if (left < 0 || right > width) {
            tx = -(pageLeft(m.page) + r.left) * scale + width / 4f
        }
        clampAndApply()
    }

    /** Measure one page's margins, then re-apply if that is the page the current crop is pinned to. */
    private fun requestBox(page: Int) {
        val d = doc ?: return
        if (boxPending == page || boxCache.containsKey(page)) return
        boxPending = page
        d.contentBoxOf(page) { box ->
            post {
                if (boxPending == page) boxPending = -1
                boxCache[page] = box
                if (mode == MODE_CROP && cropPage == page) applyMode()
            }
        }
    }

    fun pageCount(): Int = doc?.pageCount ?: 0

    fun currentPage(): Int = curPage

    private fun pageHeight(i: Int): Float = doc?.pageHeight(i) ?: 1f

    private fun pageWidth(i: Int): Float = doc?.pageWidth(i) ?: 1f

    private fun rebuildLayout(preserve: Boolean) {
        val d = doc ?: return
        // Anchor on where we are *within* the current page, not on an absolute offset: the
        // whole point of re-laying-out is that page offsets are about to move.
        val anchorPage = curPage
        val anchorOff = if (preserve && tops.isNotEmpty()) (-ty / scale) - tops[anchorPage] else 0f

        docW = d.maxWidth()
        tops = FloatArray(d.pageCount + 1)
        var y = 0f
        for (i in 0 until d.pageCount) {
            tops[i] = y
            y += d.pageHeight(i)
            if (i < d.pageCount - 1) y += GAP_PT
        }
        tops[d.pageCount] = y
        docH = y

        if (preserve) {
            ty = -(tops[anchorPage] + anchorOff) * scale
            clampAndApply()
        }
    }

    private fun recomputePreviewScale() {
        doc ?: return
        // ★ A preview is the placeholder you see *while moving*, not the final image — the tile
        // layer is what makes a page sharp once it settles. Rendering previews at full fit-width
        // made each one ~7 MB, so the LRU held barely the pages already on screen: every scroll
        // evicted a page that was still visible, which re-requested it, which evicted another.
        // That thrash was the flicker. A soft page for the duration of a fling is the right
        // trade; a page that keeps blanking out is not.
        var s = width / docW
        if (docW * s > PREVIEW_MAX_W) s = PREVIEW_MAX_W / docW
        if (abs(s - previewScale) > 0.01f) {
            previewScale = s
            previewGen++
            previews.evictAll()
            inFlight.clear()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (doc == null) return
        recomputePreviewScale()
        applyMode()
    }

    // ---- Zoom modes ----

    /**
     * Double-tap toggles exactly two states: margins cropped away, or the whole page on screen.
     *
     * ★ There is no 1:1 stage. Double-tap is the "show me this the way I want to read it"
     * gesture, and both useful answers are framings of the page — actual size is a zoom, which
     * is what the pinch is for.
     *
     * ★ CROP stays available even on a page with no detectable margins (where it simply equals
     * fit-width). Dropping it there would make the same gesture do different things depending on
     * where in the document you happened to be.
     */
    private fun nextMode(): Int = if (mode == MODE_CROP) MODE_PAGE else MODE_CROP

    private fun applyMode() {
        if (doc == null || width == 0) return
        val old = scale
        scale = when (mode) {
            MODE_CROP -> cropScale
            MODE_PAGE -> fitPageScale
            else -> fitScale
        }.coerceIn(minScale, maxScale)

        if (mode == MODE_CROP) {
            // Pin the trimmed content's left edge to the viewport instead of centring the whole
            // page — the point of cropping is that the margins leave the screen.
            val box = boxCache[cropPage]
            tx = if (box == null) (width - docW * scale) / 2f
            else -((box[0] - CROP_PAD) * docW) * scale
        } else {
            tx = (width - docW * scale) / 2f
        }
        // Keep the current page anchored under the same viewport top through a zoom change.
        if (old > 0f && tops.isNotEmpty()) {
            val docY = -ty / old
            ty = -docY * scale
        }
        clampAndApply()
    }

    // ---- Scroll geometry ----

    private fun contentH() = docH * scale

    private fun contentW() = docW * scale

    /** Vertical translation range, `[min, max]`. In [pageMode] it narrows to the current page. */
    private fun rangeY(): FloatArray {
        if (!pageMode) {
            val ch = contentH()
            return if (ch <= height) floatArrayOf((height - ch) / 2f, (height - ch) / 2f)
            else floatArrayOf(height - ch, 0f)
        }
        val top = tops[curPage] * scale
        val bot = top + pageHeight(curPage) * scale
        val ph = bot - top
        return if (ph <= height) {
            val v = -top + (height - ph) / 2f
            floatArrayOf(v, v)
        } else floatArrayOf(height - bot, -top)
    }

    private fun rangeX(): FloatArray {
        val cw = contentW()
        return if (cw <= width) floatArrayOf((width - cw) / 2f, (width - cw) / 2f)
        else floatArrayOf(width - cw, 0f)
    }

    private fun clampAndApply() {
        val rx = rangeX()
        val ry = rangeY()
        tx = tx.coerceIn(rx[0], rx[1])
        ty = ty.coerceIn(ry[0], ry[1])
        updateCurrentPage()
        scheduleHiRes()
        invalidate()
        onViewport?.invoke()
    }

    private fun updateCurrentPage() {
        if (pageMode || tops.isEmpty()) return
        // The page under the viewport's middle is the one you would say you are reading.
        val mid = (-ty + height / 2f) / scale
        var i = tops.binarySearch(mid).let { if (it < 0) -it - 2 else it }
        i = i.coerceIn(0, (doc?.pageCount ?: 1) - 1)
        curPage = i
    }

    fun goToPage(index: Int, animate: Boolean = true) {
        val d = doc ?: return
        val i = index.coerceIn(0, d.pageCount - 1)
        curPage = i
        val ry = rangeY()
        // Top-align the page; rangeY has already centred it when it is shorter than the viewport.
        val target = (-tops[i] * scale).coerceIn(ry[0], ry[1])
        if (animate) {
            // ★ Start from the real tx, not 0: computeScroll writes both axes back, so a snap
            // launched from x=0 would yank a zoomed-in page back to the left edge.
            scroller.startScroll((-tx).roundToInt(), (-ty).roundToInt(), 0, (-target - (-ty)).roundToInt(), SNAP_MS)
            postInvalidateOnAnimation()
        } else {
            ty = target
            clampAndApply()
        }
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        tx = -scroller.currX.toFloat()
        ty = -scroller.currY.toFloat()
        clampAndApply()
        postInvalidateOnAnimation()
    }

    // ---- Gestures ----

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // A tap dismisses a selection rather than toggling chrome — matching every text
            // surface on the platform, and it is the only way out that needs no aiming.
            if (hasSelection()) clearSelection() else onTap?.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!PdfDoc.TEXT_SUPPORTED) return
            val (page, pt) = pageAt(e.x, e.y) ?: return
            selPage = page
            // Same point twice: the platform widens a degenerate pair to the word underneath.
            selStart.set(pt.x, pt.y)
            selStop.set(pt.x, pt.y)
            // Treat the finger as already dragging the far handle, so long-press-then-drag
            // extends the selection instead of scrolling the page out from under it.
            dragHandle = 2
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            requestSelection()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            mode = nextMode()
            if (mode == MODE_CROP) {
                // Crop the page being read, measured now. Usually already cached by the settle-time
                // prefetch; if not, this falls back to fit-width and snaps in when the scan lands.
                cropPage = curPage
                requestBox(curPage)
            }
            applyMode()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // Axis lock, the same feel the text viewer gets for free from a ScrollView nested in
            // a HorizontalScrollView: whichever direction the gesture commits to, it keeps.
            // ★ Decided per gesture rather than disabling horizontal outright — once zoomed past
            // the viewport the user still has to be able to pan across to reach the text.
            if (lockAxis == 0) {
                accX += abs(dx)
                accY += abs(dy)
                if (maxOf(accX, accY) > slop) lockAxis = if (accY >= accX) 1 else 2
            }
            when (lockAxis) {
                1 -> ty -= dy
                2 -> tx -= dx
                else -> return true
            }
            if (pageMode && lockAxis == 1) crossPageIfPastEdge(dy)
            clampAndApply()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (lockAxis == 2) {
                val rx = rangeX()
                scroller.fling(
                    (-tx).roundToInt(), (-ty).roundToInt(), (-vx).roundToInt(), 0,
                    (-rx[1]).roundToInt(), (-rx[0]).roundToInt(), (-ty).roundToInt(), (-ty).roundToInt(),
                )
                postInvalidateOnAnimation()
                return true
            }
            if (pageMode) {
                // One flick = one page, the way page-at-a-time readers behave — but only once the
                // page itself is scrolled to its edge, otherwise a cropped (taller than screen)
                // page could never be read to the bottom.
                val ry = rangeY()
                if (vy < 0 && ty <= ry[0] + 1f) { goToPage(curPage + 1); return true }
                if (vy > 0 && ty >= ry[1] - 1f) { goToPage(curPage - 1); return true }
            }
            val ry = rangeY()
            scroller.fling(
                (-tx).roundToInt(), (-ty).roundToInt(), 0, (-vy).roundToInt(),
                (-tx).roundToInt(), (-tx).roundToInt(), (-ry[1]).roundToInt(), (-ry[0]).roundToInt(),
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    /** In page mode a drag that runs off the current page's edge steps to the neighbour. */
    private fun crossPageIfPastEdge(dy: Float) {
        val ry = rangeY()
        if (dy > 0 && ty < ry[0] - CROSS_PT && curPage < (doc?.pageCount ?: 1) - 1) {
            goToPage(curPage + 1, animate = false)
        } else if (dy < 0 && ty > ry[1] + CROSS_PT && curPage > 0) {
            goToPage(curPage - 1, animate = false)
            // Land on the previous page's bottom, so dragging back up reads continuously.
            ty = rangeY()[0]
        }
    }

    private val scaleGesture = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(minScale, maxScale)
            tx = d.focusX - (d.focusX - tx) * (ns / scale)
            ty = d.focusY - (d.focusY - ty) * (ns / scale)
            scale = ns
            mode = MODE_FREE
            clampAndApply()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lockAxis = 0
            accX = 0f
            accY = 0f
            parent?.requestDisallowInterceptTouchEvent(true)
            // Grabbing a handle must be decided before anything else looks at the gesture,
            // otherwise the same drag both moves the boundary and scrolls the page.
            if (hasSelection()) dragHandle = handleAt(event.x, event.y)
        }
        if (dragHandle != 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> moveBoundary(dragHandle, event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragHandle = 0
            }
            return true
        }
        scaleGesture.onTouchEvent(event)
        if (!scaleGesture.isInProgress) gesture.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (pageMode && scroller.isFinished) snapToPage()
        }
        return true
    }

    /** Settle onto a page edge in page mode; a page taller than the viewport is left where it is. */
    private fun snapToPage() {
        val ry = rangeY()
        if (ry[0] == ry[1]) return
        val top = -tops[curPage] * scale
        if (abs(ty - top) < height * 0.25f) {
            scroller.startScroll((-tx).roundToInt(), (-ty).roundToInt(), 0, (-top - (-ty)).roundToInt(), SNAP_MS)
            postInvalidateOnAnimation()
        }
    }

    // ---- Rendering ----

    private fun visibleRange(): IntRange {
        val d = doc ?: return IntRange.EMPTY
        if (tops.isEmpty()) return IntRange.EMPTY
        val topPt = -ty / scale
        val botPt = (-ty + height) / scale
        var first = tops.binarySearch(topPt).let { if (it < 0) -it - 2 else it }
        var last = tops.binarySearch(botPt).let { if (it < 0) -it - 2 else it }
        first = first.coerceIn(0, d.pageCount - 1)
        last = last.coerceIn(0, d.pageCount - 1)
        return first..last
    }

    private fun pageLeft(i: Int) = (docW - pageWidth(i)) / 2f

    override fun onDraw(canvas: Canvas) {
        val d = doc ?: return
        if (tops.isEmpty()) return
        val range = visibleRange()
        for (i in range) {
            val l = pageLeft(i) * scale + tx
            val t = tops[i] * scale + ty
            val r = l + pageWidth(i) * scale
            val b = t + pageHeight(i) * scale
            dstRect.set(l, t, r, b)
            // Paper first: a page whose bitmap has not arrived still reads as a blank sheet
            // rather than a hole in the strip.
            canvas.drawRect(dstRect, pagePaint)

            previews.get(i)?.let { bmp ->
                srcRect.set(0, 0, bmp.width, bmp.height)
                canvas.drawBitmap(bmp, srcRect, dstRect, paint)
            }
            hires[i]?.let { tile ->
                // Drop a tile that no longer matches the zoom rather than stretching it — a stale
                // tile drawn over a fresh preview looks like a rendering bug.
                if (tile.scale / scale in 0.75f..1.35f) {
                    val tl = l + tile.left * (scale / tile.scale)
                    val tt = t + tile.top * (scale / tile.scale)
                    dstRect.set(
                        tl, tt,
                        tl + tile.bmp.width * (scale / tile.scale),
                        tt + tile.bmp.height * (scale / tile.scale),
                    )
                    srcRect.set(0, 0, tile.bmp.width, tile.bmp.height)
                    canvas.drawBitmap(tile.bmp, srcRect, dstRect, paint)
                }
            }
            // Hits go on top of both bitmap layers — they are translucent, so they read as
            // highlighter over whatever the page turned out to be.
            if (selPage == i) {
                for (rc in selRects) {
                    dstRect.set(
                        l + rc.left * scale, t + rc.top * scale,
                        l + rc.right * scale, t + rc.bottom * scale,
                    )
                    canvas.drawRect(dstRect, selPaint)
                }
                val r = HANDLE_DP * resources.displayMetrics.density
                for (which in intArrayOf(1, 2)) {
                    val hp = handlePos(which) ?: continue
                    canvas.drawCircle(hp.x, hp.y + r, r, handlePaint)
                }
            }
            matchesByPage[i]?.forEach { m ->
                val p = if (curMatch >= 0 && matches[curMatch] === m) hitCurrentPaint else hitPaint
                for (rc in m.rects) {
                    dstRect.set(
                        l + rc.left * scale, t + rc.top * scale,
                        l + rc.right * scale, t + rc.bottom * scale,
                    )
                    canvas.drawRect(dstRect, p)
                }
            }
        }
        requestPreviews(range, d)
    }

    private fun requestPreviews(range: IntRange, d: PdfDoc) {
        wantFirst = (range.first - 1).coerceAtLeast(0)
        wantLast = (range.last + 1).coerceAtMost(d.pageCount - 1)

        // ★ Visible pages first, lookahead only after them. The render thread is strictly FIFO,
        // so walking the range from `first - 1` queued the **off-screen** page above the viewport
        // ahead of the page being looked at — the visible page then waited a whole render behind
        // something nobody could see, which is most of what read as flicker while scrolling.
        val order = ArrayList<Int>(range.count() + 2)
        for (i in range) order.add(i)
        (range.last + 1).takeIf { it < d.pageCount }?.let { order.add(it) }
        (range.first - 1).takeIf { it >= 0 }?.let { order.add(it) }

        for (i in order) {
            if (previews.get(i) != null || !inFlight.add(i)) continue
            val gen = previewGen
            val s = previewScale
            val w = (d.pageWidth(i) * s).roundToInt().coerceIn(1, 4096)
            val h = (d.pageHeight(i) * s).roundToInt().coerceIn(1, 8192)
            d.renderTile(i, s, 0f, 0f, w, h, wanted = { i in wantFirst..wantLast }) { bmp ->
                post {
                    inFlight.remove(i)
                    if (bmp == null) return@post
                    if (gen != previewGen) { bmp.recycle(); return@post }
                    previews.put(i, bmp)
                    invalidate()
                }
            }
        }
    }

    /**
     * ★ Always scheduled, at every zoom level — previews are deliberately low-res now, so the
     * tile layer is the only thing that makes a page sharp, not just a zoom-in refinement.
     * Memory stays bounded because a tile is clipped to the viewport ∩ page and [requestHiRes]
     * keeps tiles only for visible pages: the total is roughly one screenful regardless of
     * document length.
     */
    private fun scheduleHiRes() {
        removeCallbacks(tileReq)
        if (doc == null) return
        postDelayed(tileReq, SETTLE_MS)
    }

    /**
     * Ask for one viewport-sized tile per visible page at the current scale.
     *
     * ★ Per page, not one tile for the viewport: PdfRenderer draws a single page at a time, so
     * a tile spanning a page boundary cannot exist.
     */
    private fun requestHiRes() {
        val d = doc ?: return
        val gen = ++hiresGen
        val keep = HashSet<Int>()
        for (i in visibleRange()) {
            keep.add(i)

            // Viewport ∩ page, in the page's own pixel space at the current scale.
            val pageX = pageLeft(i) * scale + tx
            val pageY = tops[i] * scale + ty
            val left = ((-pageX) - width * TILE_PAD).coerceAtLeast(0f)
            val top = ((-pageY) - height * TILE_PAD).coerceAtLeast(0f)
            val right = ((-pageX) + width * (1 + TILE_PAD)).coerceAtMost(pageWidth(i) * scale)
            val bottom = ((-pageY) + height * (1 + TILE_PAD)).coerceAtMost(pageHeight(i) * scale)
            var w = (right - left).roundToInt()
            var h = (bottom - top).roundToInt()
            if (w < 1 || h < 1) continue

            // ★ Matching scale is NOT enough to reuse a tile — it must still *cover* what is on
            // screen. Skipping on scale alone meant that after scrolling down within one page,
            // the tile rendered for the part already read was kept, and everything newly exposed
            // below it stayed on the blurry preview indefinitely. That is the "the lower part of
            // the page keeps flashing" report: a sharp top against a soft bottom, with the seam
            // jumping every time a tile was replaced.
            val existing = hires[i]
            if (existing != null && existing.scale / scale in 0.9f..1.1f) {
                val f = scale / existing.scale
                val cl = existing.left * f
                val ct = existing.top * f
                if (cl <= left + 0.5f && ct <= top + 0.5f &&
                    cl + existing.bmp.width * f >= right - 0.5f &&
                    ct + existing.bmp.height * f >= bottom - 0.5f
                ) continue
            }
            w = w.coerceAtMost(4096)
            h = h.coerceAtMost(4096)

            val s = scale
            d.renderTile(i, s, left, top, w, h) { bmp ->
                post {
                    if (bmp == null) return@post
                    if (gen != hiresGen) { bmp.recycle(); return@post }
                    hires[i] = Tile(bmp, s, left, top)
                    invalidate()
                }
            }
        }
        // Tiles for pages that have scrolled away are pure memory; the preview covers them.
        if (hires.keys.retainAll(keep)) invalidate()

        // Measure the current page's margins while things are still, so a double-tap can crop
        // immediately instead of showing fit-width first and jumping a moment later.
        requestBox(curPage)
    }

    fun release() {
        removeCallbacks(tileReq)
        previews.evictAll()
        hires.clear()
        boxCache.clear()
        matches.clear()
        matchesByPage.clear()
        doc?.close()
        doc = null
    }

    companion object {
        /** Opening state: full width (or the whole page when reading page-at-a-time). */
        const val MODE_FIT = 0

        /** Margins trimmed away, content filling the width. */
        const val MODE_CROP = 1

        /** The whole page on screen. */
        const val MODE_PAGE = 2

        /** Reached by pinching: no mode owns the scale, so the next double-tap goes to [MODE_CROP]. */
        const val MODE_FREE = 3

        /** Gap between pages, in points — scaling with the page keeps the strip looking like paper at any zoom. */
        private const val GAP_PT = 10f

        /** Breathing room left around cropped content, as a fraction of page width; glyphs flush to the screen edge look clipped. */
        private const val CROP_PAD = 0.015f

        /** How far past a page edge a drag must run before it steps to the neighbouring page (px). */
        private const val CROSS_PT = 48f

        private const val SNAP_MS = 220

        /** Wait for the gesture to settle before rendering tiles — a pinch would otherwise queue dozens of renders on the one render thread. */
        private const val SETTLE_MS = 150L

        /** Extra area a tile covers beyond the viewport, so a small pan does not need a re-render. */
        private const val TILE_PAD = 0.12f

        /**
         * Width ceiling in px for a cached page preview. Kept well under the viewport on purpose:
         * previews only have to survive a fling, the tile layer supplies sharpness at rest, and
         * a small preview is both far quicker to render and cheap enough that the LRU holds a
         * useful number of pages instead of thrashing.
         */
        private const val PREVIEW_MAX_W = 560f

        /** Drawn radius of a selection handle. */
        private const val HANDLE_DP = 7f

        /** Grab radius around a handle — deliberately far larger than the drawn one, since the finger covers it. */
        private const val HANDLE_TOUCH_DP = 24f
    }
}
