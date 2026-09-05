package com.twig.app.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Format
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ActivityHexViewerBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Hex viewer. Reads via [FsRegistry]; works uniformly across all sources.
 *
 * The table itself lives in [HexPane] (shared with [HexCompareActivity]); this class owns the
 * toolbar, the search bar, theme picking, pinch zoom and the fast scroll bar. A few design notes:
 *  - **Virtual scrolling**: one RecyclerView item per row, bytes are pulled on demand from [HexSource]'s
 *    chunk cache; when a chunk is missing a placeholder is drawn first, then visible rows refresh once the
 *    chunk arrives. This removes the read-everything cap, so even a 10GB file opens — and at open time only
 *    the title bar's one screen has been read.
 *  - **No horizontal scrolling**: bytes per row is computed from pane width ÷ character width
 *    ([HexPane.computeBpr]), and recomputed whenever font size changes.
 *  - **Search**: text (UTF-8, ASCII case-insensitive) and hex modes both scan the same byte stream, and hits
 *    are highlighted in both the hex and char columns simultaneously — through [HexPane.hits], the same hook
 *    the compare page feeds byte differences into.
 *  - The colour scheme is shared with the text viewer's [CodeHighlighter.THEMES], switchable from the menu;
 *    font size is adjustable via two-finger pinch and stored in [Prefs.hexTextSize].
 */
class HexViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityHexViewerBinding
    private lateinit var file: XFile
    private lateinit var pane: HexPane

    private var pendingRelayout = false

    // search
    private var hexMode = false
    private var matches: List<Long> = emptyList()
    private var patLen = 0
    private var cur = -1
    private var searchJob: Job? = null
    private var searchMenuItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHexViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val scheme = intent.getStringExtra(EXTRA_SCHEME) ?: return finish()
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: path

        b.toolbar.title = name
        b.toolbar.setNavigationOnClickListener { handleBack() }
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(name))
        buildMenu()

        pane = HexPane(b.list, lifecycleScope).apply {
            setTextSize(Prefs.hexTextSize(this@HexViewerActivity))
            hits = { off, len -> hitsIn(off, len) }
            onRowLongClick = { pos -> copyRow(pos) }
        }
        applyTheme()
        wirePinchZoom()
        wireFastScroll()
        wireSearchBar()
        // Width changed (rotation / split-screen) requires recomputing bytes-per-row; the case where the file is read
        // first and the view hasn't measured its width yet is also caught here ([pendingRelayout]).
        // ★ Must post before computing: this callback fires during layout traversal; calling notifyDataSetChanged
        // here would trigger "requestLayout during layout", and the update for this frame is just dropped by the system.
        b.list.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol || pendingRelayout) {
                pendingRelayout = false
                b.list.post { relayout(force = true) }
            }
        }

        file = XFile(scheme = scheme, path = path, isDir = false, displayName = name)
        open(intent.getLongExtra(EXTRA_SIZE, 0L))
    }

    override fun onDestroy() {
        pane.close()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (b.searchBar.visibility == View.VISIBLE) toggleSearch(false) else finish()
    }

    private fun buildMenu() {
        searchMenuItem = b.toolbar.menu.add(R.string.viewer_search).apply {
            setIcon(R.drawable.ic_search)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { toggleSearch(true); true }
        }
        b.toolbar.menu.add(R.string.viewer_text_bigger).setOnMenuItemClickListener {
            applyTextSize(pane.textSp + 1f, persist = true); true
        }
        b.toolbar.menu.add(R.string.viewer_text_smaller).setOnMenuItemClickListener {
            applyTextSize(pane.textSp - 1f, persist = true); true
        }
        b.toolbar.menu.add(R.string.viewer_theme).setOnMenuItemClickListener { pickTheme(); true }
    }

    // ---- load ----

    private fun open(declaredSize: Long) {
        b.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = runCatching { pane.open(file, declaredSize) }
            b.loading.visibility = View.GONE
            r.fold(
                onSuccess = {
                    if (pane.src?.truncated == true) {
                        toast(getString(R.string.viewer_too_large, Format.size(HexSource.MAX_MEM.toLong())))
                    }
                    relayout(force = true)
                },
                onFailure = { showFatal(it) },
            )
        }
    }

    private fun showFatal(e: Throwable) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.viewer_load_failed, e.message ?: ""))
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    // ---- layout: bytes per row ----

    private fun relayout(force: Boolean) {
        if (pane.src == null) return
        val digits = HexLayout.offsetDigits(pane.size)
        val newBpr = pane.computeBpr(digits)
        if (newBpr == 0) {
            // Width not measured yet (the file read finished before the first layout). **Don't post a retry yourself** —
            // that creates an endlessly spinning chain of messages; just flag it and wait for the layout callback to call us.
            pendingRelayout = true
            return
        }
        if (pane.applyLayout(newBpr, digits, force)) b.list.post { syncBar() }
    }

    private fun applyTextSize(sp: Float, persist: Boolean) {
        // Quantize to 0.5sp: pinch gestures come every frame, so without quantization we'd relayout the whole table every frame
        val v = (sp.coerceIn(MIN_SP, MAX_SP) * 2).roundToInt() / 2f
        if (pane.setTextSize(v)) relayout(force = true)
        if (persist) Prefs.setHexTextSize(this, pane.textSp)
    }

    private fun wirePinchZoom() {
        val detector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    applyTextSize(pane.textSp * d.scaleFactor, persist = false)
                    return true
                }

                override fun onScaleEnd(d: ScaleGestureDetector) {
                    Prefs.setHexTextSize(this@HexViewerActivity, pane.textSp)
                }
            },
        )
        b.list.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return e.pointerCount >= 2 || detector.isInProgress
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                detector.onTouchEvent(e)
            }

            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
        })
    }

    // ---- fast scroll bar ----

    private fun wireFastScroll() {
        b.fastscroll.onDrag = { f, ended ->
            if (pane.rowCount > 1) {
                val row = (maxFirstRow() * f).roundToInt().coerceIn(0, pane.rowCount - 1)
                pane.scrollToRow(row)
                showDragHint(row.toLong() * pane.bpr, !ended)
            }
        }
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!b.fastscroll.dragging) syncBar()
            }
        })
    }

    /**
     * First-row index corresponding to thumb fraction 1.0 — it's "total rows - one screen of rows", not "total rows".
     * If you used total rows as the denominator, dragging to the bottom only gets you to the second-to-last screen,
     * and the very last screen would never be reachable.
     */
    private fun maxFirstRow(): Int {
        val first = pane.firstVisibleRow()
        val last = pane.lastVisibleRow()
        val onScreen = if (first in 0..last) last - first + 1 else 1
        return (pane.rowCount - onScreen).coerceAtLeast(1)
    }

    private fun syncBar() {
        val first = pane.firstVisibleRow()
        val last = pane.lastVisibleRow()
        val onScreen = if (first in 0..last) last - first + 1 else 0
        b.fastscroll.isEnabled = pane.rowCount > onScreen && pane.rowCount > 1
        b.fastscroll.invalidate()
        if (first >= 0 && pane.rowCount > 1) b.fastscroll.fraction = first.toFloat() / maxFirstRow()
    }

    private fun showDragHint(off: Long, show: Boolean) {
        b.dragHint.visibility = if (show) View.VISIBLE else View.GONE
        if (show) b.dragHint.text = String.format(Locale.US, "%0${pane.offDigits}X", off)
    }

    // ---- search ----

    private fun wireSearchBar() {
        syncSearchMode()
        b.searchMode.setOnClickListener {
            hexMode = !hexMode
            syncSearchMode()
            runSearch(jumpFirst = true)
        }
        b.searchInput.doAfterTextChanged { runSearch(jumpFirst = true) }
        b.searchInput.setOnEditorActionListener { _, _, _ -> move(1); true }
        b.searchPrev.setOnClickListener { move(-1) }
        b.searchNext.setOnClickListener { move(1) }
        b.searchClose.setOnClickListener { toggleSearch(false) }
    }

    private fun syncSearchMode() {
        b.searchMode.setText(if (hexMode) R.string.hex_search_hex else R.string.hex_search_text)
        b.searchInput.hint = getString(
            if (hexMode) R.string.hex_search_hint_hex else R.string.hex_search_hint_text,
        )
    }

    private fun toggleSearch(show: Boolean) {
        b.searchBar.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            b.searchInput.requestFocus()
            imm.showSoftInput(b.searchInput, 0)
        } else {
            imm.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
            b.searchInput.setText("") // also clears hit highlights as a bonus
        }
    }

    private fun runSearch(jumpFirst: Boolean) {
        searchJob?.cancel()
        val q = b.searchInput.text.toString()
        val pat = if (hexMode) HexLayout.parseHex(q) else q.toByteArray(Charsets.UTF_8)
        matches = emptyList()
        cur = -1
        patLen = pat.size
        val s = pane.src
        if (pat.isEmpty() || s == null) {
            b.searchCount.text = ""
            pane.refreshVisible()
            return
        }
        b.searchCount.text = "…"
        pane.refreshVisible()
        val from = pane.topOffset()
        searchJob = lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE) // don't re-scan the whole file on every keystroke during typing
            val found = withContext(Dispatchers.IO) {
                val active = { coroutineContext.isActive }
                runCatching { s.search(pat, fold = !hexMode, limit = MAX_MATCHES, active = active) }
                    .getOrDefault(emptyList())
            }
            matches = found
            patLen = pat.size
            if (found.isEmpty()) {
                b.searchCount.text = "0"
                pane.refreshVisible()
            } else if (jumpFirst) {
                // Find the nearest hit at or after the current screen position, instead of always jumping back to the file start
                val i = found.indexOfFirst { it >= from }
                jumpTo(if (i >= 0) i else 0)
            } else {
                b.searchCount.text = "${matches.size}"
                pane.refreshVisible()
            }
        }
    }

    private fun move(delta: Int) {
        if (matches.isEmpty()) return
        jumpTo((cur + delta + matches.size) % matches.size)
    }

    private fun jumpTo(index: Int) {
        cur = index
        val off = matches[index]
        b.searchCount.text = "${index + 1}/${matches.size}"
        pane.scrollToOffset(off, b.list.height / 3)
        b.list.post {
            pane.refreshVisible()
            syncBar()
        }
    }

    /** Hit ranges within a row; [HexHit.current] flags the one navigation is sitting on. */
    private fun hitsIn(off: Long, len: Int): List<HexHit> {
        if (matches.isEmpty() || patLen <= 0) return emptyList()
        val out = ArrayList<HexHit>(2)
        // First hit that could intersect this row: its end position must cross the row start, i.e. m > off - patLen
        var i = matches.binarySearch { m -> if (m <= off - patLen) -1 else 1 }
        if (i < 0) i = -i - 1
        while (i < matches.size && matches[i] < off + len) {
            val s = maxOf(0L, matches[i] - off).toInt()
            val e = minOf(len.toLong(), matches[i] + patLen - off).toInt()
            if (e > s) out.add(HexHit(s, e, i == cur))
            i++
        }
        return out
    }

    // ---- theme ----

    private fun theme(): CodeHighlighter.Theme =
        CodeHighlighter.THEMES[Prefs.codeTheme(this).coerceIn(0, CodeHighlighter.THEMES.size - 1)]

    private fun applyTheme() {
        pane.theme = theme()
        b.list.setBackgroundColor(theme().bg)
        NavBarTint.apply(this, theme().bg) // navigation bar matches the body area
    }

    private fun pickTheme() {
        val names = CodeHighlighter.THEMES.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_theme)
            .setSingleChoiceItems(names, Prefs.codeTheme(this).coerceIn(0, names.size - 1)) { d, i ->
                Prefs.setCodeTheme(this, i)
                applyTheme()
                pane.refreshAll()
                d.dismiss()
            }
            .show()
    }

    private fun copyRow(pos: Int) {
        val line = pane.rowText(pos) ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hex", line))
        toast(getString(R.string.info_copied))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"
        private const val MIN_SP = 7f
        private const val MAX_SP = 28f
        private const val MAX_MATCHES = 2000
        private const val SEARCH_DEBOUNCE = 250L

        fun start(context: Context, file: XFile) {
            context.startActivity(
                Intent(context, HexViewerActivity::class.java).apply {
                    putExtra(EXTRA_SCHEME, file.scheme)
                    putExtra(EXTRA_PATH, file.path)
                    putExtra(EXTRA_NAME, file.name)
                    putExtra(EXTRA_SIZE, file.size)
                },
            )
        }
    }
}
