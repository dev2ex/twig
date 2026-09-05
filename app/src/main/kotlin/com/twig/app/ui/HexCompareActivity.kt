package com.twig.app.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Format
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ActivityHexCompareBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Byte-level comparison of two files: two [HexPane]s scrolling as one, with the differing bytes
 * highlighted and a "previous / next difference" navigator.
 *
 * This is the only thing the compare page can offer for binaries — [DiffActivity] refuses them
 * outright (`isBinary` → `diff_binary`), which used to be a dead end.
 *
 * ★ **Both columns are forced onto the same bytes-per-row and the same offset-digit count**
 * ([relayout]): each side would otherwise measure its own — a 500-byte file wants 4 offset digits
 * where a 5 GB one wants 10, and the rows would then hold different byte counts. The moment that
 * happens the two offset columns disagree and comparing "the same row on both sides" is a lie.
 * So: digits from the larger file, bytes-per-row from the narrower proposal.
 *
 * Scrolling is synchronised by **row index plus pixel offset** rather than by passing dy along:
 * with equal row heights on both sides that is exact, and it doesn't drift when one file is
 * shorter and its side hits the bottom first.
 *
 * The diff scan reads both files sequentially, once ([HexDiff.scan]); display keeps using
 * [HexSource]'s random-access chunks, so a 10 GB pair still renders the screen you're looking at
 * without waiting for the scan.
 */
class HexCompareActivity : AppCompatActivity() {

    private lateinit var b: ActivityHexCompareBinding
    private lateinit var paneA: HexPane
    private lateinit var paneB: HexPane
    private var fileA: XFile? = null
    private var fileB: XFile? = null

    private var ranges: List<DiffRange> = emptyList()
    private var cur = -1
    private var truncated = false
    private var scanJob: Job? = null
    private var pendingRelayout = false

    /** Which list the user is actually dragging; the other one is being pushed and must not push back. */
    private var syncSource: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHexCompareBinding.inflate(layoutInflater)
        setContentView(b.root)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        b.toolbar.title = title
        b.toolbar.setNavigationOnClickListener { finish() }
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(title))
        buildMenu()
        applyOrientation()

        val a = fileFrom(EXTRA_A_SCHEME, EXTRA_A_PATH, EXTRA_A_SIZE, EXTRA_A_TIME)
        val c = fileFrom(EXTRA_B_SCHEME, EXTRA_B_PATH, EXTRA_B_SIZE, EXTRA_B_TIME)
        if (a == null || c == null) return finish()
        fileA = a
        fileB = c

        val sp = Prefs.hexTextSize(this)
        paneA = makePane(b.listA, sp)
        paneB = makePane(b.listB, sp)
        applyTheme()
        wireSync(b.listA, paneA, paneB)
        wireSync(b.listB, paneB, paneA)
        wirePinchZoom(b.listA)
        wirePinchZoom(b.listB)
        wireFastScroll()
        b.diffPrev.setOnClickListener { move(-1) }
        b.diffNext.setOnClickListener { move(1) }
        // Same reasoning as the viewer: recompute the row width after a size change, and post out of
        // the layout pass before touching the adapter.
        b.listA.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol || pendingRelayout) {
                pendingRelayout = false
                b.listA.post { relayout(force = true) }
            }
        }

        updateInfo(a.size, c.size)
        open(a, c)
    }

    override fun onDestroy() {
        paneA.close()
        paneB.close()
        super.onDestroy()
    }

    private fun makePane(list: RecyclerView, sp: Float) = HexPane(list, lifecycleScope).apply {
        setTextSize(sp)
        hits = { off, len -> HexDiff.hitsInRow(ranges, off, len, cur) }
        onRowLongClick = { pos -> copyRow(this, pos) }
    }

    private fun fileFrom(schemeKey: String, pathKey: String, sizeKey: String, timeKey: String): XFile? {
        val scheme = intent.getStringExtra(schemeKey) ?: return null
        val path = intent.getStringExtra(pathKey) ?: return null
        return XFile(
            scheme, path, isDir = false,
            size = intent.getLongExtra(sizeKey, 0L),
            lastModified = intent.getLongExtra(timeKey, 0L),
        )
    }

    /**
     * Info bar per side: `left · 2.3 MB · time`. Sizes are re-stated once the sources are open,
     * because an [XFile] assembled from an Intent often has no size — and when arriving from
     * [DiffActivity] it has no timestamp either, so the time is dropped rather than printed as 1970.
     */
    private fun updateInfo(sizeA: Long, sizeB: Long) {
        b.infoA.text = sideInfo(getString(R.string.compare_side_left), sizeA, fileA?.lastModified ?: 0L)
        b.infoB.text = sideInfo(getString(R.string.compare_side_right), sizeB, fileB?.lastModified ?: 0L)
        b.toolbar.subtitle = "${Format.size(sizeA)} ↔ ${Format.size(sizeB)}"
    }

    private fun sideInfo(side: String, size: Long, time: Long): String {
        val head = "$side · ${Format.size(size)}"
        return if (time > 0) "$head · ${Format.time(time)}" else head
    }

    // ---- load ----

    private fun open(a: XFile, c: XFile) {
        b.loading.visibility = View.VISIBLE
        b.diffStatus.setText(R.string.hex_cmp_opening)
        lifecycleScope.launch {
            val r = runCatching {
                paneA.open(a, a.size)
                paneB.open(c, c.size)
            }
            b.loading.visibility = View.GONE
            r.fold(
                onSuccess = {
                    relayout(force = true)
                    updateInfo(paneA.size, paneB.size)
                    startScan(a, c)
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

    // ---- diff scan ----

    private fun startScan(a: XFile, c: XFile) {
        scanJob?.cancel()
        // ★ Lengths from the opened sources, never from the XFile: an XFile rebuilt out of an Intent
        // routinely has size 0, which would make the progress percentage and the "one side is longer"
        // tail range both nonsense.
        val sizeA = paneA.size
        val sizeB = paneB.size
        val total = minOf(sizeA, sizeB).coerceAtLeast(1L)
        b.diffStatus.text = getString(R.string.hex_cmp_scanning, 0)
        scanJob = lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                val active = { coroutineContext.isActive }
                runCatching {
                    FsRegistry.of(a).openInput(a).use { ia ->
                        FsRegistry.of(c).openInput(c).use { ib ->
                            HexDiff.scan(ia, ib, sizeA, sizeB, active) { done ->
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                launch(Dispatchers.Main) {
                                    b.diffStatus.text = getString(R.string.hex_cmp_scanning, pct)
                                }
                            }
                        }
                    }
                }.getOrElse { return@withContext null }
            }
            if (res == null) {
                b.diffStatus.setText(R.string.hex_cmp_scan_failed)
                return@launch
            }
            if (res.cancelled) return@launch
            ranges = res.ranges
            truncated = res.truncated
            cur = -1
            syncStatus()
            // Land on the first difference rather than leaving the user at offset 0 of two files
            // that may only differ 4 GB in.
            if (ranges.isNotEmpty()) jumpTo(0) else refreshBoth()
        }
    }

    private fun syncStatus() {
        b.diffStatus.text = when {
            ranges.isEmpty() -> getString(R.string.hex_cmp_identical)
            truncated -> getString(R.string.hex_cmp_count_more, ranges.size)
            else -> getString(R.string.hex_cmp_count, ranges.size)
        }
        syncPos()
    }

    private fun syncPos() {
        b.diffPos.text = if (cur >= 0 && ranges.isNotEmpty()) "${cur + 1}/${ranges.size}" else ""
    }

    private fun move(delta: Int) {
        if (ranges.isEmpty()) return
        jumpTo((cur + delta + ranges.size) % ranges.size)
    }

    private fun jumpTo(index: Int) {
        cur = index
        syncPos()
        val off = ranges[index].start
        val y = b.listA.height / 3
        paneA.scrollToOffset(off, y)
        paneB.scrollToOffset(off, y)
        b.listA.post {
            refreshBoth()
            syncBar()
        }
    }

    private fun refreshBoth() {
        paneA.refreshVisible()
        paneB.refreshVisible()
    }

    // ---- layout ----

    /** ★ One row width and one offset width for both sides — see the class comment. */
    private fun relayout(force: Boolean) {
        if (paneA.src == null || paneB.src == null) return
        val digits = HexLayout.offsetDigits(maxOf(paneA.size, paneB.size))
        val ba = paneA.computeBpr(digits)
        val bb = paneB.computeBpr(digits)
        if (ba == 0 || bb == 0) {
            pendingRelayout = true
            return
        }
        val bpr = minOf(ba, bb)
        val ca = paneA.applyLayout(bpr, digits, force)
        val cb = paneB.applyLayout(bpr, digits, force)
        // Both columns get the longer file's row count, the shorter one padded with blank rows —
        // otherwise its side bottoms out early and drags the other back up (see [HexPane.padTo]).
        val rows = maxOf(paneA.rowCount, paneB.rowCount)
        paneA.padTo(rows)
        paneB.padTo(rows)
        if (ca || cb) b.listA.post { syncBar() }
    }

    private fun applyTextSize(sp: Float, persist: Boolean) {
        val v = (sp.coerceIn(MIN_SP, MAX_SP) * 2).roundToInt() / 2f
        val changed = paneA.setTextSize(v) or paneB.setTextSize(v)
        if (changed) relayout(force = true)
        if (persist) Prefs.setHexTextSize(this, paneA.textSp)
    }

    private fun wirePinchZoom(list: RecyclerView) {
        val detector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    applyTextSize(paneA.textSp * d.scaleFactor, persist = false)
                    return true
                }

                override fun onScaleEnd(d: ScaleGestureDetector) {
                    Prefs.setHexTextSize(this@HexCompareActivity, paneA.textSp)
                }
            },
        )
        list.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
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

    // ---- scroll sync ----

    /**
     * Mirror [self]'s position onto [other]. The [syncSource] latch is what keeps the two from
     * pushing each other: while a list is being scrolled it owns the sync, and the pushed side's
     * own callback is ignored until the drag settles.
     */
    private fun wireSync(list: RecyclerView, self: HexPane, other: HexPane) {
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    if (syncSource === rv) syncSource = null
                } else {
                    syncSource = rv
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (syncSource != null && syncSource !== rv) return
                val row = self.firstVisibleRow()
                if (row >= 0) other.scrollToRow(row, self.firstVisibleTop())
                if (!b.fastscroll.dragging) syncBar()
            }
        })
    }

    // ---- fast scroll bar ----

    /** Both panes are padded to the same count in [relayout], so either one answers this. */
    private fun rowCount() = maxOf(paneA.rowCount, paneB.rowCount)

    private fun wireFastScroll() {
        b.fastscroll.onDrag = { f, ended ->
            val rows = rowCount()
            if (rows > 1) {
                val row = (maxFirstRow() * f).roundToInt().coerceIn(0, rows - 1)
                paneA.scrollToRow(row)
                paneB.scrollToRow(row)
                showDragHint(row.toLong() * paneA.bpr, !ended)
            }
        }
    }

    private fun maxFirstRow(): Int {
        val first = paneA.firstVisibleRow()
        val last = paneA.lastVisibleRow()
        val onScreen = if (first in 0..last) last - first + 1 else 1
        return (rowCount() - onScreen).coerceAtLeast(1)
    }

    private fun syncBar() {
        val first = paneA.firstVisibleRow()
        val last = paneA.lastVisibleRow()
        val onScreen = if (first in 0..last) last - first + 1 else 0
        val rows = rowCount()
        b.fastscroll.isEnabled = rows > onScreen && rows > 1
        b.fastscroll.invalidate()
        if (first >= 0 && rows > 1) b.fastscroll.fraction = first.toFloat() / maxFirstRow()
    }

    private fun showDragHint(off: Long, show: Boolean) {
        b.dragHint.visibility = if (show) View.VISIBLE else View.GONE
        if (show) b.dragHint.text = String.format(Locale.US, "%0${paneA.offDigits}X", off)
    }

    // ---- menu / theme / orientation ----

    private fun buildMenu() {
        b.toolbar.menu.add(R.string.viewer_text_bigger).setOnMenuItemClickListener {
            applyTextSize(paneA.textSp + 1f, persist = true); true
        }
        b.toolbar.menu.add(R.string.viewer_text_smaller).setOnMenuItemClickListener {
            applyTextSize(paneA.textSp - 1f, persist = true); true
        }
        b.toolbar.menu.add(R.string.viewer_theme).setOnMenuItemClickListener { pickTheme(); true }
    }

    private fun theme(): CodeHighlighter.Theme =
        CodeHighlighter.THEMES[Prefs.codeTheme(this).coerceIn(0, CodeHighlighter.THEMES.size - 1)]

    private fun applyTheme() {
        val t = theme()
        paneA.theme = t
        paneB.theme = t
        b.listA.setBackgroundColor(t.bg)
        b.listB.setBackgroundColor(t.bg)
        NavBarTint.apply(this, t.bg) // the bottom-most layer here is the hex body, same as the viewer
    }

    private fun pickTheme() {
        val names = CodeHighlighter.THEMES.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_theme)
            .setSingleChoiceItems(names, Prefs.codeTheme(this).coerceIn(0, names.size - 1)) { d, i ->
                Prefs.setCodeTheme(this, i)
                applyTheme()
                paneA.refreshAll()
                paneB.refreshAll()
                d.dismiss()
            }
            .show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    private fun applyOrientation() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        b.panes.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (side in listOf(b.sideA, b.sideB)) {
            side.layoutParams = LinearLayout.LayoutParams(
                if (land) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                if (land) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                1f,
            )
        }
        b.divider.layoutParams = LinearLayout.LayoutParams(
            if (land) dp(1) else LinearLayout.LayoutParams.MATCH_PARENT,
            if (land) LinearLayout.LayoutParams.MATCH_PARENT else dp(1),
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun copyRow(pane: HexPane, pos: Int) {
        val line = pane.rowText(pos) ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hex", line))
        Toast.makeText(this, R.string.info_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_A_SCHEME = "a_scheme"
        private const val EXTRA_A_PATH = "a_path"
        private const val EXTRA_A_SIZE = "a_size"
        private const val EXTRA_A_TIME = "a_time"
        private const val EXTRA_B_SCHEME = "b_scheme"
        private const val EXTRA_B_PATH = "b_path"
        private const val EXTRA_B_SIZE = "b_size"
        private const val EXTRA_B_TIME = "b_time"
        private const val EXTRA_TITLE = "title"
        private const val MIN_SP = 7f
        private const val MAX_SP = 28f

        fun start(ctx: Context, left: XFile, right: XFile, title: String) {
            ctx.startActivity(
                Intent(ctx, HexCompareActivity::class.java)
                    .putExtra(EXTRA_A_SCHEME, left.scheme)
                    .putExtra(EXTRA_A_PATH, left.path)
                    .putExtra(EXTRA_A_SIZE, left.size)
                    .putExtra(EXTRA_A_TIME, left.lastModified)
                    .putExtra(EXTRA_B_SCHEME, right.scheme)
                    .putExtra(EXTRA_B_PATH, right.path)
                    .putExtra(EXTRA_B_SIZE, right.size)
                    .putExtra(EXTRA_B_TIME, right.lastModified)
                    .putExtra(EXTRA_TITLE, title),
            )
        }
    }
}
