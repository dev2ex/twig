package com.twig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Prefs
import com.twig.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Full BLOB inspection through the same virtual hex rows used by file viewers. */
internal object SqliteBlobDialog {
    fun show(context: Context, title: String, size: Long, parent: CoroutineScope,
        read: suspend (Long, Int) -> ByteArray): AlertDialog {
        val dp = context.resources.displayMetrics.density
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val source = ChunkedHexSource(size, read)
        val list = RecyclerView(context)
        val pane = HexPane(list, scope)
        pane.open(source)
        pane.setTextSize(Prefs.hexTextSize(context))
        pane.theme = CodeHighlighter.THEMES[Prefs.codeTheme(context).coerceIn(0, CodeHighlighter.THEMES.lastIndex)]
        list.setBackgroundColor(pane.theme.bg)
        val status = TextView(context).apply {
            text = context.getString(R.string.sqlite_blob, size)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
        val bar = FastScrollBar(context)
        val frame = FrameLayout(context).apply {
            setBackgroundColor(pane.theme.bg)
            addView(list, FrameLayout.LayoutParams(-1, -1).apply { marginEnd = (8 * dp).toInt() })
            addView(bar, FrameLayout.LayoutParams((24 * dp).toInt(), -1, Gravity.END))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(frame, LinearLayout.LayoutParams(-1,
                minOf(360 * dp, context.resources.displayMetrics.heightPixels * 0.6f).toInt()))
        }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(content)
            .setPositiveButton(R.string.dialog_close, null).create()
        fun maxFirstRow(): Int {
            val visible = (pane.lastVisibleRow() - pane.firstVisibleRow() + 1).coerceAtLeast(1)
            return (pane.rowCount - visible).coerceAtLeast(1)
        }
        fun syncBar() {
            bar.isEnabled = list.canScrollVertically(-1) || list.canScrollVertically(1)
            if (!bar.dragging) bar.fraction = pane.firstVisibleRow().coerceAtLeast(0).toFloat() / maxFirstRow()
        }
        bar.onDrag = { fraction, _ -> pane.scrollToRow((fraction * maxFirstRow()).roundToInt()) }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { syncBar() }
        })
        list.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) list.post {
                if (job.isActive) {
                    val digits = HexLayout.offsetDigits(size)
                    val bpr = pane.computeBpr(digits)
                    if (bpr > 0) pane.applyLayout(bpr, digits, false)
                    list.post { syncBar() }
                }
            }
        }
        pane.onRowLongClick = { row ->
            pane.rowText(row)?.let { text ->
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(title, text))
            }
        }
        pane.onReadError = { error ->
            if (error is CancellationException) dialog.dismiss()
            else {
                status.setText(R.string.sqlite_read_failed)
                frame.visibility = View.GONE
                pane.close()
            }
        }
        dialog.setOnDismissListener {
            job.cancel()
            pane.close()
        }
        // Leaving the screen also dismisses the window and cancels native range reads.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { dialog.dismiss() }
        }
        dialog.show()
        return dialog
    }
}
