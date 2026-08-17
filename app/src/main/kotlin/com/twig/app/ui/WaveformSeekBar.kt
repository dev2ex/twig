package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 自绘波形进度条:等宽竖条(固定 [BAR_DP] 宽 + [GAP_DP] 间隔),已播 accent、未播半透明白。
 * 拖动 seek,按下回调预览时间。
 *
 * 未加载出波形前画一条"矮的平条"占位(方角、居中细条),视觉上区别于真正的波形并暗示加载中;
 * 占位条与波形条同一套等宽栅格,加载完成后原地"长出"高低起伏,不跳位。
 */
class WaveformSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 拖动松手回调最终位置(ms)。 */
    var onSeek: ((Long) -> Unit)? = null
    /** 拖动中回调预览位置(ms),null 表示拖动结束。 */
    var onPreview: ((Long?) -> Unit)? = null

    private var wave: ByteArray? = null
    private var durationMs = 0L
    private var positionMs = 0L
    private var dragFraction = -1f // 拖动中 >=0

    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    // 未播提高到 ~170 alpha,原来 90 太淡在毛玻璃背景上几乎看不清
    private val unplayed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255) }

    fun setWaveform(data: ByteArray?) { wave = data; invalidate() }

    /** 已播条颜色随封面主色调动态调整(0 = 恢复默认 accent)。 */
    fun setAccent(color: Int) { played.color = if (color != 0) color else accent; invalidate() }

    /** 未播条颜色(由 [MusicTint] 按背景反推,不再是固定半透明白)。 */
    fun setUnplayedColor(color: Int) { unplayed.color = color; invalidate() }

    fun setProgress(pos: Long, dur: Long) {
        positionMs = pos; durationMs = dur
        if (dragFraction < 0) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0) return
        val data = wave
        val frac = if (dragFraction >= 0) dragFraction
        else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val playedX = frac * w

        val barW = dp(BAR_DP)
        val gap = dp(GAP_DP)
        val n = (((w + gap) / (barW + gap)).toInt()).coerceAtLeast(1)
        // 整体居中:把多出来的余量均分到两端
        val startX = (w - (n * barW + (n - 1) * gap)) / 2f

        if (data == null) {
            // 占位:矮的平条(方角),高度固定,仅用颜色区分已播/未播,便于加载前也能拖动
            val bh = dp(PLACEHOLDER_DP)
            val top = (h - bh) / 2f
            for (i in 0 until n) {
                val left = startX + i * (barW + gap)
                canvas.drawRect(left, top, left + barW, top + bh,
                    if (left + barW / 2 <= playedX) played else unplayed)
            }
            return
        }

        val buckets = data.size
        for (i in 0 until n) {
            val bi = (i.toLong() * buckets / n).toInt().coerceIn(0, buckets - 1)
            val amp = (data[bi].toInt() and 0xFF) / 255f
            val bh = (h * (0.06f + amp * 0.94f)).coerceAtLeast(dp(2f))
            val left = startX + i * (barW + gap)
            val top = (h - bh) / 2f
            canvas.drawRect(left, top, left + barW, top + bh,
                if (left + barW / 2 <= playedX) played else unplayed)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragFraction = (event.x / width).coerceIn(0f, 1f)
                onPreview?.invoke((dragFraction * durationMs).toLong())
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val f = (event.x / width).coerceIn(0f, 1f)
                dragFraction = -1f
                onPreview?.invoke(null)
                onSeek?.invoke((f * durationMs).toLong())
                performClick()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragFraction = -1f; onPreview?.invoke(null); invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        private val accent = Color.parseColor("#66BB6A")
        private const val BAR_DP = 1.5f        // 每条固定宽度
        private const val GAP_DP = 1.5f          // 条间距
        private const val PLACEHOLDER_DP = 3f  // 加载中占位平条高度(矮、方角)
    }
}
