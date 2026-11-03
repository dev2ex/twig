package com.twig.app.ui

import android.app.Activity
import android.content.res.Configuration
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SqliteHighlightTest {
    @Test fun `static SQL uses surface-appropriate colors and preserves comment boundaries when flattened`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val sql = "SELECT 'text' -- comment\nFROM entries WHERE id = 42"
        for ((mode, theme) in listOf(Configuration.UI_MODE_NIGHT_NO to 2, Configuration.UI_MODE_NIGHT_YES to 1)) {
            val config = Configuration(activity.resources.configuration).apply { uiMode = mode }
            val context = activity.createConfigurationContext(config)
            val text = SqliteHighlight.text(context, sql, singleLine = true) as Spanned
            assertEquals(sql.replace('\n', ' '), text.toString())
            val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
            val from = spans.single { text.getSpanStart(it) == sql.indexOf("FROM") }
            assertEquals(CodeHighlighter.THEMES[theme].colors[0], from.foregroundColor)
            val comment = spans.single { text.getSpanStart(it) == sql.indexOf("--") }
            assertEquals(sql.indexOf('\n'), text.getSpanEnd(comment))
        }
        val longSummary = SqliteHighlight.text(activity, "SELECT 1;\n".repeat(1000), singleLine = true) as Spanned
        assertTrue(longSummary.getSpans(0, longSummary.length, CodeHighlighter.Hl::class.java).isEmpty())
    }

    @Test fun `live SQL preserves the editable selection other spans and composing region`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val input = EditText(activity).apply { setText("SELECT 'hello' FROM entries") }
        activity.setContentView(input)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val editable = input.text
        val search = BackgroundColorSpan(0xffff0000.toInt())
        input.setSelection(7, 14)
        BaseInputConnection.setComposingSpans(editable)
        editable.setSpan(search, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val composingStart = BaseInputConnection.getComposingSpanStart(editable)
        assertTrue(composingStart >= 0)
        val stop = SqliteHighlight.edit(input, scope)
        try {
            advanceTimeBy(200)
            runCurrent()
            assertEquals(composingStart, BaseInputConnection.getComposingSpanStart(editable))
            assertTrue(editable.getSpans(0, editable.length, CodeHighlighter.Hl::class.java).isEmpty())
            BaseInputConnection.removeComposingSpans(editable)
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            assertSame(editable, input.text)
            assertEquals(7, input.selectionStart)
            assertEquals(14, input.selectionEnd)
            assertEquals(0, editable.getSpanStart(search))
            assertTrue(editable.getSpans(0, editable.length, CodeHighlighter.Hl::class.java).isNotEmpty())
            input.setText("-- comment\nSELECT 42")
            input.setSelection(3)
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(3, input.selectionStart)
            val colored = input.text.getSpans(0, input.length(), ForegroundColorSpan::class.java)
            assertEquals(3, colored.size)
            assertEquals(input.length(), input.text.getSpanEnd(colored.last()))
            input.append(" WHERE id = 1")
            stop()
            advanceUntilIdle()
            assertTrue(scope.coroutineContext[Job]!!.children.none())
        } finally { stop(); scope.cancel() }
    }

    @Test fun `live size limit removes stale highlighting without changing the SQL`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val input = EditText(activity).apply { setText("SELECT 1") }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val stop = SqliteHighlight.edit(input, scope)
        try {
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            assertTrue(input.text.getSpans(0, input.length(), CodeHighlighter.Hl::class.java).isNotEmpty())
            input.append("\n-- text".repeat(15000))
            assertTrue(input.length() > CodeHighlighter.MAX_LIVE_HIGHLIGHT)
            val sql = input.text.toString()
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(sql, input.text.toString())
            assertTrue(input.text.getSpans(0, input.length(), CodeHighlighter.Hl::class.java).isEmpty())
        } finally { stop(); scope.cancel() }
    }
}
