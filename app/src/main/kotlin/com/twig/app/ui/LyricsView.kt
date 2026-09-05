package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/**
 * Self-drawn scrolling lyrics:
 * - One lyric line can contain multiple text lines (bilingual original + translation, newlines split out),
 *   stacked; the current line is highlighted and centred overall, following playback smoothly.
 * - When the current line is too long, marquee-scroll left/right; non-current lines truncate with ellipsis.
 * - Vertical drag scrolls the lyrics; tap a line to seek to it; horizontal swipe callback [onSwipe] switches to
 *   the next / previous track.
 * - The cover ↔ lyrics toggle doesn't live here (handled by the info area below); tap on untimed lyrics
 *   calls [onTap].
 */
class LyricsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onTap: (() -> Unit)? = null
    /** Tap a lyric line with a timestamp → seek to that time (ms). */
    var onSeekTo: ((Long) -> Unit)? = null
    /** Horizontal swipe to switch tracks: next=true left-swipe (next track), false right-swipe (previous). */
    var onSwipe: ((next: Boolean) -> Unit)? = null

    private var lyrics: Lyrics? = null
    private var currentIndex = -1
    private var scrollY = 0f
    private var targetY = 0f
    private var manual = false          // untimed lyrics: scroll the entire block manually
    private var userScrolling = false   // synced lyrics: user is manually browsing (auto-follow paused)
    private var accentColor = accent

    /** After the user manually scrolls synced lyrics, if no line is tapped within [RESUME_DELAY_MS] for a seek, automatically snap back to the current playback position. */
    private val resumeFollow = Runnable {
        userScrolling = false
        targetY = centers.getOrElse(currentIndex.coerceAtLeast(0)) { 0f }
        postInvalidateOnAnimation()
    }

    // Per-entry contents (units: content coordinates, from 0): centre y and block height
    private var centers = FloatArray(0)
    private var heights = FloatArray(0)
    private var entryChangedAt = 0L

    private val subLine = dp(26f)   // vertical slot height per text line
    private val entryGap = dp(16f)  // extra spacing between entries
    private val sideMargin = dp(20f)

    // TextPaint (not Paint): TextUtils.ellipsize needs it
    private val normal = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(16f); textAlign = Paint.Align.CENTER; color = Color.argb(150, 255, 255, 255)
    }
    private val highlight = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(18f); textAlign = Paint.Align.CENTER; color = accent; isFakeBoldText = true
    }
    private val hint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(15f); textAlign = Paint.Align.CENTER; color = Color.argb(140, 255, 255, 255)
    }

    private var startX = 0f          // press position (decides horizontal-track-switch vs vertical-scroll)
    private var startY = 0f
    private var downY = 0f           // increment reference for vertical scroll (updated as we move)
    private var dragging = false
    private var horizontalDrag = false

    // Inertial scroll: on release, continue scrolling for a moment based on fling velocity, rather than stopping abruptly
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val flingTick = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll())
                invalidate()
                postOnAnimation(this)
            } else if (lyrics?.synced == true) {
                userScrolling = true
                removeCallbacks(resumeFollow)
                postDelayed(resumeFollow, RESUME_DELAY_MS)
            }
        }
    }

    fun setLyrics(l: Lyrics?) {
        scroller.forceFinished(true)
        removeCallbacks(flingTick)
        lyrics = l
        currentIndex = -1
        scrollY = 0f; targetY = 0f
        manual = l != null && !l.synced
        userScrolling = false
        removeCallbacks(resumeFollow)
        computeLayout()
        entryChangedAt = System.currentTimeMillis()
        invalidate()
    }

    /** Highlight colour tracks the cover's dominant colour (0 = default). */
    fun setAccent(color: Int) { accentColor = if (color != 0) color else accent; highlight.color = accentColor; invalidate() }

    /** Colour for non-current lines / hint text (derived from the background by [MusicTint], no longer a fixed translucent white). */
    fun setTint(normalColor: Int, hintColor: Int) {
        normal.color = normalColor; hint.color = hintColor; invalidate()
    }

    fun hasLyrics(): Boolean = lyrics?.isEmpty == false

    private fun computeLayout() {
        val l = lyrics
        if (l == null || l.isEmpty) { centers = FloatArray(0); heights = FloatArray(0); return }
        centers = FloatArray(l.lines.size)
        heights = FloatArray(l.lines.size)
        var cursor = 0f
        for (i in l.lines.indices) {
            val h = l.lines[i].texts.size * subLine
            heights[i] = h
            centers[i] = cursor + h / 2f
            cursor += h + entryGap
        }
    }

    fun setPositionMs(ms: Long) {
        val l = lyrics ?: return
        if (!l.synced) return
        var idx = -1
        for (i in l.lines.indices) if (l.lines[i].timeMs <= ms) idx = i else break
        if (idx != currentIndex) {
            currentIndex = idx
            targetY = centers.getOrElse(idx.coerceAtLeast(0)) { 0f }
            entryChangedAt = System.currentTimeMillis()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val l = lyrics
        val cy = height / 2f
        if (l == null || l.isEmpty) {
            canvas.drawText(context.getString(com.twig.app.R.string.music_no_lyrics), width / 2f, cy, hint)
            return
        }
        if (l.synced && !manual && !userScrolling && abs(scrollY - targetY) > 0.5f) {
            scrollY += (targetY - scrollY) * 0.18f
            if (abs(scrollY - targetY) <= 0.5f) scrollY = targetY
            postInvalidateOnAnimation()
        }
        val cx = width / 2f
        val avail = width - 2 * sideMargin
        var marqueeRunning = false
        for (i in l.lines.indices) {
            val blockCenter = cy + centers[i] - scrollY
            val h = heights[i]
            // Hide the whole line once half of it has crossed the top/bottom edge (don't draw half a character),
// rather than only hiding once it's fully scrolled off-screen
            if (blockCenter - h / 2f < 0 || blockCenter + h / 2f > height) continue
            val p = if (i == currentIndex) highlight else normal
            val texts = l.lines[i].texts
            val firstY = blockCenter - h / 2f + subLine / 2f
            texts.forEachIndexed { j, t ->
                val baseY = firstY + j * subLine + p.textSize / 3f
                val tw = p.measureText(t)
                if (i == currentIndex && tw > avail) {
                    marqueeRunning = true
                    drawMarquee(canvas, t, p, cx, baseY, avail, tw)
                } else if (tw > avail) {
                    val clipped = TextUtils.ellipsize(t, p, avail, TextUtils.TruncateAt.END).toString()
                    canvas.drawText(clipped, cx, baseY, p)
                } else {
                    canvas.drawText(t, cx, baseY, p)
                }
            }
        }
        if (marqueeRunning) postInvalidateOnAnimation()
    }

    /** Marquee for the current line: centred text shuttles left/right within the visible area, pausing briefly at each end. */
    private fun drawMarquee(canvas: Canvas, text: String, p: Paint, cx: Float, baseY: Float, avail: Float, tw: Float) {
        val overflow = tw - avail
        val speed = dp(45f) / 1000f      // px/ms
        val pause = 800f                 // endpoint pause in ms
        val travelMs = overflow / speed
        val period = travelMs + pause
        val t = (System.currentTimeMillis() - entryChangedAt).toFloat()
        val cycle = t % (period * 2f)
        val off = when {
            cycle < pause -> 0f
            cycle < pause + travelMs -> (cycle - pause) * speed
            cycle < pause * 2 + travelMs -> overflow
            else -> overflow - (cycle - pause * 2 - travelMs) * speed
        }.coerceIn(0f, overflow)
        // CENTER alignment: x moves from "display left end" to "display right end"
        val xLeft = sideMargin + tw / 2f
        canvas.drawText(text, xLeft - off, baseY, p)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) scroller.forceFinished(true) // Press again during inertial scroll: take over immediately
                removeCallbacks(flingTick)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                startX = event.x; startY = event.y; downY = event.y
                dragging = false; horizontalDrag = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val totalDx = event.x - startX
                val totalDy = event.y - startY
                if (!dragging && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                    dragging = true
                    horizontalDrag = abs(totalDx) > abs(totalDy) // horizontal wins and is treated as a track-switch gesture
                    if (horizontalDrag) parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging && !horizontalDrag) {
                    // Vertical: scroll the lyrics (slightly amplified displacement so the touch feels more responsive)
                    scrollY = (scrollY - (event.y - downY) * DRAG_SPEED).coerceIn(0f, maxScroll())
                    downY = event.y
                    if (lyrics?.synced == true) {
                        userScrolling = true
                        removeCallbacks(resumeFollow)
                        postDelayed(resumeFollow, RESUME_DELAY_MS)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                when {
                    dragging && horizontalDrag -> if (abs(event.x - startX) > dp(48f)) onSwipe?.invoke(event.x < startX)
                    !dragging -> handleTap(event.x, event.y)
                    else -> { // vertical drag ends: start inertial scroll based on release velocity
                        velocityTracker?.let { vt ->
                            vt.addMovement(event)
                            vt.computeCurrentVelocity(1000)
                            val vy = -vt.yVelocity * DRAG_SPEED
                            if (abs(vy) > minFlingVelocity) {
                                scroller.forceFinished(true)
                                scroller.fling(0, scrollY.toInt(), 0, vy.toInt(), 0, 0, 0, maxScroll().toInt())
                                removeCallbacks(resumeFollow) // after inertia ends, flingTick will re-time
                                postOnAnimation(flingTick)
                            } else if (lyrics?.synced == true) {
                                userScrolling = true
                                removeCallbacks(resumeFollow)
                                postDelayed(resumeFollow, RESUME_DELAY_MS)
                            }
                        }
                    }
                }
                velocityTracker?.recycle(); velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> { velocityTracker?.recycle(); velocityTracker = null }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val l = lyrics
        if (l != null && l.synced && !l.isEmpty) {
            val i = entryAt(x, y)
            if (i >= 0) {
                // Tap a line to seek to it; exit manual-browsing so auto-follow takes over again
                userScrolling = false
                removeCallbacks(resumeFollow)
                onSeekTo?.invoke(l.lines[i].timeMs)
                performClick()
                return
            }
        }
        // Untimed lyrics, or a tap in the blank left/right of the text — nowhere to seek, so tap toggles back to the cover
        onTap?.invoke(); performClick()
    }

    /** Which lyric block does the screen point (x,y) land on (within actual text)? Blank space left/right of the text doesn't count, returns -1. */
    private fun entryAt(x: Float, y: Float): Int {
        val l = lyrics ?: return -1
        val cy = height / 2f
        for (i in centers.indices) {
            val screen = cy + centers[i] - scrollY
            val h = heights[i]
            if (abs(y - screen) <= h / 2f + entryGap / 2f) {
                return if (textHit(l, i, screen, h, x, y)) i else -1
            }
        }
        return -1
    }

    /** Does the click x actually land inside the actual width of a line of text in this lyric entry (not the centred blank padding)? */
    private fun textHit(l: Lyrics, i: Int, blockCenterScreen: Float, h: Float, x: Float, y: Float): Boolean {
        val texts = l.lines[i].texts
        if (texts.isEmpty()) return false
        val top = blockCenterScreen - h / 2f
        val row = ((y - top) / subLine).toInt().coerceIn(0, texts.size - 1)
        val paint = if (i == currentIndex) highlight else normal
        val avail = width - 2 * sideMargin
        val tw = minOf(paint.measureText(texts[row]), avail)
        val cx = width / 2f
        val slop = dp(12f) // widen the hit range a little so text edges don't feel sticky
        return abs(x - cx) <= tw / 2f + slop
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun maxScroll(): Float {
        if (centers.isEmpty()) return 0f
        return centers.last().coerceAtLeast(0f)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private val touchSlop get() = dp(8f)

    companion object {
        private val accent = Color.parseColor("#66BB6A")
        private const val RESUME_DELAY_MS = 3000L
        private const val DRAG_SPEED = 1.15f // manual scroll feels a bit more responsive than a strict 1:1
    }
}
