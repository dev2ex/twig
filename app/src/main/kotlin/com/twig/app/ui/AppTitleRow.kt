package com.twig.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.twig.app.R

/** Reserve title space for the app name while keeping short versions at their natural width. */
class AppTitleRow(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val version = findViewById<TextView>(R.id.appVersion)
        if (version.visibility != View.GONE) {
            val margins = version.layoutParams as LayoutParams
            val limit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                Int.MAX_VALUE
            } else {
                val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                    - margins.leftMargin - margins.rightMargin).coerceAtLeast(0)
                available / 3
            }
            if (version.maxWidth != limit) version.maxWidth = limit
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
