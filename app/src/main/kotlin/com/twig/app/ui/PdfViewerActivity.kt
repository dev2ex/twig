package com.twig.app.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ActivityPdfViewerBinding
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Built-in PDF reader. The reading surface is [PdfStripView]; this class is only chrome —
 * open the document, drive the toolbar, remember the scroll mode.
 *
 * ★ Unlike [TextViewerActivity], this **cannot** read through `FsRegistry.openInput`:
 * PdfRenderer has no streaming interface and needs a seekable fd, so only `file` and SAF
 * sources can be opened (the same wall [Thumbs] hits generating PDF thumbnails). Callers
 * must therefore keep the external-app fallback for everything else — see
 * [com.twig.app.OpenFiles.canViewPdf].
 */
class PdfViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPdfViewerBinding
    private lateinit var file: XFile
    private var modeItem: MenuItem? = null
    private var doc: PdfDoc? = null
    private var actionMode: ActionMode? = null

    /**
     * Bumped to abandon an in-flight search; the walk polls it between pages and stops.
     *
     * ★ Volatile because it is written on the main thread and read from the render thread — a
     * cached value there means an abandoned search keeps chewing through the document.
     */
    @Volatile private var searchToken = 0

    /** True while a walk is still running, so the counter can distinguish "none yet" from "none". */
    private var searchRunning = false

    private val searchDebounce = Runnable { runSearch() }

    /** The scrollbar hides itself; only the page counter needs a timer here. */
    private val hidePageLabel = Runnable { b.pageLabel.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val scheme = intent.getStringExtra(EXTRA_SCHEME) ?: return finish()
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: path
        file = XFile(scheme = scheme, path = path, isDir = false, displayName = name)

        b.toolbar.title = name
        b.toolbar.setNavigationOnClickListener { finish() }
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(name))

        // ★ Only offered where the platform can actually read the text. Below Android 15 there is
        // no text API at all, so the entry is absent rather than present-and-dead.
        if (PdfDoc.TEXT_SUPPORTED) {
            b.toolbar.menu.add(R.string.viewer_search).apply {
                setIcon(R.drawable.ic_search)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { toggleSearch(true); true }
            }
            wireSearch()
        }

        modeItem = b.toolbar.menu.add(R.string.pdf_page_mode).apply {
            isCheckable = true
            isChecked = Prefs.pdfPageMode(this@PdfViewerActivity)
            setOnMenuItemClickListener {
                val on = !Prefs.pdfPageMode(this@PdfViewerActivity)
                Prefs.setPdfPageMode(this@PdfViewerActivity, on)
                it.isChecked = on
                b.strip.pageMode = on
                true
            }
        }

        b.strip.pageMode = Prefs.pdfPageMode(this)
        b.strip.onTap = { toggleChrome() }
        b.fastscroll.onDrag = { f, _ -> b.strip.scrollToFraction(f) }
        b.strip.onViewport = {
            pokeScrollChrome()
            // Keep the floating copy bar attached to the text as the page moves under it.
            actionMode?.invalidateContentRect()
        }
        b.strip.onSelection = { text -> onSelectionChanged(text) }

        NavBarTint.apply(this, getColor(R.color.surface))
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val doc = withContext(Dispatchers.IO) { PdfDoc.open(this@PdfViewerActivity, file) }
            b.loading.visibility = View.GONE
            if (doc == null) {
                Toast.makeText(this@PdfViewerActivity, R.string.pdf_open_failed, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            this@PdfViewerActivity.doc = doc
            b.strip.setDocument(doc)

            // Real page sizes arrive in the background — the first screen is already readable on
            // page 0's dimensions alone, so measuring never blocks opening. Margin detection is
            // not kicked off here at all: it is per-page and the strip measures the page being
            // read when it settles.
            doc.measureFrom { _ -> b.strip.post { b.strip.onSizesChanged() } }
        }
    }

    // ---- Text selection ----

    /**
     * Show the copy affordance for a selection, or dismiss it when the selection is gone.
     *
     * ★ Uses the platform's floating ActionMode rather than a hand-built bar: it puts the copy
     * button where the system puts it everywhere else, brings its own "Copy" string in every
     * locale, and positions itself around the selection through [ActionMode.Callback2].
     */
    private fun onSelectionChanged(text: String) {
        if (text.isEmpty()) {
            actionMode?.finish()
            return
        }
        if (actionMode == null) startSelectionMode() else actionMode?.invalidateContentRect()
    }

    private fun startSelectionMode() {
        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_COPY, 0, android.R.string.copy)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != MENU_COPY) return false
                val text = b.strip.selectedText()
                if (text.isNotEmpty()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(null, text))
                    // No toast: selection needs Android 15, where the system shows its own
                    // copy confirmation — adding ours would double it.
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                b.strip.clearSelection()
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                b.strip.selectionBounds(outRect)
            }
        }
        actionMode = b.strip.startActionMode(callback, ActionMode.TYPE_FLOATING)
    }

    // ---- Search ----

    private fun wireSearch() {
        b.searchClose.setOnClickListener { toggleSearch(false) }
        b.searchNext.setOnClickListener { stepMatch(1) }
        b.searchPrev.setOnClickListener { stepMatch(-1) }
        // ★ Search as you type, debounced. This was originally bound to the IME action alone, on
        // the reasoning that a query walks the whole document on the one render thread — but that
        // made typing look like nothing was happening at all, which is a far worse failure than
        // some wasted rendering. An abandoned walk is cheap anyway: cancellation is polled
        // between pages, so a superseded query stops within a page or two.
        b.searchInput.doAfterTextChanged {
            b.searchInput.removeCallbacks(searchDebounce)
            b.searchInput.postDelayed(searchDebounce, SEARCH_DEBOUNCE_MS)
        }
        b.searchInput.setOnEditorActionListener { _, _, _ ->
            b.searchInput.removeCallbacks(searchDebounce)
            runSearch()
            true
        }
    }

    private fun toggleSearch(show: Boolean) {
        b.searchBar.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            b.searchInput.requestFocus()
            imm.showSoftInput(b.searchInput, 0)
        } else {
            imm.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
            b.searchInput.removeCallbacks(searchDebounce)
            b.searchInput.setText("")
            searchToken++
            searchRunning = false
            b.strip.clearMatches()
            b.searchCount.text = ""
        }
    }

    private fun runSearch() {
        // Written as an explicit version check rather than leaning on PdfDoc.TEXT_SUPPORTED:
        // lint cannot see through a custom property, and searchPages is @RequiresApi.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val d = doc ?: return
        searchToken++
        val token = searchToken
        b.strip.clearMatches()
        val query = b.searchInput.text.toString()
        if (query.isEmpty()) {
            searchRunning = false
            b.searchCount.text = ""
            return
        }
        searchRunning = true
        updateSearchCount()

        // Start at the page being read and wrap: the hit you want is nearly always the next one
        // below you, and a plain 0..n walk would make you wait out the whole document to reach it.
        val start = b.strip.currentPage()
        val order = IntArray(d.pageCount) { (start + it) % d.pageCount }
        var jumped = false
        d.searchPages(
            order = order,
            query = query,
            cancelled = { token != searchToken },
            onHits = { page, hits ->
                runOnUiThread {
                    if (token != searchToken) return@runOnUiThread
                    val at = b.strip.addMatches(page, hits)
                    if (!jumped && at >= 0) {
                        jumped = true
                        b.strip.goToMatch(at)
                    }
                    updateSearchCount()
                }
            },
            onDone = {
                runOnUiThread {
                    if (token != searchToken) return@runOnUiThread
                    searchRunning = false
                    updateSearchCount()
                }
            },
        )
    }

    private fun stepMatch(dir: Int) {
        val n = b.strip.matchCount()
        if (n == 0) return
        val next = ((b.strip.currentMatch() + dir) % n + n) % n
        b.strip.goToMatch(next)
        updateSearchCount()
    }

    /**
     * ★ The counter always says something. Leaving it blank while a long document was walked is
     * exactly what made search look dead: with no hits yet and no progress shown, typing appeared
     * to do nothing at all. "…" means still looking, a bare "0" means genuinely nothing found.
     */
    private fun updateSearchCount() {
        val n = b.strip.matchCount()
        b.searchCount.text = when {
            n == 0 -> if (searchRunning) "…" else "0"
            searchRunning -> "${b.strip.currentMatch() + 1}/$n…"
            else -> "${b.strip.currentMatch() + 1}/$n"
        }
    }

    /**
     * Show the page counter and scrollbar, and restart their shared countdown.
     *
     * ★ Called on every viewport move, so it must stay cheap: `setText` is skipped unless the
     * number actually changed, because assigning identical text still triggers a layout pass and
     * this runs on every frame of a fling.
     */
    private fun pokeScrollChrome() {
        val total = b.strip.pageCount()
        if (total <= 0) return
        if (total > 1) {
            val text = "${b.strip.currentPage() + 1}/$total"
            if (b.pageLabel.text != text) b.pageLabel.text = text
            b.pageLabel.visibility = View.VISIBLE
        }
        // The bar shows and hides itself off the fraction; all this has to do is keep it
        // current, and disable it outright when there is nothing to scroll.
        // ★ Not synced while dragging: the drag already drives the strip, and writing the
        // fraction back on the way round would have the thumb fight the finger.
        b.fastscroll.isEnabled = b.strip.scrollable()
        if (!b.fastscroll.dragging) b.fastscroll.fraction = b.strip.scrollFraction()
        b.pageLabel.removeCallbacks(hidePageLabel)
        b.pageLabel.postDelayed(hidePageLabel, CHROME_HIDE_MS)
    }

    private fun toggleChrome() {
        val show = b.toolbar.visibility != View.VISIBLE
        b.toolbar.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        searchToken++
        actionMode?.finish()
        b.searchInput.removeCallbacks(searchDebounce)
        b.pageLabel.removeCallbacks(hidePageLabel)
        b.strip.release()
    }

    companion object {
        private const val MENU_COPY = 1

        /** How long the page counter and scrollbar stay up after movement stops. */
        private const val CHROME_HIDE_MS = 1000L

        /** Typing settle time before a query is run. */
        private const val SEARCH_DEBOUNCE_MS = 350L

        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"

        fun start(context: Context, file: XFile) {
            context.startActivity(
                Intent(context, PdfViewerActivity::class.java).apply {
                    putExtra(EXTRA_SCHEME, file.scheme)
                    putExtra(EXTRA_PATH, file.path)
                    putExtra(EXTRA_NAME, file.name)
                },
            )
        }
    }
}
