package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Zoomable image view (modelled on SambaGallery interactions, self-drawn without
 * a third-party library):
 * - Double-tap cycles between FIT (centred, no cropping) / CROP (one direction
 *   cropped) / ACTUAL (1:1, draggable to see local details).
 * - Drag pans (when the image exceeds the view); pinch for free zoom.
 * - Single-tap callback ([onTap]); when not zoomed in, a horizontal quick swipe
 *   triggers page-turn ([onPage]).
 * - When zoomed in enough that the base bitmap gets interpolated ([scale] > 1),
 *   requests a hi-res region from outside ([onNeedHiRes]) and overlays it on
 *   top of the base bitmap — the base bitmap is decoded down-sampled to save
 *   memory, and zoom-in details come from this layer.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    var onTap: (() -> Unit)? = null

    /** Long-press callback (pops up an action menu). */
    var onLongPress: (() -> Unit)? = null

    /** Page-turn callback: dir=-1 previous, +1 next. */
    var onPage: ((Int) -> Unit)? = null

    /**
     * Need a hi-res region: parameter is the visible area (with padding) as a rect
     * in **the current displayed bitmap**'s coordinate system, plus the current
     * zoom factor (used to determine the region's own down-sampling). Once decoded
     * asynchronously, calls back via [setHiRes].
     */
    var onNeedHiRes: ((RectF, Float) -> Unit)? = null

    /**
     * Viewport changed (zoom / pan / mode switch). The image-compare page uses it
     * to sync the other side; changes caused by [applyViewport] do **not** callback,
     * otherwise both sides would feed each other endlessly.
     */
    var onViewport: (() -> Unit)? = null
    private var syncing = false

    private val m = Matrix()
    private var imgW = 0f
    private var imgH = 0f
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var mode = 0 // 0 FIT, 1 CROP, 2 ACTUAL
    /** Bitmap → original image pixels ratio (decoded down-sampling ratio); the actual-size mode scales up by this to render the image at its true size. */
    private var actualScale = 1f

    private var hiRes: Bitmap? = null
    private var hiResRect: RectF? = null // in displayed bitmap coordinates, corresponds 1:1 with hiRes
    private var hiResScale = 0f          // the scale when this region was requested, used to decide whether to re-decode at the new factor
    private val hiResPaint = Paint().apply { isFilterBitmap = true }
    private val hiResReq = Runnable { requestHiRes() }

    private val fitScale get() = if (imgW == 0f) 1f else minOf(width / imgW, height / imgH)
    private val cropScale get() = if (imgW == 0f) 1f else maxOf(width / imgW, height / imgH)
    private val maxScale get() = maxOf(cropScale, actualScale) * 2f

    /**
     * Zoom lower bound. ★ Cannot unconditionally use [fitScale]: under [MODE_FIT_ACTUAL]
     * an image smaller than the container should display at 1:1, and that scale is
     * smaller than fitScale — capping the lower bound at fitScale would snap it back
     * to fill as soon as it's synced / pinched.
     */
    private val minScale get() =
        if (initialMode == MODE_FIT_ACTUAL) minOf(fitScale, actualScale) else fitScale

    /** Which mode to use after changing the image, and where the double-tap cycle starts. Defaults to [MODE_FIT]. */
    var initialMode = MODE_FIT

    /** The double-tap cycle's stages. CROP is meaningless in compare scenarios, so it switches to cycling between "actual / fit". */
    private val modeCycle get() =
        if (initialMode == MODE_FIT_ACTUAL) intArrayOf(MODE_FIT_ACTUAL, MODE_FIT, MODE_ACTUAL)
        else intArrayOf(MODE_FIT, MODE_CROP, MODE_ACTUAL)

    init {
        scaleType = ScaleType.MATRIX
    }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val cycle = modeCycle
            val i = cycle.indexOf(mode)
            mode = cycle[(if (i < 0) 0 else i + 1) % cycle.size]
            applyMode()
            notifyViewport()
            return true
        }

        // ★ Same-named member: omitting this@ZoomableImageView would resolve to the listener's own onLongPress
        override fun onLongPress(e: MotionEvent) {
            this@ZoomableImageView.onLongPress?.invoke()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            tx -= dx; ty -= dy; clampAndApply(); notifyViewport()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            // When not zoomed in (no horizontal overflow), a horizontal quick swipe pages through
            if (imgW * scale <= width + 1f && kotlin.math.abs(vx) > kotlin.math.abs(vy) &&
                kotlin.math.abs(vx) > 500f
            ) {
                onPage?.invoke(if (vx > 0) -1 else 1)
                return true
            }
            return false
        }
    })

    private val scaleGesture = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            val ns = (scale * f).coerceIn(minScale, maxScale)
            // Zoom around the pinch focus
            tx = d.focusX - (d.focusX - tx) * (ns / scale)
            ty = d.focusY - (d.focusY - ty) * (ns / scale)
            scale = ns
            clampAndApply()
            notifyViewport()
            return true
        }
    })

    /**
     * Set a new image: reset to [initialMode].
     * @param actual bitmap → original image pixels ratio (decoded down-sampling ratio),
     *   used by the "actual size" mode to render the image at its true size.
     */
    fun setImage(bmp: Bitmap, actual: Float = 1f) {
        clearHiRes()
        setImageBitmap(bmp)
        imgW = bmp.width.toFloat(); imgH = bmp.height.toFloat()
        actualScale = actual
        mode = initialMode
        if (width > 0) applyMode()
    }

    /** Deliver a hi-res region ([rect] must be the rectangle given when [onNeedHiRes] was issued). */
    fun setHiRes(bmp: Bitmap, rect: RectF) {
        hiRes = bmp
        hiResRect = rect
        invalidate()
    }

    private fun clearHiRes() {
        removeCallbacks(hiResReq)
        // Don't recycle: this bitmap may still be referenced by the previous frame's canvas, GC is safer
        hiRes = null
        hiResRect = null
        hiResScale = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = hiRes ?: return
        val r = hiResRect ?: return
        canvas.save()
        canvas.concat(m) // same transform as the base bitmap, hi-res region falls into bitmap coordinates
        canvas.drawBitmap(h, null, r, hiResPaint)
        canvas.restore()
    }

    /** Request a hi-res region after zoom/pan settles; drop it when zoomed back out so the bitmap isn't interpolated, saving memory. */
    private fun scheduleHiRes() {
        removeCallbacks(hiResReq)
        if (actualScale <= 1f || scale <= 1.01f) { // original image has no more pixels, or the bitmap isn't zoomed yet
            if (hiRes != null) { clearHiRes(); invalidate() }
            return
        }
        postDelayed(hiResReq, HIRES_DELAY_MS)
    }

    private fun requestHiRes() {
        if (imgW == 0f) return
        // Reverse-transform the visible area back to bitmap coordinates, then pad it — small pans don't need a re-decode
        val pad = HIRES_PAD
        val vw = width / scale; val vh = height / scale
        val rect = RectF(
            ((-tx / scale) - vw * pad).coerceAtLeast(0f),
            ((-ty / scale) - vh * pad).coerceAtLeast(0f),
            ((-tx + width) / scale + vw * pad).coerceAtMost(imgW),
            ((-ty + height) / scale + vh * pad).coerceAtMost(imgH),
        )
        if (rect.width() < 1f || rect.height() < 1f) return
        val have = hiResRect
        // If the existing region covers the current view and the scale hasn't changed much, don't re-decode
        if (have != null && have.contains(rect) && scale / hiResScale in 0.7f..1.4f) return
        hiResScale = scale
        onNeedHiRes?.invoke(rect, scale)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (imgW > 0) applyMode()
    }

    private fun applyMode() {
        scale = when (mode) {
            MODE_FIT -> fitScale
            MODE_CROP -> cropScale
            // Display at the image's true pixel size, but not exceeding the container — large
            // images scale down just enough to fit, small images stay at 1:1 and aren't enlarged
            MODE_FIT_ACTUAL -> minOf(actualScale, fitScale)
            else -> actualScale // actual size: at the image's true pixel size (draggable to see local details)
        }
        val sw = imgW * scale; val sh = imgH * scale
        tx = (width - sw) / 2f
        ty = (height - sh) / 2f
        clampAndApply()
    }

    private fun clampAndApply() {
        val sw = imgW * scale; val sh = imgH * scale
        tx = if (sw <= width) (width - sw) / 2f else tx.coerceIn(width - sw, 0f)
        ty = if (sh <= height) (height - sh) / 2f else ty.coerceIn(height - sh, 0f)
        m.reset(); m.postScale(scale, scale); m.postTranslate(tx, ty)
        imageMatrix = m
        scheduleHiRes()
    }

    /**
     * Push viewport changes outward. **Only call after user gestures**, never for
     * image swap or layout (applyMode on first sizing) — each side settling at its
     * own actual size is part of the initial state; whichever finishes loading
     * later would push its viewport onto the other side, and then neither would
     * be at actual size.
     */
    private fun notifyViewport() {
        if (!syncing) onViewport?.invoke()
    }

    /**
     * The current viewport's position within the image, **normalised to the image's
     * own dimensions**: [top-left u, top-left v, viewport-covered width ratio].
     * Normalising lets two **differently-sized** images align to the same content —
     * syncing by viewport pixel offsets would misalign immediately when the same
     * photo changes resolution.
     */
    fun viewport(): FloatArray? {
        if (imgW <= 0f || imgH <= 0f || scale <= 0f || width == 0) return null
        return floatArrayOf(-tx / scale / imgW, -ty / scale / imgH, width / (scale * imgW))
    }

    /** Move the viewport to the position described by [viewport]. If the other image is smaller, scale is clamped by the fit lower bound — that's a physical limit. */
    fun applyViewport(v: FloatArray) {
        if (imgW <= 0f || imgH <= 0f || width == 0 || v[2] <= 0f) return
        syncing = true
        scale = (width / (v[2] * imgW)).coerceIn(minScale, maxScale)
        tx = -v[0] * imgW * scale
        ty = -v[1] * imgH * scale
        clampAndApply()
        syncing = false
    }

    /** Left/right edges are left for the system edge-back gesture; touches inside this width are not handled at all, so the page-turn gesture and it don't fight. */
    private val edgeGuardPx get() = 24f * resources.displayMetrics.density

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            (event.x < edgeGuardPx || event.x > width - edgeGuardPx)
        ) {
            return false
        }
        scaleGesture.onTouchEvent(event)
        gesture.onTouchEvent(event)
        // Allow pan after zoom-in, prevent parent interception
        if (imgW * scale > width + 1f) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    companion object {
        private const val HIRES_DELAY_MS = 150L // only decode after the gesture settles, don't fire repeatedly during a pinch
        private const val HIRES_PAD = 0.15f     // visible area padding ratio (padding is quadratic in memory, don't be greedy)

        /** Fit the container (may enlarge small images to fill). */
        const val MODE_FIT = 0
        /** Crop to fill. */
        const val MODE_CROP = 1
        /** The image's true pixel size. */
        const val MODE_ACTUAL = 2
        /** True pixel size, but scale down to just fit when exceeding the container. */
        const val MODE_FIT_ACTUAL = 3
    }
}
