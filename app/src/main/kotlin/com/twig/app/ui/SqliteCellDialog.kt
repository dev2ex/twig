package com.twig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.twig.app.R
import com.twig.app.Format
import com.twig.app.sqlite.SqliteCell

internal object SqliteCellDialog {
    fun show(context: Context, title: String, cell: SqliteCell) {
        val value = when (cell.kind) {
            'n' -> "NULL"
            'b' -> context.getString(R.string.sqlite_blob, Format.size(cell.length)) + "\n" + cell.text
            else -> cell.text
        }
        val displayed = if (cell.truncated) value + "\n\n" + context.getString(R.string.sqlite_value_truncated, cell.length) else value
        show(context, title, displayed, value)
    }

    fun show(context: Context, title: String, displayed: String, copied: String = displayed) {
        val dp = context.resources.displayMetrics.density
        val text = TextView(context).apply {
            text = displayed
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
        AlertDialog.Builder(context).setTitle(title).setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton(R.string.dialog_close, null)
            .setNeutralButton(R.string.sqlite_copy) { _, _ ->
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, copied))
            }.show()
    }
}
