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
 * 自绘滚动歌词:
 * - 一条歌词可含多行(双语原文+译文、换行符拆行),堆叠显示;当前条整体高亮居中、平滑跟随。
 * - 当前条某行过长时跑马灯左右滚动;非当前条过长则省略号截断。
 * - 纵向拖动滚动歌词、点某条歌词 seek 到该句;横向滑动回调 [onSwipe] 切上/下一首。
 * - 封面↔歌词的切换不在这里(交给下方信息区),无时间戳歌词点击回调 [onTap]。
 */
class LyricsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onTap: (() -> Unit)? = null
    /** 点击某条带时间戳的歌词 → 跳转到该时间(ms)。 */
    var onSeekTo: ((Long) -> Unit)? = null
    /** 横向滑动切歌:next=true 左滑(下一首),false 右滑(上一首)。 */
    var onSwipe: ((next: Boolean) -> Unit)? = null

    private var lyrics: Lyrics? = null
    private var currentIndex = -1
    private var scrollY = 0f
    private var targetY = 0f
    private var manual = false          // 无时间戳歌词:整段手动滚动
    private var userScrolling = false   // 同步歌词:用户正在手动浏览(暂停自动跟随)
    private var accentColor = accent

    /** 同步歌词手动滚动后,若 [RESUME_DELAY_MS] 内没点行 seek,自动跳回当前播放位置。 */
    private val resumeFollow = Runnable {
        userScrolling = false
        targetY = centers.getOrElse(currentIndex.coerceAtLeast(0)) { 0f }
        postInvalidateOnAnimation()
    }

    // 每条的内容(单位:content 坐标,从 0 起)中心 y 与块高
    private var centers = FloatArray(0)
    private var heights = FloatArray(0)
    private var entryChangedAt = 0L

    private val subLine = dp(26f)   // 每行文本竖直槽高
    private val entryGap = dp(16f)  // 条与条之间的额外间距
    private val sideMargin = dp(20f)

    // TextPaint(而非 Paint):TextUtils.ellipsize 需要它
    private val normal = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(16f); textAlign = Paint.Align.CENTER; color = Color.argb(150, 255, 255, 255)
    }
    private val highlight = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(18f); textAlign = Paint.Align.CENTER; color = accent; isFakeBoldText = true
    }
    private val hint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(15f); textAlign = Paint.Align.CENTER; color = Color.argb(140, 255, 255, 255)
    }

    private var startX = 0f          // 按下位置(判断横向切歌 / 纵向滚动)
    private var startY = 0f
    private var downY = 0f           // 纵向滚动的增量参考(随移动更新)
    private var dragging = false
    private var horizontalDrag = false

    // 惯性滚动:抬手时按滑动速度继续滚一段,而不是手一松就硬停
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

    /** 高亮色随封面主色调(0 = 默认)。 */
    fun setAccent(color: Int) { accentColor = if (color != 0) color else accent; highlight.color = accentColor; invalidate() }

    /** 非当前行 / 提示文字的颜色(由 [MusicTint] 按背景反推,不再是固定半透明白)。 */
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
            // 上下边缘露出一半就整条隐藏(不画半截字),而不是只在完全滚出视野时才隐藏
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

    /** 当前行跑马灯:居中文本在可视区左右往返,两端各停顿一下。 */
    private fun drawMarquee(canvas: Canvas, text: String, p: Paint, cx: Float, baseY: Float, avail: Float, tw: Float) {
        val overflow = tw - avail
        val speed = dp(45f) / 1000f      // px/ms
        val pause = 800f                 // 端点停顿 ms
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
        // CENTER 对齐:x 从"显示左端"移到"显示右端"
        val xLeft = sideMargin + tw / 2f
        canvas.drawText(text, xLeft - off, baseY, p)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) scroller.forceFinished(true) // 惯性滚动中再次按下:立即接管
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
                    horizontalDrag = abs(totalDx) > abs(totalDy) // 横向优先判为切歌手势
                    if (horizontalDrag) parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging && !horizontalDrag) {
                    // 纵向:滚动歌词(略微放大位移,手感更跟手)
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
                    else -> { // 纵向拖拽结束:按抬手速度起惯性滚动
                        velocityTracker?.let { vt ->
                            vt.addMovement(event)
                            vt.computeCurrentVelocity(1000)
                            val vy = -vt.yVelocity * DRAG_SPEED
                            if (abs(vy) > minFlingVelocity) {
                                scroller.forceFinished(true)
                                scroller.fling(0, scrollY.toInt(), 0, vy.toInt(), 0, 0, 0, maxScroll().toInt())
                                removeCallbacks(resumeFollow) // 惯性结束后 flingTick 里再重新计时
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
                // 点某句 seek 到该句;退出手动浏览态让自动跟随接管
                userScrolling = false
                removeCallbacks(resumeFollow)
                onSeekTo?.invoke(l.lines[i].timeMs)
                performClick()
                return
            }
        }
        // 无时间戳歌词、或点在文字左右空白处,无处可跳,点击切回封面
        onTap?.invoke(); performClick()
    }

    /** 命中屏幕 (x,y) 落在哪条歌词块的文字上;文字左右的空白处不算命中,返回 -1。 */
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

    /** 点击 x 是否真的落在该条歌词某一行文字的实际宽度内(而非居中留白处)。 */
    private fun textHit(l: Lyrics, i: Int, blockCenterScreen: Float, h: Float, x: Float, y: Float): Boolean {
        val texts = l.lines[i].texts
        if (texts.isEmpty()) return false
        val top = blockCenterScreen - h / 2f
        val row = ((y - top) / subLine).toInt().coerceIn(0, texts.size - 1)
        val paint = if (i == currentIndex) highlight else normal
        val avail = width - 2 * sideMargin
        val tw = minOf(paint.measureText(texts[row]), avail)
        val cx = width / 2f
        val slop = dp(12f) // 命中范围稍放宽,避免文字边缘卡手
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
        private const val DRAG_SPEED = 1.15f // 手动滚动稍微跟手一点,不完全 1:1
    }
}
