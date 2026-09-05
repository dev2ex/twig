package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.method.KeyListener
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.twig.app.Format
import com.twig.app.OpenFiles
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.TextCodec
import com.twig.app.databinding.ActivityTextViewerBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/**
 * Built-in plain-text viewer / editor. Reads through [FsRegistry]'s `openInput`, so text files on local storage, inside
 * archives, on FTP, etc. can all be viewed directly — no need to extract / download first. Another reuse of the unified
 * FileSystem abstraction. Recognised code extensions go through [CodeHighlighter] for lexical highlighting (themes
 * like Monokai, switchable from the menu); the search icon in the action bar opens a search bar, highlights every hit,
 * and ▲▼ jump between them.
 *
 * Markdown and HTML open **rendered** (a WebView, see [renderPreview]) — that is what you want to see nine times out
 * of ten, so it is the default and there is no Preview toggle in the menu. The action bar's pencil is the way to the
 * source: it leaves the render and enters edit mode in one tap, and backing out of editing returns to the render
 * ([previewHome]). "Open as text" in the long-press menu still opens the raw source.
 *
 * Editing: the same EditText toggles read-only / editable in place ([enterEdit]); save goes through [writeAtomically]
 * back to the same source. Editing is disallowed in three cases (see [editBlockReason] and [canEdit]) — all are
 * scenarios where "save would destroy the original file", better not to expose the entry:
 *  1. The file exceeded [MAX_BYTES] and was truncated — saving would chop the file down to 1 MB;
 *  2. **No encoding strictly decoded it** — the U+FFFDs produced by [readTextFile]'s lenient decode would be
 *     irreversible damage on save;
 *  3. The source is read-only overall (7z/RAR/restic/git viewer/`share`) or the entry isn't writable.
 *
 * ★ Encoding isn't a binary "UTF-8 vs non-UTF-8" — [TextCodec] recognising a file as GBK (etc.) is still editable,
 * but it **must be written back in the original encoding** ([fileCharset] + [fileBom]) — saving as UTF-8 leaves the
 * contents character-for-character unchanged, but other programs then open it as mojibake. When the original encoding
 * can't represent a newly-typed character (e.g. emoji inside GBK), the save fails; that's when we ask the user whether
 * to convert to UTF-8.
 */
class TextViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityTextViewerBinding
    private var raw: String = ""
    private var rawLower: String = ""
    private var tokens: List<CodeHighlighter.Token> = emptyList()
    private var isCode = false
    private var isMarkdown = false
    private lateinit var currentFile: XFile
    private var fileName: String = ""
    private var lang: CodeHighlighter.Lang? = null
    private var isHtml = false
    private var isPreviewable = false
    private var previewMode = false

    /**
     * Preview is this file's *home* view — set when the activity was opened in preview mode.
     * There is no preview toggle in the menu any more (markdown/html open rendered by default,
     * and the pencil is the way out), so this is what tells [exitEdit] to go back to the render
     * instead of dropping the reader into raw source they never asked for.
     */
    private var previewHome = false
    private var searchMenuItem: MenuItem? = null

    // Edit state
    private var truncated = false

    /** Encoding used when reading; null = no encoding strictly decoded it (lenient UTF-8 display); editing is disallowed. */
    private var fileCharset: Charset? = Charsets.UTF_8

    /** Original BOM at the file's head (stripped during decode); restored verbatim on save. */
    private var fileBom: ByteArray? = null
    private var canEdit = false
    private var editMode = false
    private var dirty = false
    private var saving = false
    private var liveHighlight = false
    private var settingText = false // applyTheme's setText shouldn't be treated as a user edit
    private var hlGen = 0 // invalidation marker for in-flight re-highlighting
    private var editMenuItem: MenuItem? = null
    private var saveMenuItem: MenuItem? = null

    /** Read-only state removes the keyListener (still selectable / copyable); edit state restores it — the original lives here. */
    private var savedKeyListener: KeyListener? = null

    // Pinch-zoom font size: in sp, restored from Prefs in onCreate, written back on gesture end.
    private var contentTextSizeSp = 13f

    // Search state: hit offsets, current index, applied highlight spans (must be re-applied after theme change rebuilds text)
    private var matches: List<Int> = emptyList()
    private var matchLen = 0
    private var cur = -1
    private val hitSpans = ArrayList<Any>()
    private var curSpan: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTextViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val scheme = intent.getStringExtra(EXTRA_SCHEME) ?: return finish()
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: path

        fileName = name
        b.toolbar.title = name
        b.toolbar.setNavigationOnClickListener { handleBack() }
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(name))

        // Read-only state: remove the keyListener — no keyboard pop, no cursor. Just removing it isn't enough;
        // we also need setTextIsSelectable(true) to truly guarantee long-press select/copy (it doesn't touch
        // keyListener, only manages movement method and focusable/clickable, and subsequent setText still passes
        // BufferType.EDITABLE explicitly — it won't get internally changed to SPANNABLE). Enter edit mode and just
        // restore the keyListener (see enterEdit) — no view swap, no text move.
        savedKeyListener = b.content.keyListener
        b.content.keyListener = null
        b.content.setTextIsSelectable(true)
        b.content.isCursorVisible = false
        b.content.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        b.content.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        wireEditor()

        b.lineNumbers.target = b.content
        b.lineNumbers.numberColor = ContextCompat.getColor(this, R.color.text_secondary)
        applyLineNumbersVisibility()

        // Font size: restored from last remembered value; pinch-zoom adjusts in real time, written back to Prefs on release.
        contentTextSizeSp = Prefs.viewerTextSize(this)
        b.content.setTextSize(TypedValue.COMPLEX_UNIT_SP, contentTextSizeSp)
        b.scroll.onScale = { factor -> applyTextSize(contentTextSizeSp * factor) }
        b.scroll.onScaleEnd = { Prefs.setViewerTextSize(this, contentTextSizeSp) }

        // Horizontal scroll indicator pinned to the viewport's bottom (native draws at the content's bottom edge, hidden if you don't scroll to the end)
        b.scroll.hsv = b.hscroll
        b.hscroll.setOnScrollChangeListener { _, _, _, _, _ -> b.scroll.invalidate() }

        searchMenuItem = b.toolbar.menu.add(R.string.viewer_search).apply {
            setIcon(R.drawable.ic_search)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { toggleSearch(true); true }
        }
        editMenuItem = b.toolbar.menu.add(R.string.viewer_edit).apply {
            setIcon(R.drawable.ic_edit)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false // we only know whether editing is allowed after loading completes
            setOnMenuItemClickListener { enterEdit(); true }
        }
        saveMenuItem = b.toolbar.menu.add(R.string.viewer_save).apply {
            setIcon(R.drawable.ic_save)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false
            setOnMenuItemClickListener { save(); true }
        }
        b.toolbar.menu.add(R.string.viewer_wrap).apply {
            isCheckable = true
            isChecked = Prefs.viewerWrap(this@TextViewerActivity)
            setOnMenuItemClickListener {
                val on = !Prefs.viewerWrap(this@TextViewerActivity)
                Prefs.setViewerWrap(this@TextViewerActivity, on)
                it.isChecked = on
                applyWrap()
                true
            }
        }
        b.toolbar.menu.add(R.string.viewer_line_numbers).apply {
            isCheckable = true
            isChecked = Prefs.viewerLineNumbers(this@TextViewerActivity)
            setOnMenuItemClickListener {
                val on = !Prefs.viewerLineNumbers(this@TextViewerActivity)
                Prefs.setViewerLineNumbers(this@TextViewerActivity, on)
                it.isChecked = on
                applyLineNumbersVisibility()
                true
            }
        }
        lang = CodeHighlighter.langFor(name)
        if (lang != null) {
            b.toolbar.menu.add(R.string.viewer_theme).setOnMenuItemClickListener { pickTheme(); true }
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        isHtml = ext == "html" || ext == "htm"
        isPreviewable = isHtml || lang?.markdown == true
        b.scroll.post { applyWrap() } // wait for layout to complete to get viewport width

        // Carry the displayName: content:// paths don't include the file name; losing it means losing the extension (used for syntax-highlight / preview detection)
        currentFile = XFile(scheme = scheme, path = path, isDir = false, displayName = name)
        setupWebView()
        wireSearchBar()
        load(currentFile, intent.getBooleanExtra(EXTRA_PREVIEW, false))
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = handleBack()

    override fun onDestroy() {
        b.content.removeCallbacks(relightTask)
        super.onDestroy()
    }

    /** Back during editing: first exit edit state, prompt on unsaved changes; only really exit if neither applies. */
    private fun handleBack() {
        when {
            saving -> return // write in progress, don't let the Activity run away
            editMode && dirty -> confirmDiscard()
            editMode -> exitEdit()
            else -> finish()
        }
    }

    /**
     * Preview-mode WebView: JS disabled (pure display, no scripting capability needed, reduces attack surface); relative
     * resource requests (images / CSS) are mapped back to [currentFile]'s directory by [shouldInterceptRequest], read
     * via the unified FileSystem abstraction — local / inside-archive / SMB / WebDAV etc. sources naturally all work.
     * Both internal relative links and external http(s) links are intercepted: the former because cross-document
     * navigation isn't supported yet, the latter gets handed off to the system browser.
     */
    private fun setupWebView() {
        b.webview.settings.javaScriptEnabled = false
        b.webview.settings.setSupportZoom(true)
        b.webview.settings.builtInZoomControls = true
        b.webview.settings.displayZoomControls = false
        b.webview.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (request.url.host != WEBVIEW_HOST) return null
                return runCatching {
                    // Uri.path is already decoded, and the base URL itself is the file's real directory
                    // (see webviewBaseUrl()), so when the browser resolves "../" it climbs along the real directory
                    // depth, not getting truncated at a "fake root" earlier — parent-directory relative refs just work.
                    val abs = normalizePath(request.url.path.orEmpty().ifEmpty { return null })
                    val target = XFile(currentFile.scheme, abs, isDir = false)
                    val input = FsRegistry.of(target).openInput(target)
                    WebResourceResponse(OpenFiles.mimeOf(target.name), "", input)
                }.getOrNull()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "http" || url.scheme == "https") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                }
                return true
            }
        }
        // Hit counter for preview-mode search (see runPreviewSearch). Only the final callback of a
        // pass carries the real total — the interim ones stream partial counts as the page is walked.
        b.webview.setFindListener { active, count, done ->
            if (!done) return@setFindListener
            b.searchCount.text = if (count == 0) "0" else "${active + 1}/$count"
        }
    }

    private fun renderPreview() {
        val html = if (isHtml) raw else MarkdownHtml.render(raw)
        b.webview.loadDataWithBaseURL(webviewBaseUrl(), html, "text/html", "utf-8", null)
    }

    /**
     * A fake origin (no network) + the file's real directory path, composed into the base URL for relative-resource
     * resolution. The path depth must be real — using a fixed root ("https://host/") would make the browser's
     * "../xxx" parse truncate at the root (RFC 3986 remove_dot_segments has nothing left to climb past an empty path),
     * so parent-directory relative references would forever break, and only siblings / sub-dirs work. Laying out
     * the base URL at the real directory depth lets ".." climb up along the real hierarchy.
     */
    private fun webviewBaseUrl(): String {
        val dir = currentFile.parentPath.trim('/')
        val encoded = if (dir.isEmpty()) "" else dir.split('/').joinToString("/") { Uri.encode(it) }
        return "https://$WEBVIEW_HOST/$encoded/"
    }

    private fun applyPreviewVisibility() {
        b.scroll.visibility = if (previewMode) View.GONE else View.VISIBLE
        b.webview.visibility = if (previewMode) View.VISIBLE else View.GONE
        syncNavBar() // preview page's background is the markdown set (follows system light/dark), unrelated to the code theme
        syncEditMenu()
    }

    /** Normalize path: collapse `.`/`..`, strip extra slashes (same algorithm as [com.twig.app.M3uPlaylist]). */
    private fun normalizePath(path: String): String {
        val stack = ArrayList<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            else -> stack.add(p)
        }
        return "/" + stack.joinToString("/")
    }

    /**
     * Word wrap: TextView inside HorizontalScrollView is measured as UNSPECIFIED, so layoutParams width doesn't apply,
     * but maxWidth still takes effect in non-EXACTLY mode — clamp to viewport width and lines wrap; horizontal
     * scroll naturally disappears. Turning it off restores MAX_VALUE and we go back to long-line horizontal scroll.
     * No need to touch the layout hierarchy.
     */
    private fun applyTextSize(sp: Float) {
        contentTextSizeSp = sp.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        b.content.setTextSize(TypedValue.COMPLEX_UNIT_SP, contentTextSizeSp)
        b.lineNumbers.requestLayout()
        b.lineNumbers.invalidate()
    }

    private fun applyWrap() {
        b.content.maxWidth = if (Prefs.viewerWrap(this)) {
            // Subtract the cursor pad: CodeEditText adds it back after measuring; without subtracting,
            // wrap ends up a few pixels wider than the viewport and a phantom horizontal scroll appears.
            // Also subtract the gutter: it shares the row with hscroll (layout_weight="1"), so the space
            // actually available to content is narrower than the whole viewport by the gutter's width.
            val vp = if (b.scroll.width > 0) b.scroll.width else resources.displayMetrics.widthPixels
            val gutter = if (b.lineNumbers.visibility == View.VISIBLE) b.lineNumbers.width else 0
            (vp - gutter - b.content.cursorPad).coerceAtLeast(1)
        } else {
            Int.MAX_VALUE
        }
        b.lineNumbers.invalidate()
    }

    private fun applyLineNumbersVisibility() {
        b.lineNumbers.visibility = if (Prefs.viewerLineNumbers(this)) View.VISIBLE else View.GONE
        refreshLineNumbers()
        applyWrap() // the gutter taking/releasing width shifts where wrap kicks in
    }

    /** [LineNumberGutter] rereads [b.content]'s live Layout on every draw for row positions — this only needs to
     * keep its logical line count (hence gutter width) and redraw in sync after text or size changes. */
    private fun refreshLineNumbers() {
        val text = b.content.text
        b.lineNumbers.lineCount = if (text.isNullOrEmpty()) 1 else text.count { it == '\n' } + 1
        b.lineNumbers.invalidate()
    }

    // ---- Search ----

    private fun wireSearchBar() {
        b.searchInput.doAfterTextChanged { runSearch(jumpFirst = true) }
        b.searchInput.setOnEditorActionListener { _, _, _ -> move(1); true }
        b.searchPrev.setOnClickListener { move(-1) }
        b.searchNext.setOnClickListener { move(1) }
        b.searchClose.setOnClickListener { toggleSearch(false) }
    }

    private fun toggleSearch(show: Boolean) {
        b.searchBar.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            b.searchInput.requestFocus()
            imm.showSoftInput(b.searchInput, 0)
        } else {
            imm.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
            b.searchInput.setText("")
            runSearch(jumpFirst = false) // clear highlights
            // Unconditional: entering edit mode leaves preview *first* and closes the bar after,
            // so by now previewMode is already false while the render still holds its matches.
            b.webview.clearMatches()
        }
    }

    /**
     * Full-text search and highlight every hit (case-insensitive, hit count capped to guard against
     * pathological input). In preview mode the source text isn't what's on screen, so the search is
     * handed to the WebView instead — see [runPreviewSearch].
     */
    private fun runSearch(jumpFirst: Boolean) {
        if (previewMode) {
            runPreviewSearch(b.searchInput.text.toString())
            return
        }
        val sp = b.content.text as? Spannable
        hitSpans.forEach { sp?.removeSpan(it) }
        hitSpans.clear()
        curSpan?.let { sp?.removeSpan(it) }
        curSpan = null
        cur = -1
        val q = b.searchInput.text.toString()
        matchLen = q.length
        if (q.isEmpty() || sp == null) {
            matches = emptyList()
            b.searchCount.text = ""
            return
        }
        val ql = q.lowercase()
        val list = ArrayList<Int>()
        var i = rawLower.indexOf(ql)
        while (i >= 0 && list.size < MAX_MATCHES) {
            list.add(i)
            i = rawLower.indexOf(ql, i + ql.length)
        }
        matches = list
        for (m in list) {
            val s = BackgroundColorSpan(HIT_BG)
            sp.setSpan(s, m, m + matchLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            hitSpans.add(s)
        }
        if (list.isEmpty()) b.searchCount.text = "0" else if (jumpFirst) jumpTo(0)
    }

    /**
     * Preview-mode search: the WebView's own find-in-page rather than our offsets into [raw].
     * The rendered page is a different document from the source — a markdown table becomes cells,
     * a fenced block becomes a `<pre>`, and `**bold**`'s asterisks aren't on screen at all — so a
     * source offset has nothing to scroll to. Find-in-page highlights and scrolls in the rendered
     * layout, and reports its counts through the [setFindListener] wired in [setupWebView].
     */
    private fun runPreviewSearch(query: String) {
        b.webview.clearMatches()
        if (query.isEmpty()) {
            b.searchCount.text = ""
            return
        }
        b.webview.findAllAsync(query)
    }

    private fun move(delta: Int) {
        // findNext wraps around by itself and re-fires the find listener with the new ordinal.
        if (previewMode) {
            b.webview.findNext(delta > 0)
            return
        }
        if (matches.isEmpty()) return
        jumpTo((cur + delta + matches.size) % matches.size)
    }

    private fun jumpTo(index: Int) {
        val sp = b.content.text as? Spannable ?: return
        cur = index
        val pos = matches[index]
        curSpan?.let { sp.removeSpan(it) }
        val s = BackgroundColorSpan(CUR_BG)
        sp.setSpan(s, pos, pos + matchLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        curSpan = s
        b.searchCount.text = "${index + 1}/${matches.size}"
        b.content.post {
            val layout = b.content.layout ?: return@post
            val line = layout.getLineForOffset(pos)
            val y = layout.getLineTop(line) - b.scroll.height / 3
            b.scroll.smoothScrollTo(0, maxOf(0, y))
            val x = layout.getPrimaryHorizontal(pos).toInt() - b.hscroll.width / 3
            b.hscroll.smoothScrollTo(maxOf(0, x), 0)
        }
    }

    // ---- Editing ----

    private fun wireEditor() {
        b.content.doAfterTextChanged {
            refreshLineNumbers()
            if (settingText || !editMode) return@doAfterTextChanged
            hlGen++ // text changed, in-flight token batch invalidated
            if (!dirty) {
                dirty = true
                updateTitle()
            }
            if (liveHighlight) scheduleRelight()
        }
    }

    /** Reason editing is blocked; null means it's allowed. A non-writable source isn't in this list — that case simply doesn't expose the entry. */
    private fun editBlockReason(): String? = when {
        truncated -> getString(R.string.viewer_readonly_truncated, Format.size(MAX_BYTES))
        fileCharset == null -> getString(R.string.viewer_readonly_encoding)
        else -> null
    }

    private fun enterEdit() {
        editBlockReason()?.let { toast(it); return }
        if (!canEdit) return
        // Editing always happens on the source text, so the pencil doubles as "leave the render".
        // [exitEdit] brings the preview back, so the pair reads as one preview <-> edit round trip.
        if (previewMode) {
            previewMode = false
            applyPreviewVisibility()
        }
        editMode = true
        if (b.searchBar.visibility == View.VISIBLE) toggleSearch(false)
        b.content.keyListener = savedKeyListener
        b.content.isCursorVisible = true
        // ★ The cursor blink timer only restarts on **actual focus change** (Editor.onFocusChanged → makeBlink),
        //   and setCursorVisible(true) just invalidates once. When entering edit mode the body is **already focused**
        //   — onCreate's setTextIsSelectable made it the page's only focusable view, so layout auto-focuses it,
        //   and requestFocus() returns immediately without doing anything. So if the cursor drawn that frame happens
        //   to be in the "off" phase, no one ever repaints it, and the symptom is "cursor is invisible, type one character
        //   to make it appear" (text change goes through handleTextChanged → makeBlink). clearFocus first to force a real
        //   focus change; internally it makes the root view find focus again — the body is the only focusable, so focus
        //   cycles around in place, and the blink comes alive.
        b.content.clearFocus()
        b.content.clearFocus()
        b.content.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(b.content, 0)
        syncEditMenu()
    }

    private fun exitEdit() {
        editMode = false
        b.content.keyListener = null
        b.content.isCursorVisible = false
        b.content.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.content.windowToken, 0)
        syncEditMenu()
        if (previewHome) {
            previewMode = true
            renderPreview() // the text may have been saved in the meantime
            applyPreviewVisibility()
        }
    }

    private fun syncEditMenu() {
        // The pencil stays up in preview mode: it is the only way out of the render now that
        // the Preview toggle is gone (see [enterEdit]).
        editMenuItem?.isVisible = canEdit && !editMode
        saveMenuItem?.isVisible = editMode
        // Search yields to editing: hit offsets get scrambled by edits
        searchMenuItem?.isVisible = !editMode
    }

    private fun updateTitle() {
        b.toolbar.title = if (dirty) "*$fileName" else fileName
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_discard_title)
            .setMessage(R.string.viewer_discard_msg)
            .setPositiveButton(R.string.viewer_save) { _, _ -> save(exitAfter = true) }
            .setNegativeButton(R.string.viewer_discard_ok) { _, _ ->
                dirty = false
                applyTheme() // discard changes, lay raw back down
                updateTitle()
                exitEdit()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Save. **Write back in the encoding the file was read with**, not always UTF-8: a GBK file silently converted to
     * UTF-8 shows no anomaly in twig (it recognises it itself), but other programs open it as mojibake — the user
     * changed one line, and the whole file is ruined.
     *
     * If the original encoding can't represent the new content (e.g. emoji inside GBK), don't force-save:
     * [TextCodec.encode] returns null, surface the stuck characters and let the user pick "save as UTF-8" or go back.
     */
    private fun save(exitAfter: Boolean = false) {
        if (saving) return
        val text = b.content.text.toString()
        val cs = fileCharset ?: Charsets.UTF_8 // fallback: if we got into edit state, decoding must have succeeded once
        val bytes = TextCodec.encode(text, cs, fileBom)
        if (bytes == null) {
            askConvertUtf8(cs, text, exitAfter)
            return
        }
        saving = true
        b.loadingBox.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    writeAtomically(currentFile, bytes)
                }
            }
            saving = false
            b.loadingBox.visibility = View.GONE
            r.fold(
                onSuccess = {
                    raw = text
                    rawLower = text.lowercase()
                    dirty = false
                    updateTitle()
                    if (isCode && !liveHighlight) relight() // large files don't rescan while typing, catch up after save
                    toast(getString(R.string.viewer_saved))
                    if (exitAfter) exitEdit() // save triggered by back key prompt: should leave after saving
                },
                onFailure = { showSaveError(it) },
            )
        }
    }

    /** The original encoding can't hold the new content: surface the stuck characters and let the user decide whether to convert to UTF-8 for save. */
    private fun askConvertUtf8(cs: Charset, text: String, exitAfter: Boolean) {
        val chars = TextCodec.unmappable(text, cs).joinToString(" ")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.viewer_charset_failed_title, cs.name()))
            .setMessage(getString(R.string.viewer_charset_failed, cs.name(), chars))
            .setPositiveButton(R.string.viewer_save_as_utf8) { _, _ ->
                fileCharset = Charsets.UTF_8
                fileBom = null // the original BOM was for the old encoding, becomes invalid after conversion
                updateEncodingLabel()
                save(exitAfter)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** For non-UTF-8 files, show the encoding name under the title — the user needs to know they're seeing "decoded as GBK". */
    private fun updateEncodingLabel() {
        val cs = fileCharset
        b.toolbar.subtitle = if (cs == null || cs == Charsets.UTF_8) null else cs.name()
    }

    private fun scheduleRelight() {
        b.content.removeCallbacks(relightTask)
        b.content.postDelayed(relightTask, RELIGHT_DELAY)
    }

    private val relightTask = Runnable { relight() }

    /**
     * Re-highlight during editing. **Rescan the whole document** — only a full tokenize gets cross-line comment /
     * multi-line string / md-fence state right (same reasoning as [DiffActivity] scanning each side in one go and
     * slicing per line), a partial rescan will scramble the colours. The scan itself runs on a background thread,
     * and back on the main thread only [CodeHighlighter.applyTo] swaps spans in place.
     */
    private fun relight() {
        val l = lang ?: return
        val ed: Editable = b.content.text ?: return
        // ★ Never touch spans during IME composition: the composing text is itself marked by spans,
        //   removing them this round collapses the pinyin string on the spot (lost characters / duplicates
        //   going to the screen). Defer to after the composition is committed.
        if (BaseInputConnection.getComposingSpanStart(ed) >= 0) {
            scheduleRelight()
            return
        }
        val text = ed.toString()
        if (text.length > CodeHighlighter.MAX_HIGHLIGHT) return
        val gen = ++hlGen
        lifecycleScope.launch {
            val toks = withContext(Dispatchers.Default) { CodeHighlighter.tokenize(text, l) }
            if (gen != hlGen) return@launch // text changed during the scan, this batch is invalid
            val live: Editable = b.content.text ?: return@launch
            if (live.length != text.length) return@launch
            tokens = toks
            CodeHighlighter.applyTo(live, toks, currentTheme())
        }
    }

    // ---- Loading and themes ----

    private fun load(file: XFile, startPreview: Boolean) {
        val l = lang
        b.loadingBox.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val read = readTextFile(file, MAX_BYTES.toInt())
                    val full = if (read.truncated) {
                        read.text + "\n\n" + getString(R.string.viewer_too_large, Format.size(MAX_BYTES))
                    } else {
                        read.text
                    }
                    // tokens are computed off-thread; theme switches just re-colour, no rescan
                    val toks = if (l != null && full.length <= CodeHighlighter.MAX_HIGHLIGHT) {
                        CodeHighlighter.tokenize(full, l)
                    } else {
                        emptyList()
                    }
                    Loaded(full, full.lowercase(), toks, read.truncated, read.charset, read.bom, canWriteTo(file))
                }
            }
            b.loadingBox.visibility = View.GONE
            result.fold(
                onSuccess = { r ->
                    raw = r.text
                    rawLower = r.lower
                    tokens = r.tokens
                    truncated = r.truncated
                    fileCharset = r.charset
                    fileBom = r.bom
                    updateEncodingLabel()
                    isCode = l != null
                    isMarkdown = l?.markdown == true
                    // Rescanning the whole document on every keystroke drops frames at scale; over the threshold,
                    // only highlight on entry to edit / after save
                    liveHighlight = isCode && raw.length <= CodeHighlighter.MAX_LIVE_HIGHLIGHT
                    // Truncated / non-UTF-8 still get the button — tap shows a Toast explaining why;
                    // read-only source hides the entry entirely
                    canEdit = r.writable
                    applyTheme()
                    syncEditMenu()
                    previewHome = startPreview && isPreviewable
                    if (intent.getBooleanExtra(EXTRA_EDIT, false)) {
                        // Explicitly asked to edit (a file we just created) — that beats the render,
                        // which exitEdit will drop back to afterwards when previewHome is set.
                        enterEdit() // fine if it can't build: enterEdit itself rejects non-editable cases
                    } else if (previewHome) {
                        previewMode = true
                        renderPreview()
                        applyPreviewVisibility()
                    }
                },
                onFailure = {
                    setContentText(getString(R.string.viewer_load_failed, it.message ?: ""))
                },
            )
        }
    }

    private fun currentTheme(): CodeHighlighter.Theme =
        CodeHighlighter.THEMES[Prefs.codeTheme(this).coerceIn(0, CodeHighlighter.THEMES.size - 1)]

    /**
     * Lay [raw] back into the view and colour it by theme.
     *
     * ★ Building a Spannable first and then setText hits an old large-file trap: the text is held as EDITABLE,
     * `setText(spannable, EDITABLE)` has to copy every one of the tens of thousands of spans into the
     * SpannableStringBuilder (quadratic cost). So here we setText plain text first (zero spans to copy), then
     * call [CodeHighlighter.applyTo] directly on the Editable — only one setSpan pass.
     */
    private fun applyTheme() {
        // Markdown body uses the system default font (as opposed to code); fenced code blocks / inline code spans
        // come with their own monospace override, and other file types are unaffected, continuing to use the monospace font from the layout.
        b.content.typeface = if (isMarkdown) Typeface.DEFAULT else Typeface.MONOSPACE
        setContentText(raw)
        // Background / default foreground don't care whether the language is recognised — plain text with no
        // lexical highlighting also uses the same theme palette, otherwise opening a file with an unknown extension
        // would have colours mismatched with the highlighted files next to it.
        val t = currentTheme()
        b.scroll.setBackgroundColor(t.bg)
        b.content.setTextColor(t.fg)
        b.lineNumbers.numberColor = t.colors[2] // COMMENT — muted but tuned for this theme's own background
        syncNavBar()
        if (isCode) {
            b.content.text?.let { CodeHighlighter.applyTo(it, tokens, t) }
        }
        // Text got a new instance, search highlight spans are all lost; if the search bar is open, re-apply
        hitSpans.clear()
        curSpan = null
        if (b.searchBar.visibility == View.VISIBLE && b.searchInput.text.isNotEmpty()) {
            runSearch(jumpFirst = false)
        }
    }

    /**
     * Tint the navigation bar to the bottom-most layer's colour. **The two states don't pull from the same palette**:
     * the text area follows the "viewer colours" (code theme, independent of system light/dark), whereas the
     * markdown preview is a self-contained HTML inside a WebView, whose background is decided by its
     * `prefers-color-scheme` — i.e. follows system light/dark.
     */
    private fun syncNavBar() {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        NavBarTint.apply(
            this,
            if (previewMode) MarkdownHtml.pageBg(night) else currentTheme().bg,
        )
    }

    /** Lay text; [settingText] shields doAfterTextChanged so programmatic assignment isn't treated as a user edit. */
    private fun setContentText(text: CharSequence) {
        settingText = true
        b.content.setText(text, TextView.BufferType.EDITABLE)
        settingText = false
    }

    private fun pickTheme() {
        val names = CodeHighlighter.THEMES.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_theme)
            .setSingleChoiceItems(names, Prefs.codeTheme(this).coerceIn(0, names.size - 1)) { d, i ->
                Prefs.setCodeTheme(this, i)
                // Edit mode can't re-lay text (cursor and unsaved changes would vanish), just recolour in place
                if (editMode) {
                    val t = currentTheme()
                    b.scroll.setBackgroundColor(t.bg)
                    b.content.setTextColor(t.fg)
                    b.lineNumbers.numberColor = t.colors[2]
                    syncNavBar()
                    b.content.text?.let { e -> CodeHighlighter.applyTo(e, tokens, t) }
                } else {
                    applyTheme()
                }
                d.dismiss()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Save failures go through a dialog, not a Toast: the underlying error strings (SMB NTSTATUS etc.) are long, and Toast can't fit them. */
    private fun showSaveError(e: Throwable) {
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_save_failed_title)
            .setMessage(e.message ?: e.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private class Loaded(
        val text: String,
        val lower: String,
        val tokens: List<CodeHighlighter.Token>,
        val truncated: Boolean,
        val charset: Charset?,
        val bom: ByteArray?,
        val writable: Boolean,
    )


    companion object {
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PREVIEW = "preview"
        private const val EXTRA_EDIT = "edit"
        private const val MAX_BYTES = 1L * 1024 * 1024 // 1 MB cap, to avoid OOM
        private const val MAX_MATCHES = 2000
        private const val RELIGHT_DELAY = 300L // how long to wait after typing stops before re-highlighting
        private const val MIN_TEXT_SIZE_SP = 8f
        private const val MAX_TEXT_SIZE_SP = 40f
        private const val HIT_BG = 0x66FFC107 // all hits: translucent amber
        private val CUR_BG = 0xB3FF6F00.toInt() // current hit: deep orange

        // Fake origin for the preview WebView: shouldInterceptRequest uses the host to identify "this is a relative
        // resource request we issued ourselves", not a real network call — images / CSS / etc. relative paths are
        // actually read via FileSystem. The path part of the base URL is built dynamically from the file's real
        // directory (see webviewBaseUrl()); it can't be a fixed root, otherwise "../" gets truncated early, see that function's comment.
        private const val WEBVIEW_HOST = "twig.local"

        /**
         * [preview]: markdown/html open **rendered by default** — that is what you want to see
         * nine times out of ten, and the pencil in the action bar takes you to the source.
         * Pass `false` explicitly for the entry points that mean "show me the text"
         * (`chooseOpen`'s open-as-text).
         *
         * [edit]: go straight into edit mode once loaded (used when opening a newly-created empty file, saves one tap).
         */
        fun start(
            context: Context,
            file: XFile,
            preview: Boolean = OpenFiles.isPreviewable(file),
            edit: Boolean = false,
        ) {
            context.startActivity(
                Intent(context, TextViewerActivity::class.java).apply {
                    putExtra(EXTRA_SCHEME, file.scheme)
                    putExtra(EXTRA_PATH, file.path)
                    putExtra(EXTRA_NAME, file.name)
                    if (preview) putExtra(EXTRA_PREVIEW, true)
                    if (edit) putExtra(EXTRA_EDIT, true)
                },
            )
        }
    }
}
