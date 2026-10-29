package com.twig.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.twig.app.Prefs
import com.twig.app.R

/** Shared appearance controls for the subtitle picker and player settings. Changes apply immediately. */
internal object SubtitleSizeUi {
    fun create(ctx: Context, includeTitle: Boolean = false, onChanged: (Int) -> Unit): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, (16 * density).toInt(), padding, 0)
        }
        if (includeTitle) content.addView(TextView(ctx).apply {
            setText(R.string.player_subtitle)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (12 * density).toInt())
        })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        content.addView(row)
        val sizeColumn = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        row.addView(sizeColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        val label = TextView(ctx).apply {
            text = ctx.getString(R.string.player_subtitle_size_value, Prefs.subtitleSize(ctx))
        }
        sizeColumn.addView(label)
        sizeColumn.addView(SeekBar(ctx).apply {
            max = Prefs.SUBTITLE_SIZE_MAX - Prefs.SUBTITLE_SIZE_MIN
            progress = Prefs.subtitleSize(ctx) - Prefs.SUBTITLE_SIZE_MIN
            contentDescription = ctx.getString(R.string.player_subtitle_size)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val size = progress + Prefs.SUBTITLE_SIZE_MIN
                    Prefs.setSubtitleSize(ctx, size)
                    label.text = ctx.getString(R.string.player_subtitle_size_value, size)
                    onChanged(size)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * density).toInt()))
        val colors = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), 0, 0, 0)
        }
        row.addView(colors, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun addColorControl(title: Int, get: () -> Int, set: (Int) -> Unit) {
            val button = Button(ctx).apply {
                setText(title)
                isAllCaps = false
                textSize = 12f
                minimumWidth = 0
                minWidth = 0
                setPadding((4 * density).toInt(), paddingTop, (12 * density).toInt(), paddingBottom)
            }
            fun refresh() {
                val swatch = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(get())
                    setStroke((density).toInt().coerceAtLeast(1), Color.GRAY)
                    setSize((18 * density).toInt(), (18 * density).toInt())
                }
                button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, swatch, null)
                button.compoundDrawablePadding = (6 * density).toInt()
            }
            refresh()
            button.setOnClickListener {
                showColors(ctx, title, get()) { color ->
                    set(color)
                    refresh()
                    onChanged(Prefs.subtitleSize(ctx))
                }
            }
            colors.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addColorControl(R.string.player_subtitle_text_color, { Prefs.subtitleTextColor(ctx) }) {
            Prefs.setSubtitleTextColor(ctx, it)
        }
        return content
    }

    private fun showColors(ctx: Context, title: Int, selected: Int, onSelected: (Int) -> Unit) {
        val density = ctx.resources.displayMetrics.density
        val palette = intArrayOf(
            Color.WHITE, 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(),
            0xFFFFA500.toInt(), 0xFFFF69B4.toInt(), 0xFFFF0000.toInt(), 0xFF448AFF.toInt(),
        )
        val grid = GridLayout(ctx).apply {
            columnCount = 4
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
        }
        val dialog = AlertDialog.Builder(ctx).setTitle(title).setView(grid)
            .setNegativeButton(android.R.string.cancel, null).create()
        for (color in palette) {
            val swatch = View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(color)
                    setStroke(((if (color == selected) 3 else 1) * density).toInt().coerceAtLeast(1),
                        if (color == selected) 0xFF448AFF.toInt() else Color.GRAY)
                }
                contentDescription = String.format("#%08X", color)
                isSelected = color == selected
                setOnClickListener { onSelected(color); dialog.dismiss() }
            }
            grid.addView(swatch, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                height = (48 * density).toInt()
                setMargins((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            })
        }
        dialog.show()
        dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
}
