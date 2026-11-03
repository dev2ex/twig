package com.twig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import org.json.JSONTokener
import java.util.ArrayDeque

/** Native, bounded, in-memory tools; arbitrary JavaScript has only ordinary page privileges. */
internal class WebsiteDebugPanel(
    private val activity: AppCompatActivity,
    private val host: LinearLayout,
    private val web: WebsiteWebView,
    private val currentSite: () -> Website,
    private val persist: () -> Boolean = { true },
    private val saved: (Website) -> Unit,
) {
    private val entries = ArrayDeque<String>()
    private var characters = 0
    private var renderScheduled = false
    private val renderTask = Runnable {
        renderScheduled = false
        if (isOpen && tab == R.string.website_debug_console) renderLogs()
    }
    private var logs: TextView? = null
    private var logScroll: ScrollView? = null
    private var preview: String? = null
    private var elementInfo: TextView? = null
    private var pickButton: Button? = null
    private var parentButton: Button? = null
    private var copySelector: Button? = null
    private var copyHtml: Button? = null
    private var hideElement: Button? = null
    private var element: WebsiteElementInspector.Selection? = null
    private val inspector = WebsiteElementInspector(web) { selected ->
        element = selected
        renderElement()
    }
    private var tab = R.string.website_debug_console
    private val pages = linkedMapOf<Int, View>()
    private val tabs = linkedMapOf<Int, TextView>()
    private val resize = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }
    val isOpen: Boolean get() = host.visibility == View.VISIBLE

    init { (host.parent as View).addOnLayoutChangeListener(resize) }

    fun show() {
        if (pages.isEmpty()) build()
        host.visibility = View.VISIBLE
        resize()
        renderLogs()
    }

    fun hide(): Boolean {
        if (!isOpen) return false
        hideKeyboard()
        stopInspection()
        host.visibility = View.GONE
        web.requestFocus()
        return true
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun resize() {
        if (!isOpen) return
        val parent = host.parent as LinearLayout
        if (parent.height == 0) return
        val fixed = (0 until parent.childCount).map { parent.getChildAt(it) }.filter {
            it !== host && it.visibility != View.GONE && (it.layoutParams as LinearLayout.LayoutParams).weight == 0f
        }.sumOf { it.measuredHeight }
        val available = (parent.height - parent.paddingTop - parent.paddingBottom - fixed).coerceAtLeast(0)
        val height = (if (available < dp(280)) available else (available * 0.55f).toInt()).coerceAtMost(dp(420))
        if (host.layoutParams.height != height) host.layoutParams = host.layoutParams.apply { this.height = height }
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun button(label: Int, action: () -> Unit) = Button(activity).apply {
        tag = label
        setText(label)
        textSize = 12f
        isAllCaps = false
        minimumWidth = 0
        minWidth = 0
        setOnClickListener { action() }
    }
    private fun LinearLayout.fill(view: View) = addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    private fun editor(label: Int, value: String) = EditText(activity).apply {
        tag = label
        hint = activity.getString(label)
        typeface = Typeface.MONOSPACE
        textSize = 13f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        gravity = Gravity.TOP or Gravity.START
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setText(value)
    }

    private fun build() {
        host.setBackgroundColor(ContextCompat.getColor(activity, R.color.bar))
        host.addView(View(activity).apply { setBackgroundColor(ContextCompat.getColor(activity, R.color.divider)) },
            LinearLayout.LayoutParams(-1, dp(1)))
        host.addView(row().apply {
            addView(TextView(activity).apply {
                setText(R.string.website_debug)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(activity).apply {
                tag = R.string.dialog_close
                text = "✕"
                contentDescription = activity.getString(R.string.dialog_close)
                gravity = Gravity.CENTER
                setOnClickListener { hide() }
            }, LinearLayout.LayoutParams(dp(48), -1))
        }, LinearLayout.LayoutParams(-1, dp(36)))
        host.addView(row().apply {
            for (label in listOf(R.string.website_debug_console, R.string.website_script, R.string.website_style, R.string.website_debug_elements)) {
                val item = TextView(activity).apply {
                    tag = label
                    setText(label)
                    gravity = Gravity.CENTER
                    isFocusable = true
                    setOnClickListener { select(label) }
                }
                tabs[label] = item
                addView(item, LinearLayout.LayoutParams(0, -1, 1f))
            }
        }, LinearLayout.LayoutParams(-1, dp(36)))
        val content = column()
        host.fill(content)
        val console = column().apply {
            val text = TextView(activity).apply {
                tag = "website_console_log"
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            logs = text
            val scroll = ScrollView(activity).apply { addView(text) }
            logScroll = scroll
            fill(scroll)
            addView(button(R.string.history_clear) { entries.clear(); characters = 0; renderLogs() }, LinearLayout.LayoutParams(-1, dp(40)))
        }
        val javascript = column().apply {
            val input = editor(R.string.website_debug_js_input, currentSite().script)
            fill(input)
            addView(row().apply {
                addView(button(R.string.website_debug_run) { execute(input.text.toString()) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(button(R.string.website_debug_save_reload) {
                    saveCustomization(script = input.text.toString())?.let { hideKeyboard(); web.reload() }
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
            })
        }
        val style = column().apply {
            val input = editor(R.string.website_debug_css_input, currentSite().style)
            fill(input)
            addView(row().apply {
                addView(button(R.string.website_debug_preview) { applyPreview(input.text.toString()) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(button(R.string.website_debug_restore) {
                    input.setText(currentSite().style)
                    applyPreview(null)
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(button(R.string.viewer_save) {
                    saveCustomization(style = input.text.toString())?.let { preview = null; applyStyle(it); hideKeyboard() }
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
            })
        }
        val elements = column().apply {
            val info = TextView(activity).apply {
                tag = "website_element_info"
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            elementInfo = info
            fill(ScrollView(activity).apply { addView(info) })
            addView(row().apply {
                pickButton = button(R.string.website_debug_pick) { togglePicking() }
                parentButton = button(R.string.website_debug_pick_parent) { inspector.parent() }
                addView(pickButton, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(parentButton, LinearLayout.LayoutParams(0, dp(40), 1f))
                hideElement = button(R.string.website_debug_hide_element) { hideSelectedElement() }
                addView(hideElement, LinearLayout.LayoutParams(0, dp(40), 1f))
            })
            addView(row().apply {
                copySelector = button(R.string.website_debug_copy_selector) {
                    element?.let { copy(R.string.website_debug_selector, it.selector) }
                }
                copyHtml = button(R.string.website_debug_copy_html) {
                    element?.let { copy(R.string.website_debug_copy_html, it.html) }
                }
                addView(copySelector, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(copyHtml, LinearLayout.LayoutParams(0, dp(40), 1f))
            })
        }
        pages[R.string.website_debug_elements] = elements
        pages[R.string.website_debug_console] = console
        pages[R.string.website_script] = javascript
        pages[R.string.website_style] = style
        pages.values.forEach { content.addView(it, LinearLayout.LayoutParams(-1, -1)) }
        select(tab)
    }

    private fun select(label: Int) {
        if (label != R.string.website_debug_elements) stopInspection()
        tab = label
        pages.forEach { (key, page) -> page.visibility = if (key == label) View.VISIBLE else View.GONE }
        tabs.forEach { (key, view) ->
            view.isSelected = key == label
            view.setTextColor(ContextCompat.getColor(activity, if (key == label) R.color.action_text else R.color.text_primary))
            view.setTypeface(null, if (key == label) Typeface.BOLD else Typeface.NORMAL)
        }
        if (label == R.string.website_debug_console) renderLogs()
    }

    fun console(message: ConsoleMessage) {
        append("${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()}\n${message.message()}")
    }

    private fun append(value: String) {
        val entry = value.take(4096)
        entries.addLast(entry)
        characters += entry.length
        while (entries.size > 200 || characters > 65536) characters -= entries.removeFirst().length
        if (isOpen && tab == R.string.website_debug_console && !renderScheduled) {
            renderScheduled = true
            host.postDelayed(renderTask, 100)
        }
    }

    private fun renderLogs() {
        logs?.text = if (entries.isEmpty()) activity.getString(R.string.website_debug_console_empty) else entries.joinToString("\n\n")
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun execute(code: String) {
        if (code.isBlank()) return
        hideKeyboard()
        append("> $code")
        select(R.string.website_debug_console)
        // Native evaluation preserves global console semantics without eval(), a JS bridge or a CDN.
        web.evaluateJavascript(code + "\n//# sourceURL=twig-console.js") { raw ->
            if (!activity.isDestroyed) {
                val json = raw?.take(8192) ?: "null"
                val value = runCatching { JSONTokener(json).nextValue().toString() }.getOrDefault(json)
                append("< $value")
            }
        }
    }

    fun styleSite(site: Website): Website = preview?.let { site.copy(style = it) } ?: site

    private fun applyPreview(value: String?) {
        val site = currentSite()
        if (!WebsiteCustomization.sameOrigin(site.url, web.url)) {
            Toast.makeText(activity, R.string.website_debug_style_scope, Toast.LENGTH_LONG).show()
            return
        }
        preview = value
        hideKeyboard()
        applyStyle(styleSite(site))
    }

    private fun applyStyle(site: Website) {
        if (WebsiteCustomization.sameOrigin(site.url, web.url)) web.evaluateJavascript(WebsiteCustomization.styleScript(WebsiteStore.effective(activity, site)), null)
    }

    private fun saveCustomization(script: String? = null, style: String? = null, hiddenSelector: String? = null): Website? {
        val latest = if (persist()) WebsiteStore.all(activity).firstOrNull { it.id == currentSite().id }
            else currentSite()
        if (latest == null) {
            Toast.makeText(activity, R.string.website_missing, Toast.LENGTH_LONG).show()
            return null
        }
        val updated = latest.copy(script = script ?: latest.script, style = style ?: latest.style,
            hiddenElements = if (hiddenSelector == null) latest.hiddenElements else
                (WebsiteCustomization.hiddenSelectors(latest.hiddenElements) + hiddenSelector).distinct().joinToString("\n"))
        return try {
            if (persist()) WebsiteStore.save(activity, updated)
            saved(updated)
            Toast.makeText(activity, R.string.viewer_saved, Toast.LENGTH_SHORT).show()
            updated
        } catch (_: com.twig.app.secure.Secrets.KeyUnavailable) {
            Toast.makeText(activity, R.string.backup_locked, Toast.LENGTH_LONG).show()
            null
        }
    }

    fun navigation(url: String) {
        stopInspection()
        preview = null
        append("↻ $url")
    }

    private fun togglePicking() {
        if (web.onPickElement != null) {
            stopInspection()
            return
        }
        stopInspection()
        hideKeyboard()
        web.onPickElement = { x, y ->
            web.onPickElement = null
            renderElement()
            inspector.pick(x, y)
        }
        renderElement()
    }

    private fun hideSelectedElement() {
        val selected = element ?: return
        if (!WebsiteCustomization.sameOrigin(currentSite().url, web.url)) {
            Toast.makeText(activity, R.string.website_debug_style_scope, Toast.LENGTH_LONG).show()
            return
        }
        saveCustomization(hiddenSelector = selected.selector)?.let {
            stopInspection()
            applyStyle(styleSite(it))
        }
    }

    private fun renderElement() {
        pickButton?.setText(if (web.onPickElement != null) R.string.dialog_cancel else R.string.website_debug_pick)
        parentButton?.isEnabled = element?.hasParent == true
        copySelector?.isEnabled = element != null
        copyHtml?.isEnabled = element != null
        hideElement?.isEnabled = element != null && WebsiteCustomization.sameOrigin(currentSite().url, web.url)
        elementInfo?.text = element?.let { selected -> buildString {
            append(selected.tag)
            if (selected.id.isNotEmpty()) append("\nid=\"${selected.id}\"")
            if (selected.classes.isNotEmpty()) append("\nclass=\"${selected.classes}\"")
            append("\n\n" + activity.getString(R.string.website_debug_selector))
            append("\n" + activity.getString(R.string.website_debug_matches, selected.matches))
            append("\n" + selected.selector)
            append("\n\nHTML")
            if (selected.truncated) append(" (" + activity.getString(R.string.website_debug_html_truncated) + ")")
            append("\n" + selected.html)
        } } ?: activity.getString(R.string.website_debug_pick_hint)
    }

    private fun copy(label: Int, value: String) {
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(activity.getString(label), value))
        Toast.makeText(activity, R.string.info_copied, Toast.LENGTH_SHORT).show()
    }

    fun stopInspection() {
        web.onPickElement = null
        inspector.clear()
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(host.windowToken, 0)
        host.findFocus()?.clearFocus()
    }

    fun destroy() {
        stopInspection()
        host.removeCallbacks(renderTask)
        (host.parent as View).removeOnLayoutChangeListener(resize)
        entries.clear()
        characters = 0
        preview = null
    }
}
