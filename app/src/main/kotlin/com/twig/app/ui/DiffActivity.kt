package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Format
import com.twig.app.GitFileSystem
import com.twig.app.OpenFiles
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ActivityDiffBinding
import com.twig.app.databinding.ItemDiffLineBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.git.Diff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Which two versions each side of a diff corresponds to (matches the values of
 * `GitVfs.diffSides`). Even though both sides are called "old" and "new", staged
 * compares HEAD↔index and unstaged compares index↔worktree — without spelling that out,
 * it is impossible to tell which two versions this diff actually compared. `untracked`
 * is a newly-added file, so the old side does not exist; we give the empty string.
 */
internal fun gitSideSources(ctx: android.content.Context, p: String): Pair<String, String>? = when {
    p.startsWith("/changes/") -> when (p.removePrefix("/changes/").substringBefore('/')) {
        "staged" -> "HEAD" to ctx.getString(R.string.git_src_index)
        "unstaged" -> ctx.getString(R.string.git_src_index) to ctx.getString(R.string.git_src_work)
        "untracked" -> "" to ctx.getString(R.string.git_src_work)
        else -> null
    }
    // Parent commit ↔ this commit; the short SHA is enough to recognize, the full 40 chars would take up too much space in the title
    p.startsWith("/history/") ->
        p.removePrefix("/history/").substringBefore('/').take(7)
            .takeIf { it.isNotEmpty() }?.let { "$it^" to it }
    else -> null
}

/**
 * git virtual path → repo-relative path: `/changes/<group>/a/b.kt` and
 * `/history/<sha>/a/b.kt` both yield `a/b.kt` (the segment after the prefix is
 * GitVfs's group name / commit SHA, not a path inside the repo).
 */
internal fun gitRelPath(p: String): String =
    p.removePrefix("/changes/").removePrefix("/history/").substringAfter('/', "")

/**
 * Fixed configuration for diff-line text. Extracted into a function so it can be pinned
 * down in a unit test (see `DiffLineLayoutTest`) — drop any one of these three and
 * "long lines can't be fully seen" will come back in a different form.
 */
internal fun configureDiffLineText(tv: android.widget.TextView) {
    // ★ Ellipsizing can only be turned off here. XML's `ellipsize="none"` is the same as
    // "not set", and the TextView constructor has
    // `if (singleLine && keyListener == null && ellipsize not set) ellipsize = END`
    // — **a read-only single-line TextView defaults to ellipsizing at the end**.
    // Layout itself is infinitely wide (horizontal scrolling works), but drawing is
    // cut off by the ellipsis, which manifests as "you can scroll but can never see
    // what's after".
    tv.ellipsize = null
    // Horizontal scrolling requires "infinitely wide" layout; otherwise the part that
    // exceeds the view's width does not participate in layout at all, and scrollTo only
    // scrolls out a blank area. `singleLine="true"` happens to enable this internally,
    // but that is a side effect — write it explicitly so switching to `maxLines="1"`
    // (which does not enable horizontal scrolling) does not silently break it.
    tv.setHorizontallyScrolling(true)
    // Long-press to select and copy a fragment (rather than being limited to copying
    // whole lines). The cost is that each TextView gets an extra Editor and becomes
    // focusable/longClickable; horizontal and vertical scrolling are intercepted first
    // by the RecyclerView-level OnItemTouchListener at higher priority and will not
    // be hijacked.
    tv.setTextIsSelectable(true)
}

/**
 * Two-pane diff view (old on the left, new on the right): landscape side-by-side with
 * synchronized scrolling; portrait shows one side at a time, switched via the toolbar
 * button.
 *
 * **Horizontal swipe scrolls long lines horizontally, not switching sides** — code lines
 * routinely exceed screen width, and [ItemDiffLineBinding]'s text is single-line without
 * wrapping (wrapping would break alignment between the two sides and make the diff
 * unreadable), so the only way to see the end of a line is horizontal scrolling. The two
 * sides share a single [hScroll] — otherwise the offset between them would also break
 * alignment; the line-number column does not scroll with it and stays pinned to the left.
 */
class DiffActivity : AppCompatActivity() {

    private lateinit var b: ActivityDiffBinding
    private var rows: List<Diff.Row> = emptyList()
    private var side = 1 // Currently displayed side in portrait: 0=old, 1=new (default to the new version)
    /** Comparing any two files (entered from the directory-compare page), rather than git's old/new versions. */
    private var pairMode = false
    /** Source for each side, used to write the full path on the title bar; in git mode both sides are two versions of the same file, so only the left has a value. */
    private var fileLeft: XFile? = null
    private var fileRight: XFile? = null
    private var itemSwap: MenuItem? = null
    private var itemStack: MenuItem? = null
    /** Top-and-bottom two panes (rather than left-and-right side-by-side / portrait single-side switching); stored in [Prefs], so the next open is the same. */
    private var stacked = false
    private var syncing = false
    /** Shared horizontal scroll amount (px) between the two panes. */
    private var hScroll = 0
    /** Pixel width of the longest line; measured on a background thread before being filled in; horizontal scrolling is disabled before that is done (otherwise the bounds are unknown). */
    private var maxLineWidth = 0f
    private var hFling: android.animation.ValueAnimator? = null
    /** Pre-highlighted lines for the whole side, keyed by line number (see [highlightLines]); null = that side is not highlighted. */
    private var hlLeft: List<CharSequence>? = null
    private var hlRight: List<CharSequence>? = null
    /** Starting row index of each difference block (a run of consecutive changed lines); [blockIdx] is the current block. */
    private var blocks: List<Int> = emptyList()
    private var blockIdx = -1
    private var statBase = ""
    /** Non-null = the theme currently in effect for highlighting; SideAdapter uses it as the fallback color for characters not covered by a token. */
    private var hlTheme: CodeHighlighter.Theme? = null

    // ---- Merge state (moving a block to the other side; see [mergeBlock]) ----
    /** Current line sequences on both sides; merge operations mutate these, and [rows] is recomputed from them each time. */
    private var linesLeft: List<String> = emptyList()
    private var linesRight: List<String> = emptyList()
    /** Whether the original file on each side had a trailing newline — the only piece of information lost when splitting into lines, and which must be restored verbatim on write-back. */
    private var nlLeft = false
    private var nlRight = false
    /** Content at open time; comparing against the current line sequence tells you whether each side has unsaved changes. */
    private var baseLeft: List<String> = emptyList()
    private var baseRight: List<String> = emptyList()
    /** Whether merging is allowed; when it is not, [mergeBlocked] is the reason (toasted when an arrow is tapped); null = this whole thing does not apply. */
    private var mergeable = false
    private var mergeBlocked: String? = null
    /** Whether each side is writable. When only one side is read-only (e.g. the other side is an entry inside an archive), merging in the other direction still works. */
    private var writableLeft = false
    private var writableRight = false
    /** Snapshots of both sides before each merge; undo pops one layer. */
    private val undoStack = ArrayList<Pair<List<String>, List<String>>>()
    private var saving = false
    private var lang: CodeHighlighter.Lang? = null
    private var fileTitle = ""
    private var itemSave: MenuItem? = null
    private var itemUndo: MenuItem? = null

    private val dirtyLeft get() = linesLeft != baseLeft
    private val dirtyRight get() = linesRight != baseRight

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDiffBinding.inflate(layoutInflater)
        setContentView(b.root)

        val scheme = intent.getStringExtra(EXTRA_SCHEME) ?: return finish()
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: path
        fileTitle = title
        b.toolbar.title = title
        b.toolbar.setNavigationOnClickListener { onBackPressed() }
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(title))
        stacked = Prefs.diffStacked(this)
        itemStack = b.toolbar.menu.add(getString(R.string.diff_layout_stack)).apply {
            setIcon(R.drawable.ic_layout_rows)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                stacked = !stacked
                Prefs.setDiffStacked(this@DiffActivity, stacked)
                applyLayoutMode()
                // After switching layout, a line's visible width has changed (left/right side-by-side
                // is half a screen, top/bottom side-by-side is the full screen), so the
                // original horizontal position may have gone out of range
                b.listLeft.post { setHScroll(hScroll) }
                true
            }
        }
        itemSwap = b.toolbar.menu.add(getString(R.string.compare_switch_side)).apply {
            setIcon(R.drawable.ic_pane_to_right)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { switchSide(1 - side); true }
        }
        b.toolbar.menu.add(getString(R.string.diff_prev)).apply {
            setIcon(R.drawable.ic_diff_prev)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { jump(-1); true }
        }
        b.toolbar.menu.add(getString(R.string.diff_next)).apply {
            setIcon(R.drawable.ic_diff_next)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { jump(1); true }
        }
        itemSave = b.toolbar.menu.add(getString(R.string.viewer_save)).apply {
            setIcon(R.drawable.ic_save)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false // Appears only when there are unsaved changes
            setOnMenuItemClickListener { save(); true }
        }
        itemUndo = b.toolbar.menu.add(getString(R.string.diff_undo)).apply {
            isVisible = false
            setOnMenuItemClickListener { undo(); true }
        }
        b.toolbar.menu.showIcons() // Even when "Undo" overflows into the overflow menu, it still gets an icon

        // The two merge buttons do not go in the toolbar — it is cramped, and too far from the
        // line of content being merged to be intuitive. Instead, a floating pill is
        // attached next to the current diff block (see updateMergePill); here we just
        // wire up the click behavior.
        b.btnMergeA.setOnClickListener { mergeBlock(toRight = false) }
        b.btnMergeB.setOnClickListener { mergeBlock(toRight = true) }

        b.listLeft.layoutManager = LinearLayoutManager(this)
        b.listRight.layoutManager = LinearLayoutManager(this)
        linkScroll(b.listLeft, b.listRight)
        linkScroll(b.listRight, b.listLeft)
        installHScroll(b.listLeft)
        installHScroll(b.listRight)
        trackMergePill(b.listLeft)
        trackMergePill(b.listRight)

        val rightScheme = intent.getStringExtra(EXTRA_R_SCHEME)
        val rightPath = intent.getStringExtra(EXTRA_R_PATH)
        pairMode = rightScheme != null && rightPath != null
        fileLeft = XFile(scheme, path, isDir = false)
        fileRight = if (pairMode) XFile(rightScheme!!, rightPath!!, isDir = false) else null
        // The side on the compare page that was tapped decides which side is shown first in
        // portrait — if you tapped the left copy, of course you want to see the left first
        if (pairMode) side = intent.getIntExtra(EXTRA_SIDE, 0)
        applyLayoutMode()

        val tooBig = pairMode && maxOf(
            intent.getLongExtra(EXTRA_L_SIZE, 0L),
            intent.getLongExtra(EXTRA_R_SIZE, 0L),
        ) > PAIR_MAX_BYTES

        lifecycleScope.launch {
            if (tooBig) {
                b.loading.visibility = View.GONE
                // Too large to hold two copies in memory and diff by line — but the hex page streams,
                // so it can compare a pair of any size. Hand over instead of dead-ending.
                if (toHexCompare()) return@launch
                b.tvEmpty.text = getString(R.string.diff_too_big)
                b.tvEmpty.visibility = View.VISIBLE
                return@launch
            }
            val sides = withContext(Dispatchers.IO) {
                runCatching {
                    if (pairMode) {
                        readSide(XFile(scheme, path, isDir = false)) to
                            readSide(XFile(rightScheme!!, rightPath!!, isDir = false))
                    } else {
                        (FsRegistry.of(scheme) as? GitFileSystem)?.diffSides(path)
                    }
                }.getOrNull()
            }
            b.loading.visibility = View.GONE
            if (sides == null) {
                b.tvEmpty.text = getString(R.string.diff_unavailable)
                b.tvEmpty.visibility = View.VISIBLE
                return@launch
            }
            val (old, new) = sides
            if (isBinary(old) || isBinary(new)) {
                // Whether a file is binary is only knowable after reading it, so the compare page
                // cannot route around this one — the decision is made here and the page swaps itself
                // out for the byte-level comparison.
                if (toHexCompare()) return@launch
                b.tvEmpty.text = getString(R.string.diff_binary)
                b.tvEmpty.visibility = View.VISIBLE
                return@launch
            }
            if (pairMode) {
                // Only when both sides are real files does "move it across and write it back" make sense.
                // On the git side the left is a virtual version like HEAD / index, where
                // "write back" means stage / checkout — that's a different story.
                val l = fileLeft!!
                val r = fileRight!!
                val w = withContext(Dispatchers.IO) { canWriteTo(l) to canWriteTo(r) }
                writableLeft = w.first
                writableRight = w.second
                mergeable = writableLeft || writableRight
                // Reading side is locked to UTF-8: a file that is not valid UTF-8 already
                // displays as mojibake, and writing it back would permanently destroy the
                // original bytes. Still show the button; tapping it toasts the reason
                // (same as the text editor).
                mergeBlocked = if (strictUtf8(old ?: ByteArray(0)) == null ||
                    strictUtf8(new ?: ByteArray(0)) == null
                ) {
                    getString(R.string.diff_merge_blocked_encoding)
                } else {
                    null
                }
            }
            lang = CodeHighlighter.langFor(title)
            val oldStr = old?.toString(Charsets.UTF_8) ?: ""
            val newStr = new?.toString(Charsets.UTF_8) ?: ""
            nlLeft = oldStr.endsWith("\n")
            nlRight = newStr.endsWith("\n")
            linesLeft = toLines(oldStr)
            linesRight = toLines(newStr)
            baseLeft = linesLeft
            baseRight = linesRight
            render(first = true)
        }
    }

    /**
     * Compute [rows] / highlighting / difference blocks from the current line sequences
     * on both sides and lay them out on the UI. Run again after a merge — once you merge
     * away one block, every later block's row index shifts, so without a full recompute
     * they would point at the wrong rows.
     */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private suspend fun render(first: Boolean) {
        val theme = hlTheme ?: diffTheme()
        val l = lang
        val lText = joinLines(linesLeft, nlLeft)
        val rText = joinLines(linesRight, nlRight)
        withContext(Dispatchers.Default) {
            rows = Diff.rows(linesLeft, linesRight)
            hlLeft = l?.let { highlightLines(lText, it, theme) }
            hlRight = l?.let { highlightLines(rText, it, theme) }
        }
        if (l != null) {
            // The whole side's background is swapped to the theme's bg; otherwise the dark
            // theme's light foreground text sits on the system's default light list
            // background and becomes unreadable — previously we worked around this by
            // forcing a fallback to the light theme; now the background follows the
            // theme and the dark theme works correctly too.
            hlTheme = theme
            b.listLeft.setBackgroundColor(theme.bg)
            b.listRight.setBackgroundColor(theme.bg)
        }
        val dels = rows.count { it.changed && it.left != null }
        val adds = rows.count { it.changed && it.right != null }
        statBase = "+$adds −$dels"
        blocks = rows.indices.filter { rows[it].changed && (it == 0 || !rows[it - 1].changed) }
        blockIdx = blockIdx.coerceIn(-1, blocks.size - 1)
        refreshSubtitle()
        if (first) {
            b.listLeft.adapter = SideAdapter(left = true)
            b.listRight.adapter = SideAdapter(left = false)
        } else {
            // Re-bind the whole table: merge adds or removes whole row ranges, so
            // positions no longer line up; a partial refresh via payload has no meaning
            b.listLeft.adapter?.notifyDataSetChanged()
            b.listRight.adapter?.notifyDataSetChanged()
        }
        b.tvEmpty.text = getString(R.string.diff_identical)
        b.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        refreshSideTitles()
        refreshToolbar()
        updateMergePill() // Rows have not been laid out yet — usually this just hides; setBlock's post will reposition
        measureMaxLineWidth() // Horizontal scrolling's bounds depend on this; before it finishes, horizontal scrolling is naturally disabled
        // On open, jump to the first difference (wait for layout so we get the viewport height)
        if (first && blocks.isNotEmpty()) b.listRight.post { setBlock(0) }
    }

    // ---- Merge differences ----

    /**
     * Move the entire current diff block over to the other side. **Memory-only**;
     * writing to disk requires an explicit save: when merging one block at a time,
     * writing back after every merge is slow (especially on network sources) and
     * there is no way to undo.
     */
    private fun mergeBlock(toRight: Boolean) {
        if (saving) return
        mergeBlocked?.let { toast(it); return }
        if (blockIdx < 0 || blockIdx >= blocks.size) return
        val range = blockRange(rows, blocks[blockIdx])
        undoStack.add(linesLeft to linesRight)
        if (toRight) linesRight = mergedLines(rows, range, toRight = true)
        else linesLeft = mergedLines(rows, range, toRight = false)
        rerender()
    }

    private fun undo() {
        if (saving) return
        val prev = undoStack.removeLastOrNull() ?: return
        linesLeft = prev.first
        linesRight = prev.second
        rerender()
    }

    /**
     * Re-render and stay on the same position. Once the current block is merged away, it
     * no longer exists, and the same index sliding forward points exactly at the **next**
     * difference — which is precisely where you would want to keep merging, without having
     * to jump manually again.
     */
    private fun rerender() {
        lifecycleScope.launch {
            render(first = false)
            if (blockIdx >= 0) setBlock(blockIdx)
        }
    }

    private fun save(exitAfter: Boolean = false) {
        if (saving) return
        val dl = dirtyLeft
        val dr = dirtyRight
        if (!dl && !dr) {
            if (exitAfter) finish()
            return
        }
        val snapL = linesLeft
        val snapR = linesRight
        val bytesL = joinLines(snapL, nlLeft).toByteArray(Charsets.UTF_8)
        val bytesR = joinLines(snapR, nlRight).toByteArray(Charsets.UTF_8)
        saving = true
        b.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            var err: Throwable? = null
            var okL = false
            var okR = false
            withContext(Dispatchers.IO) {
                if (dl) {
                    runCatching { writeAtomically(fileLeft!!, bytesL) }
                        .onSuccess { okL = true }.onFailure { err = err ?: it }
                }
                if (dr) {
                    runCatching { writeAtomically(fileRight!!, bytesR) }
                        .onSuccess { okR = true }.onFailure { err = err ?: it }
                }
            }
            saving = false
            b.loading.visibility = View.GONE
            // Acknowledge per side: when one side's save succeeded and the other side's failed,
            // the successful side must not still be marked as "unsaved", otherwise tapping
            // Save again would write it back once more
            if (okL) baseLeft = snapL
            if (okR) baseRight = snapR
            if (okL || okR) setResult(RESULT_OK) // The compare page uses this to reclassify this pair locally, not to rescan the whole tree
            refreshToolbar()
            val e = err
            if (e == null) {
                toast(getString(R.string.viewer_saved))
                if (exitAfter) finish()
            } else {
                AlertDialog.Builder(this@DiffActivity)
                    .setTitle(R.string.viewer_save_failed_title)
                    .setMessage(getString(R.string.viewer_save_failed, e.message ?: e.toString()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (saving) return // A save is in progress; do not let the Activity escape
        if (!dirtyLeft && !dirtyRight) return finish()
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_discard_title)
            .setMessage(R.string.viewer_discard_msg)
            .setPositiveButton(R.string.viewer_save) { _, _ -> save(exitAfter = true) }
            .setNegativeButton(R.string.viewer_discard_ok) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    /** Toolbar items tied to merge / save state: save button visibility, undo visibility, and the `*` in the title. */
    private fun refreshToolbar() {
        itemSave?.isVisible = dirtyLeft || dirtyRight
        itemUndo?.isVisible = undoStack.isNotEmpty()
        itemUndo?.icon = tinted(R.drawable.ic_undo, R.color.text_primary)
        // A `*` in the title is a persistent indicator of "unsaved changes" — even when
        // the save icon is squeezed off the narrow screen, it remains visible
        b.toolbar.title = if (dirtyLeft || dirtyRight) "*$fileTitle" else fileTitle
    }

    /**
     * Floating merge pill: position sticks to the currently located diff block
     * ([blockIdx]) and moves with scrolling; when that block scrolls out of the viewport,
     * hide it entirely (a position without meaning is worse than no position).
     *
     * - **Side-by-side left/right** (landscape, or in the top/bottom layout): the pill
     *   sits on the divider between the two panes, with y taken from that row's position
     *   in [b.listLeft] — both panes have strictly matching row heights and synchronized
     *   scrolling, so either pane's y is identical.
     * - **Top/bottom side-by-side**: the divider is horizontal and offers no meaningful x
     *   reference, so fall back to horizontally centered; the two panes each occupy half
     *   the screen, with y preferring the top pane ([b.listLeft]), falling back to the
     *   bottom pane only when the row has scrolled out of the top (still visible in the
     *   bottom).
     * - **Portrait single-side switching**: same as above, fall back to centered; y is
     *   taken from whichever side is currently displayed.
     */
    private fun updateMergePill() {
        if (!mergeable || blockIdx !in blocks.indices) {
            b.mergePill.visibility = View.GONE
            return
        }
        b.btnMergeA.visibility = if (writableLeft) View.VISIBLE else View.GONE
        b.btnMergeB.visibility = if (writableRight) View.VISIBLE else View.GONE
        b.btnMergeA.setImageDrawable(tinted(if (stacked) R.drawable.ic_merge_up else R.drawable.ic_merge_left, R.color.white))
        b.btnMergeB.setImageDrawable(tinted(if (stacked) R.drawable.ic_merge_down else R.drawable.ic_merge_right, R.color.white))
        b.btnMergeA.contentDescription = getString(if (stacked) R.string.diff_merge_up else R.string.diff_merge_left)
        b.btnMergeB.contentDescription = getString(if (stacked) R.string.diff_merge_down else R.string.diff_merge_right)

        val row = blocks[blockIdx]
        val sideBySide = !stacked && isLandscape
        val y = if (stacked) {
            rowCenterY(b.listLeft, row) ?: rowCenterY(b.listRight, row)
        } else {
            rowCenterY(if (!isLandscape && side == 1) b.listRight else b.listLeft, row)
        }
        if (y == null) {
            b.mergePill.visibility = View.GONE
            return
        }
        b.mergePill.visibility = View.VISIBLE
        b.mergePill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        b.mergePill.x = if (sideBySide) {
            locationWithin(b.divider, b.overlayHost).first - b.mergePill.measuredWidth / 2f
        } else {
            (b.overlayHost.width - b.mergePill.measuredWidth) / 2f
        }
        b.mergePill.y = y - b.mergePill.measuredHeight / 2f
    }

    /**
     * The current vertical center of row [row] inside [rv], translated into the coordinate
     * system of [b.overlayHost]; returns null when that row has not been laid out
     * (scrolled out of the viewport).
     */
    private fun rowCenterY(rv: RecyclerView, row: Int): Float? {
        val child = (rv.layoutManager as LinearLayoutManager).findViewByPosition(row) ?: return null
        val (_, top) = locationWithin(child, b.overlayHost)
        return top + child.height / 2f
    }

    /** Coordinates of [view]'s top-left relative to [container] (they may not share a parent chain; convert via screen coordinates). */
    private fun locationWithin(view: View, container: View): Pair<Float, Float> {
        val a = IntArray(2)
        val b0 = IntArray(2)
        view.getLocationOnScreen(a)
        container.getLocationOnScreen(b0)
        return (a[0] - b0[0]).toFloat() to (a[1] - b0[1]).toFloat()
    }

    /** Scrolling (including re-layout after an orientation change) must reposition the floating pill. */
    private fun trackMergePill(rv: RecyclerView) {
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) = updateMergePill()
        })
    }

    private fun tinted(res: Int, colorRes: Int) =
        ContextCompat.getDrawable(this, res)?.mutate()?.apply {
            setTint(ContextCompat.getColor(this@DiffActivity, colorRes))
        }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---- Syntax highlighting ----

    /**
     * When the light/dark of the user's chosen [Prefs.codeTheme] clashes with the
     * system's current light/dark mode (e.g. Monokai chosen while the system is in light
     * mode), temporarily substitute the default theme of the matching light/dark, so the
     * diff area does not collide with the toolbar / system status bar and other chrome
     * that still follow the system. If there is no clash, use the user's choice directly.
     * Only affects this display; [Prefs.codeTheme] itself is not changed. The added /
     * removed row backgrounds ([DEL_BG] / [ADD_BG]) are translucent overlays that
     * naturally read as reddish / greenish over any background, so no per-substituted-
     * theme palette adjustment is needed; what truly needs to coordinate is onCreate,
     * which swaps the whole side's background to [CodeHighlighter.Theme.bg].
     */
    private fun diffTheme(): CodeHighlighter.Theme {
        val chosen = CodeHighlighter.THEMES[Prefs.codeTheme(this).coerceIn(0, CodeHighlighter.THEMES.size - 1)]
        val systemDark = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return when {
            systemDark && !isDark(chosen) -> CodeHighlighter.THEMES.first { it.name == "Monokai" }
            !systemDark && isDark(chosen) -> CodeHighlighter.THEMES.first { it.name == "GitHub Light" }
            else -> chosen
        }
    }

    private fun isDark(t: CodeHighlighter.Theme): Boolean {
        val bg = t.bg
        val lum = (
            (bg shr 16 and 0xFF) * 299 + (bg shr 8 and 0xFF) * 587 + (bg and 0xFF) * 114
            ) / 1000
        return lum < 128
    }

    /**
     * Lexically color the whole side's text in one pass (multi-line block-comment /
     * cross-line string states only stay correct this way), then split by line into
     * span-bearing fragments, where the line number is the index. Exceeding
     * [CodeHighlighter.MAX_HIGHLIGHT] skips highlighting.
     */
    private fun highlightLines(
        text: String,
        lang: CodeHighlighter.Lang,
        theme: CodeHighlighter.Theme,
    ): List<CharSequence>? {
        if (text.isEmpty() || text.length > CodeHighlighter.MAX_HIGHLIGHT) return null
        val spanned = CodeHighlighter.render(text, CodeHighlighter.tokenize(text, lang), theme)
        val out = ArrayList<CharSequence>()
        var i = 0
        while (i <= text.length) {
            var j = text.indexOf('\n', i)
            if (j < 0) j = text.length
            out.add(spanned.subSequence(i, j))
            if (j == text.length) break
            i = j + 1
        }
        if (text.endsWith("\n")) out.removeAt(out.size - 1) // Consistent with toLines: trailing newline does not count as a line
        return out
    }

    // ---- Difference-block navigation ----

    private fun jump(dir: Int) {
        if (blocks.isEmpty()) return
        setBlock(((blockIdx + dir) % blocks.size + blocks.size) % blocks.size) // Wraps around
    }

    private fun setBlock(i: Int) {
        blockIdx = i
        val visible = if (!isLandscape && side == 0) b.listLeft else b.listRight
        val off = visible.height / 3
        (b.listLeft.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(blocks[i], off)
        (b.listRight.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(blocks[i], off)
        refreshSubtitle()
        // scrollToPositionWithOffset only schedules the next layout pass; at this very moment
        // the child views are still at their old positions — the floating pill has to wait
        // for layout to settle before knowing where to go, which is why it has to be
        // posted, the same reasoning as waiting for horizontal scroll bounds to be measured
        b.listLeft.post { updateMergePill() }
    }

    /**
     * Replace this page with [HexCompareActivity] for the same pair. Only possible in pair mode: on the
     * git side the left is a virtual version (HEAD / index) with no file behind it to open twice.
     * Returns false when it can't, so the caller falls back to its own message.
     */
    private fun toHexCompare(): Boolean {
        if (!pairMode) return false
        val l = fileLeft ?: return false
        val r = fileRight ?: return false
        HexCompareActivity.start(
            this,
            l.copy(size = intent.getLongExtra(EXTRA_L_SIZE, 0L)),
            r.copy(size = intent.getLongExtra(EXTRA_R_SIZE, 0L)),
            b.toolbar.title?.toString().orEmpty(),
        )
        finish()
        return true
    }

    private fun isBinary(bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        if (bytes.size > 4 shl 20) return true // >4MB, skip line-level diff
        val n = minOf(bytes.size, 8192)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        return false
    }

    // ---- Portrait / landscape layout ----

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyLayoutMode() {
        refreshToolbar()

        b.panes.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val sideBySide = !stacked && isLandscape
        // Top/bottom side-by-side and landscape left/right side-by-side both show both panes;
        // only portrait in the left/right mode shows one side at a time
        b.sideLeft.visibility = if (stacked || sideBySide || side == 0) View.VISIBLE else View.GONE
        b.sideRight.visibility = if (stacked || sideBySide || side == 1) View.VISIBLE else View.GONE
        b.divider.visibility = if (stacked || sideBySide) View.VISIBLE else View.GONE

        // Each pane takes half of the main axis: left/right side-by-side is width,
        // top/bottom side-by-side is height
        for (rv in listOf(b.sideLeft, b.sideRight)) {
            rv.layoutParams = LinearLayout.LayoutParams(
                if (stacked) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                if (stacked) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
        }
        b.divider.layoutParams = LinearLayout.LayoutParams(
            if (stacked) LinearLayout.LayoutParams.MATCH_PARENT else dp(1),
            if (stacked) dp(1) else LinearLayout.LayoutParams.MATCH_PARENT,
        )
        refreshSubtitle()
        // Divider direction and which pane is visible both changed; the floating pill's
        // reference frame follows — wait for this layout pass to settle before repositioning
        b.listLeft.post { updateMergePill() }
    }

    /**
     * Per-pane title: side + path. The two sides' paths usually differ only in a small
     * middle segment, so the file name alone cannot tell them apart, while the toolbar
     * title only carries the file name.
     */
    private fun refreshSideTitles() {
        val l = getString(if (pairMode) R.string.compare_side_left else R.string.diff_old)
        val r = getString(if (pairMode) R.string.compare_side_right else R.string.diff_new)
        if (pairMode) {
            b.titleLeft.text = fileLeft?.let { "$l · ${Format.pathLabel(it)}" } ?: l
            b.titleRight.text = fileRight?.let { "$r · ${Format.pathLabel(it)}" } ?: r
            return
        }
        // ★ In git mode the path is **virtual** (/changes/<group>/…, /history/<sha>/…);
        // applying Format.pathLabel directly would render as "git:/changes/…" — which is
        // neither a path on disk nor meaningful to the user (the prefix segment is just
        // GitVfs's group name / commit SHA). Strip the prefix and keep only the
        // repo-relative path. Write to both panes: even though both sides are versions
        // of the same file with identical paths, only filling one side and leaving the
        // other empty looks like "the right version has no origin".
        val p = fileLeft?.path.orEmpty()
        val rel = gitRelPath(p)
        val src = gitSideSources(this, p)
        // Both sides are called "old" / "new", but staged compares HEAD↔index and
        // unstaged compares index↔worktree — without spelling that out, just writing
        // "old / new" does not show which two versions were compared
        val lt = src?.first?.takeIf { it.isNotEmpty() }?.let { "$l($it)" } ?: l
        val rt = src?.second?.takeIf { it.isNotEmpty() }?.let { "$r($it)" } ?: r
        b.titleLeft.text = if (rel.isEmpty()) lt else "$lt · $rel"
        b.titleRight.text = if (rel.isEmpty()) rt else "$rt · $rel"
    }



    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Read one side in full; goes through [FsRegistry] so local / inside-archive / network sources are all the same. */
    private fun readSide(f: XFile): ByteArray =
        FsRegistry.of(f).openInput(f).use { OpenFiles.readAllBytes(it) }

    private fun refreshSubtitle() {
        if (statBase.isEmpty()) return
        val pos = if (blockIdx >= 0) " · ${blockIdx + 1}/${blocks.size}" else ""
        // No longer pack "left / right" / "old / new" here: that is the job of each pane's
        // title bar, and writing it twice would just squeeze the already-narrow subtitle
        // into being truncated (especially noticeable in portrait)
        b.toolbar.subtitle = "$statBase$pos"
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayoutMode()
        // After changing orientation, a line's visible width has changed; the original
        // horizontal position may have gone out of range
        b.listLeft.post { setHScroll(hScroll) }
    }

    override fun onDestroy() {
        hFling?.cancel()
        super.onDestroy()
    }

    /**
     * Horizontal swipe = horizontal scrolling of long lines. Once a horizontal intent
     * is detected, **take over the whole event sequence** — otherwise RecyclerView will
     * also do vertical scrolling, and the feel is a drifting slant. Side switching is
     * handled by the toolbar button and no longer fights for this gesture.
     */
    private fun installHScroll(rv: RecyclerView) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var dragging = false
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (!dragging || abs(vx) <= abs(vy)) return false
                flingH(vx)
                return true
            }
        })
        rv.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(view: RecyclerView, e: MotionEvent): Boolean {
                gd.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragging = false
                        downX = e.x
                        downY = e.y
                        lastX = e.x
                        hFling?.cancel()
                    }
                    MotionEvent.ACTION_MOVE -> if (!dragging && hScrollMax() > 0) {
                        val dx = abs(e.x - downX)
                        val dy = abs(e.y - downY)
                        // Only take over on a clear horizontal: horizontal exceeds slop and is at least
                        // 1.5x vertical, so as not to misjudge normal up/down scrolling as
                        // horizontal scrolling
                        if (dx > slop && dx > dy * 1.5f) {
                            dragging = true
                            lastX = e.x
                        }
                    }
                }
                return dragging
            }

            override fun onTouchEvent(view: RecyclerView, e: MotionEvent) {
                gd.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        setHScroll(hScroll + (lastX - e.x).toInt())
                        lastX = e.x
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
        })
    }

    private fun flingH(vx: Float) {
        val target = (hScroll - vx * 0.3f).toInt().coerceIn(0, hScrollMax())
        if (target == hScroll) return
        hFling?.cancel()
        hFling = android.animation.ValueAnimator.ofInt(hScroll, target).apply {
            duration = 450
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { setHScroll(it.animatedValue as Int) }
            start()
        }
    }

    /** How far horizontal scrolling can go: the longest line's width minus the visible width of the text region. */
    private fun hScrollMax(): Int {
        val vw = visibleTextWidth()
        if (vw <= 0) return 0
        return (maxLineWidth - vw).coerceAtLeast(0f).toInt()
    }

    /** Visible width of the text region (excluding the fixed line-number column on the left); fall back to the whole list width when there are no rows yet. */
    private fun visibleTextWidth(): Int {
        for (rv in listOf(b.listLeft, b.listRight)) {
            if (!rv.isShown) continue
            val holder = rv.getChildAt(0)?.let { rv.getChildViewHolder(it) } as? VH ?: continue
            if (holder.b.tvText.width > 0) return holder.b.tvText.width
        }
        return 0
    }

    private fun setHScroll(x: Int) {
        val v = x.coerceIn(0, hScrollMax())
        if (v == hScroll) return
        hScroll = v
        applyHScroll(b.listLeft)
        applyHScroll(b.listRight)
    }

    /** Apply the current horizontal scroll amount to a pane's already-bound rows (newly bound rows apply it themselves inside onBindViewHolder). */
    private fun applyHScroll(rv: RecyclerView) {
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? VH ?: continue
            holder.b.tvText.scrollTo(hScroll, 0)
        }
    }

    /**
     * Measure how wide the longest line is; horizontal scrolling's bounds depend on it.
     * Put it on a background thread because measureText is called per line, and large
     * files have tens of thousands of lines; before this finishes, [hScrollMax] returns 0
     * and horizontal scrolling is naturally disabled, so it does not scroll into a blank.
     */
    private fun measureMaxLineWidth() {
        val paint = ItemDiffLineBinding.inflate(layoutInflater).tvText.paint
        val snapshot = rows
        lifecycleScope.launch {
            val w = withContext(Dispatchers.Default) {
                var m = 0f
                for (r in snapshot) {
                    r.left?.let { m = maxOf(m, paint.measureText(it)) }
                    r.right?.let { m = maxOf(m, paint.measureText(it)) }
                }
                m
            }
            maxLineWidth = w
        }
    }

    private fun switchSide(to: Int) {
        if (side == to) return
        val from = if (side == 0) b.listLeft else b.listRight
        val dest = if (to == 0) b.listLeft else b.listRight
        side = to
        // Carry the scroll position over
        val lm = from.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        val off = from.getChildAt(0)?.top ?: 0
        applyLayoutMode()
        if (pos >= 0) (dest.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, off)
        // The rows in the destination pane may have been bound earlier and still carry
        // the old horizontal position
        dest.post { applyHScroll(dest) }
    }

    /** Landscape two-pane synchronized scrolling. */
    private fun linkScroll(src: RecyclerView, dst: RecyclerView) {
        src.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (syncing || dy == 0 || !dst.isShown) return
                syncing = true
                dst.scrollBy(0, dy)
                syncing = false
            }
        })
    }

    // ---- List ----

    private inner class SideAdapter(private val left: Boolean) : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDiffLineBinding.inflate(LayoutInflater.from(parent.context), parent, false))
                .also { configureDiffLineText(it.b.tvText) }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val text = if (left) row.left else row.right
            val no = if (left) row.leftNo else row.rightNo
            holder.b.tvNo.text = if (no > 0) no.toString() else ""
            val hl = if (left) hlLeft else hlRight
            holder.b.tvText.text = if (text == null) "" else hl?.getOrNull(no - 1) ?: text
            holder.b.tvText.scrollTo(hScroll, 0)
            // Characters not covered by a token (punctuation / whitespace / unrecognized
            // language) keep the XML default color, unless highlighting is in effect this
            // time — in that case the entire side's background has been swapped to the
            // theme's bg and the default text color must follow too; otherwise most
            // characters are still the app's own text_primary, which may be black on a
            // dark-theme background and unreadable.
            hlTheme?.let { holder.b.tvText.setTextColor(it.fg) }
            holder.b.row.setBackgroundColor(
                when {
                    text == null -> PLACEHOLDER_BG
                    row.changed -> if (left) DEL_BG else ADD_BG
                    else -> 0
                },
            )
        }
    }

    private class VH(val b: ItemDiffLineBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SIDE = "side"
        private const val EXTRA_R_SCHEME = "r_scheme"
        private const val EXTRA_R_PATH = "r_path"
        private const val EXTRA_L_SIZE = "l_size"
        private const val EXTRA_R_SIZE = "r_size"
        private const val DEL_BG = 0x26EF5350
        private const val ADD_BG = 0x2666BB6A
        private const val PLACEHOLDER_BG = 0x14888888

        /** Patience diff is an in-memory algorithm: both sides must be read in full before splitting into lines — past this size, refuse outright. */
        private const val PAIR_MAX_BYTES = 4L shl 20

        fun start(context: Context, scheme: String, path: String, title: String) {
            context.startActivity(
                Intent(context, DiffActivity::class.java)
                    .putExtra(EXTRA_SCHEME, scheme)
                    .putExtra(EXTRA_PATH, path)
                    .putExtra(EXTRA_TITLE, title),
            )
        }

        /**
         * Compare any two files (the path entered from the directory-compare page).
         * Shares the full two-pane rendering with git mode; only the text source changes
         * from `GitFileSystem.diffSides` to one read per side.
         *
         * Returns an Intent rather than starting it directly: this path needs the result
         * code — if differences were merged and saved inside the page, RESULT_OK lets the
         * compare page reclassify this pair's state locally.
         */
        fun pairIntent(context: Context, left: XFile, right: XFile, title: String, side: Int = 0): Intent =
            Intent(context, DiffActivity::class.java)
                .putExtra(EXTRA_SCHEME, left.scheme)
                .putExtra(EXTRA_PATH, left.path)
                .putExtra(EXTRA_R_SCHEME, right.scheme)
                .putExtra(EXTRA_R_PATH, right.path)
                .putExtra(EXTRA_L_SIZE, left.size)
                .putExtra(EXTRA_R_SIZE, right.size)
                .putExtra(EXTRA_SIDE, side)
                .putExtra(EXTRA_TITLE, title)
    }
}
