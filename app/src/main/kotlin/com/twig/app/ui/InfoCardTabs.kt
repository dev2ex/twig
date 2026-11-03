package com.twig.app.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.twig.app.R

/** Shared property-card tabs; content providers keep their own page models and gestures. */
internal object InfoCardTabs {
    fun bind(bar: LinearLayout, titles: List<String>, current: Int, textSp: Float,
        onTab: (Int) -> Unit, onClose: () -> Unit) {
        val context = bar.context
        val dp = bar.resources.displayMetrics.density
        val active = ContextCompat.getColor(context, R.color.primary)
        val idle = ContextCompat.getColor(context, R.color.text_secondary)
        bar.visibility = View.VISIBLE
        bar.removeAllViews()
        titles.forEachIndexed { index, title ->
            bar.addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                setTextColor(if (index == current) active else idle)
                typeface = if (index == current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding((9 * dp).toInt(), (6 * dp).toInt(), (9 * dp).toInt(), (6 * dp).toInt())
                if (index != current) setOnClickListener { onTab(index) }
            })
        }
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(TextView(context).apply {
            text = context.getString(R.string.info_close)
            contentDescription = context.getString(R.string.dialog_close)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            setTextColor(idle)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { onClose() }
        })
    }
}
