package com.twig.app.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * EditText used as the body of the viewer/editor: leaves an extra cursor's worth of width
 * beyond the `wrap_content` measurement.
 *
 * ★ Without the extra padding, **the cursor at the end of the widest line gets pushed
 * against the last character**: the framework's `Editor.clampHorizontalPosition` notices
 * that the cursor's x has hit the text area's right edge and pulls it left by one cursor
 * width to keep it visible (intending "don't let the cursor get clipped"); but the
 * wrap_content text area width **is exactly the width of the longest line**, so the cursor
 * at the end of that line inevitably triggers clamping. Give it a few extra pixels and the
 * clamping condition no longer holds, so the cursor lands where it should.
 *
 * The widening only happens under non-EXACTLY measurement (HorizontalScrollView already
 * measures with UNSPECIFIED); for the word-wrap path, `maxWidth` must be reduced by the
 * corresponding [cursorPad], otherwise the post-padding width is exactly those pixels
 * wider than the viewport, producing gratuitous horizontal scrolling (see
 * `TextViewerActivity.applyWrap`).
 */
class CodeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** Width reserved for the cursor (px). The cursor paint is typically about 2px wide; 3dp leaves enough headroom. */
    val cursorPad: Int = (resources.displayMetrics.density * 3f).toInt().coerceAtLeast(2)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(measuredWidth + cursorPad, measuredHeight)
        }
    }
}
