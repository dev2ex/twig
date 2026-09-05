package com.twig.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.EditText

/**
 * Line-number gutter for [TextViewerActivity]. Sits beside [target]'s [android.widget.HorizontalScrollView]
 * inside the same horizontal `LinearLayout` (declared `layout_height="match_parent"` so it always matches the
 * content column's height — the "match_parent child of a wrap_content parent" quirk only clamps the *main* axis
 * of a `LinearLayout`, and here that axis is horizontal, so the cross-axis (height) stretch works normally). Being
 * inside the same single child of the outer vertical `ScrollView`, it scrolls in lockstep with the content with
 * no extra wiring.
 *
 * Numbers are drawn from [target]'s live [android.text.Layout], not from a separately held count: with word wrap
 * on, one logical line can render as several visual rows, and only the row whose start offset immediately follows
 * a '\n' (or is offset 0) is the start of a new logical line — that is the row that gets a number, the wrapped
 * continuation rows stay blank. [lineCount] only drives the gutter's width (digit count), so it must be kept in
 * sync by the caller whenever the text changes.
 */
class LineNumberGutter @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var target: EditText? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Number of logical lines in [target]'s text; only affects gutter width (digit count). */
    var lineCount: Int = 1
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var numberColor: Int = 0xFF757575.toInt()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val hPad = (resources.displayMetrics.density * 8f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val t = target
        paint.textSize = t?.textSize ?: (resources.displayMetrics.scaledDensity * 13f)
        paint.typeface = t?.typeface
        val digits = lineCount.toString().length.coerceAtLeast(2)
        val w = paint.measureText("0".repeat(digits)).toInt() + hPad * 2
        setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val t = target ?: return
        val layout = t.layout ?: return
        val text = t.text ?: return
        paint.color = numberColor
        paint.textSize = t.textSize
        paint.typeface = t.typeface
        val top = t.totalPaddingTop
        val right = (width - hPad).toFloat()
        var logicalLine = 0
        for (i in 0 until layout.lineCount) {
            val start = layout.getLineStart(i)
            if (i == 0 || text[start - 1] == '\n') {
                logicalLine++
                canvas.drawText(logicalLine.toString(), right, (top + layout.getLineBaseline(i)).toFloat(), paint)
            }
        }
    }
}
