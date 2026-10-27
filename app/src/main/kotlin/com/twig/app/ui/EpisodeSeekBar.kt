package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import com.twig.app.EpisodeSkip

/** Draw skip ranges over the normal track, keeping the platform thumb and touch behavior. */
class EpisodeSeekBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatSeekBar(context, attrs) {
    var marks = EpisodeSkip()
        set(value) { field = value; invalidate() }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 183, 77) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (max <= 0) return
        // The track fills the padded width; thumb travel is inset by half the handle.
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val y = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val halfHeight = 2f * resources.displayMetrics.density
        fun x(ms: Long): Float {
            val fraction = ms.toFloat() / max
            return if (layoutDirection == LAYOUT_DIRECTION_RTL) right - (right - left) * fraction
            else left + (right - left) * fraction
        }
        fun range(a: Long, b: Long) {
            val xa = x(a); val xb = x(b)
            canvas.drawRoundRect(minOf(xa, xb), y - halfHeight, maxOf(xa, xb), y + halfHeight,
                halfHeight, halfHeight, rangePaint)
        }
        marks.headEnd(max.toLong()).takeIf { it > 0 }?.let { range(0, it) }
        marks.tailStart(max.toLong())?.let { range(it, max.toLong()) }
        // Keep the seek handle above the colored ranges, as in AbsSeekBar.drawThumb.
        thumb?.let {
            val saved = canvas.save()
            canvas.translate((paddingLeft - thumbOffset).toFloat(), paddingTop.toFloat())
            it.draw(canvas)
            canvas.restoreToCount(saved)
        }
    }
}
