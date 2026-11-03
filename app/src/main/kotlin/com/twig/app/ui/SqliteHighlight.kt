package com.twig.app.ui

import android.content.Context
import android.content.res.Configuration
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared SQL colors on the existing dialog/card surfaces, without replacing their backgrounds. */
internal object SqliteHighlight {
    private val language = checkNotNull(CodeHighlighter.langFor("query.sql"))
    private fun theme(context: Context) = CodeHighlighter.THEMES[
        if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) 1 else 2]

    fun text(context: Context, sql: String, singleLine: Boolean = false): CharSequence {
        val displayed = if (singleLine) sql.replace('\n', ' ').replace('\r', ' ') else sql
        // Flattened list summaries must respect the same style-run limits as code files.
        val tokens = if (singleLine && !CodeHighlighter.canHighlight(displayed)) emptyList()
            else CodeHighlighter.tokenize(sql, language)
        return CodeHighlighter.render(displayed, tokens, theme(context))
    }

    /** The returned cleanup belongs to the dialog, so dismissing it cancels pending scans. */
    fun edit(input: EditText, scope: CoroutineScope): () -> Unit {
        var job: Job? = null
        fun schedule(wait: Long = 150) {
            job?.cancel()
            job = scope.launch {
                delay(wait)
                // Keep the IME's composing range and selection intact, including when only spans change.
                while (BaseInputConnection.getComposingSpanStart(input.text) >= 0) delay(80)
                val sql = input.text.toString()
                val tokens = withContext(Dispatchers.Default) {
                    if (sql.length <= CodeHighlighter.MAX_LIVE_HIGHLIGHT) CodeHighlighter.tokenize(sql, language)
                    else emptyList()
                }
                while (BaseInputConnection.getComposingSpanStart(input.text) >= 0) delay(80)
                if (input.text.toString() == sql)
                    CodeHighlighter.applyTo(input.text, tokens, theme(input.context))
            }
        }
        val watcher = input.doAfterTextChanged { schedule() }
        schedule(0)
        return { input.removeTextChangedListener(watcher); job?.cancel() }
    }
}
