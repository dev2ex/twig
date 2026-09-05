package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A draggable fast scrollbar, used by lists with huge row counts (the hex viewer).
 *
 * Why not use the built-in: RecyclerView's native scrollbar **cannot be dragged**
 * (it is only an indicator), and its `computeVerticalScrollRange()` returns an int —
 * a 1 GB file at 16 bytes/row is 67 million rows; multiplied by the row height it
 * overflows, and the thumb position becomes distorted. Here the thumb height is
 * **fixed** at [THUMB_DP]; the position is driven solely by the 0..1 fraction
 * supplied by the caller, completely independent of total row count, so even
 * enormous files remain draggable.
 *
 * Touch is only taken over when the **press lands on the thumb** ([onTouchEvent]
 * returns false for other hits), so events continue to dispatch to the underlying
 * RecyclerView — this 24dp strip on the right will not eat normal list scrolling.
 *
 * **The bar shows and hides itself.** It starts hidden, appears whenever [fraction]
 * moves, and fades out [HIDE_MS] after things go still — hosts never manage its
 * visibility, they only keep the fraction current. Only the thumb is drawn, no track: a
 * rail permanently down the edge of the screen is the one part of a transient indicator
 * that never earns its place. Set `isEnabled = false` when there is nothing to scroll and
 * it stays away entirely.
 *
 * ★ Hidden means untouchable, so the thumb can only be grabbed while it is up — during or
 * just after scrolling. That is how platform fast-scrollers behave, and it is the point: a
 * permanently grabbable strip along the edge would swallow gestures meant for the content.
 */
class FastScrollBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Drag callback: fraction 0..1, [ended] = true means the finger has lifted. */
    var onDrag: ((Float, Boolean) -> Unit)? = null

    var dragging = false
        private set

    /** Thumb position fraction; maintained internally during a drag, otherwise synced in from list scrolling. */
    var fraction = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v == field) return
            field = v
            poke()
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val thumbH = THUMB_DP * dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val hideRunnable = Runnable { fadeOut() }

    init {
        // Starts hidden and stays hidden until something moves — the bar is feedback about
        // scrolling, so there is nothing to say before any has happened.
        alpha = 0f
        visibility = INVISIBLE
    }

    /**
     * Show the bar and restart its countdown. Called automatically whenever [fraction] changes,
     * so hosts get the behaviour for free; call it directly only to reveal the bar without a
     * position change.
     */
    fun poke() {
        if (!isEnabled) return
        removeCallbacks(hideRunnable)
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        postDelayed(hideRunnable, HIDE_MS)
    }

    private fun fadeOut() {
        // Never disappear from under a finger that is holding the thumb.
        if (dragging) {
            postDelayed(hideRunnable, HIDE_MS)
            return
        }
        animate().alpha(0f).setDuration(FADE_MS)
            .withEndAction { visibility = INVISIBLE }
            .start()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            removeCallbacks(hideRunnable)
            animate().cancel()
            alpha = 0f
            visibility = INVISIBLE
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        if (!isEnabled) return
        // Thumb only, no track: the bar shows itself while you are moving and goes away after,
        // so a permanent rail down the edge would be the one part of it that never earned its
        // place on screen.
        val cx = width / 2f
        val top = thumbTop()
        val w = (if (dragging) 9 else 6) * dp
        paint.color = if (dragging) THUMB_ACTIVE else THUMB_COLOR
        canvas.drawRoundRect(cx - w / 2, top, cx + w / 2, top + thumbH, w / 2, w / 2, paint)
    }

    private fun thumbTop(): Float = (height - thumbH).coerceAtLeast(0f) * fraction

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Don't take over unless the press lands on the thumb — let touches fall through to the list below for normal scrolling
                val top = thumbTop()
                if (event.y < top - SLOP_DP * dp || event.y > top + thumbH + SLOP_DP * dp) return false
                dragging = true
                removeCallbacks(hideRunnable)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) update(event.y, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                update(event.y, true)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                poke()
                invalidate()
            }
            else -> return dragging
        }
        return true
    }

    private fun update(y: Float, ended: Boolean) {
        val range = height - thumbH
        fraction = if (range <= 0f) 0f else (y - thumbH / 2) / range
        onDrag?.invoke(fraction, ended)
    }

    private companion object {
        const val THUMB_DP = 44f
        const val SLOP_DP = 6f // leave a little margin above and below the thumb so even thick fingers hit it
        const val THUMB_COLOR = 0xB0808080.toInt()
        const val THUMB_ACTIVE = 0xFF66BB6A.toInt() // matches @color/accent

        /** Idle time before the bar fades out, matching the PDF viewer's page counter. */
        const val HIDE_MS = 1000L
        const val FADE_MS = 200L
    }
}
