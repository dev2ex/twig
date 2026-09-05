package com.twig.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ScaleXSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import com.twig.app.databinding.ItemHexRowBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil

/** A highlighted range inside one row: byte indices `[start, end)`, [current] marks the one navigation sits on. */
data class HexHit(val start: Int, val end: Int, val current: Boolean)

/**
 * One column of hex: a RecyclerView + a [HexSource] + the row rendering. Extracted from
 * [HexViewerActivity] so the compare page can put **two** of them side by side — the
 * fiddly parts (bytes-per-row budget, pinned column widths, the squeezed intra-group
 * space, async chunk filling) exist exactly once and both pages get fixes to them.
 *
 * What stays outside: the toolbar, menus, search, theme picking, pinch zoom, the fast
 * scroll bar. Those differ between the two pages; this class only knows how to draw
 * bytes and where the highlights are — [hits] is the single hook, and search hits and
 * byte differences are the same shape, so they share one rendering path.
 *
 * ★ [bpr] and [offDigits] are **set from outside** rather than computed here: the compare
 * page has to force both columns onto the same values or the two offsets stop lining up
 * and synchronised scrolling is meaningless. [computeBpr] only measures and proposes.
 */
class HexPane(private val list: RecyclerView, private val scope: CoroutineScope) {

    private val ctx = list.context
    private val inflater = LayoutInflater.from(ctx)
    private val lm = LinearLayoutManager(ctx)
    private val adapter = HexAdapter()
    private val measurePaint = Paint()
    private val loadingChunks = HashSet<Int>()

    var src: HexSource? = null
        private set
    var size = 0L
        private set
    var rowCount = 0
        private set

    /** Rows this file actually has; [rowCount] may exceed it after [padTo]. */
    private var naturalRows = 0
    var bpr = 16
        private set
    var offDigits = 8
        private set
    var textSp = 12f
        private set

    private var charW = 1f
    private var placeholder = ""

    /** Colour scheme; the owner assigns it and calls [refreshAll]. */
    var theme: CodeHighlighter.Theme = CodeHighlighter.THEMES[0]

    /** Where the highlights come from: given a row's `(offset, length)`, which byte ranges to paint. */
    var hits: (Long, Int) -> List<HexHit> = { _, _ -> emptyList() }

    /** Long-press on a row; the owner decides what to do with [rowText]. */
    var onRowLongClick: ((Int) -> Unit)? = null

    init {
        list.layoutManager = lm
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.itemAnimator = null // rows are pure text and equal-height; animations only flash on jumps
    }

    /** Blocking IO on [Dispatchers.IO]; throws on failure, so the caller can show the error. */
    suspend fun open(file: XFile, declaredSize: Long) {
        val s = withContext(Dispatchers.IO) {
            HexSource(FsRegistry.of(file), file).also { it.open(declaredSize) }
        }
        src = s
        size = s.size
    }

    fun close() {
        val s = src ?: return
        src = null
        // Closing touches IO (SMB/SFTP both send packets); the owner's scope is usually cancelled by now, so borrow a thread
        Thread { runCatching { s.close() } }.start()
    }

    /** Returns true when the size actually changed, so the caller knows a relayout is due. */
    fun setTextSize(sp: Float): Boolean {
        if (sp == textSp) return false
        textSp = sp
        return true
    }

    /**
     * Measure and propose a bytes-per-row for [digits] offset digits; **0 means the view has
     * no width yet** and the caller should wait for the layout pass rather than retrying in a
     * posted message (see [HexViewerActivity]'s pendingRelayout).
     */
    fun computeBpr(digits: Int): Int {
        val w = list.width
        if (w <= 0) return 0
        val dm = ctx.resources.displayMetrics
        measurePaint.typeface = Typeface.MONOSPACE
        measurePaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp, dm)
        charW = measurePaint.measureText("0").coerceAtLeast(1f)
        // ★ Row padding is read from the same resources the row layout uses, never re-typed as a
        // constant here: when the two drifted, changing the layout's padding did nothing because
        // this budget still reserved the old width.
        val rowPad = ctx.resources.getDimension(R.dimen.hex_row_pad_start) +
            ctx.resources.getDimension(R.dimen.hex_row_pad_end)
        val usable = w - rowPad - DIVIDER_DP * dm.density
        return HexLayout.bytesPerRow(usable, charW, digits, MIN_BPR, MAX_BPR)
    }

    /** Adopt a row width; keeps the top-of-screen file offset pinned. Returns true when anything changed. */
    fun applyLayout(newBpr: Int, digits: Int, force: Boolean): Boolean {
        if (!force && newBpr == bpr && digits == offDigits) return false
        val top = topOffset()
        bpr = newBpr
        offDigits = digits
        placeholder = "·· ".repeat(bpr).trimEnd()
        naturalRows = ((size + bpr - 1) / bpr).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        rowCount = naturalRows
        adapter.notifyDataSetChanged()
        lm.scrollToPositionWithOffset((top / bpr).toInt(), 0)
        return true
    }

    /**
     * Pad the row count out to [minRows], the rows past the file rendering blank.
     *
     * ★ This is what lets the compare page scroll a **shorter** file's column past its own end. Without
     * it the short side hits the bottom, and because the two columns push their positions onto each
     * other, it drags the long side back up — the tail difference, the one that says "these bytes exist
     * on one side only", could never be reached.
     */
    fun padTo(minRows: Int) {
        val want = maxOf(naturalRows, minRows)
        if (want == rowCount) return
        rowCount = want
        adapter.notifyDataSetChanged()
    }

    /** File offset of the row at the top of the screen (used to pin the view across row-width changes). */
    fun topOffset(): Long {
        val p = lm.findFirstVisibleItemPosition()
        return if (p < 0) 0L else p.toLong() * bpr
    }

    fun firstVisibleRow(): Int = lm.findFirstVisibleItemPosition()

    fun lastVisibleRow(): Int = lm.findLastVisibleItemPosition()

    /** Pixel offset of the first visible row's top edge relative to the list (negative when scrolled past). */
    fun firstVisibleTop(): Int {
        val p = lm.findFirstVisibleItemPosition()
        if (p < 0) return 0
        return lm.findViewByPosition(p)?.top ?: 0
    }

    fun scrollToRow(row: Int, offsetPx: Int = 0) = lm.scrollToPositionWithOffset(row, offsetPx)

    /** Put the row containing [off] at [offsetPx] from the top. */
    fun scrollToOffset(off: Long, offsetPx: Int = 0) =
        lm.scrollToPositionWithOffset((off / bpr).toInt(), offsetPx)

    fun refreshVisible() {
        val f = lm.findFirstVisibleItemPosition()
        val l = lm.findLastVisibleItemPosition()
        if (f in 0..l) adapter.notifyItemRangeChanged(f, l - f + 1)
    }

    fun refreshAll() = adapter.notifyItemRangeChanged(0, rowCount)

    /** One row rendered as text, for "copy row". */
    fun rowText(pos: Int): String? {
        if (pos < 0) return null
        val off = pos.toLong() * bpr
        val data = src?.peek(off, minOf(bpr.toLong(), size - off).toInt()) ?: return null
        return String.format(Locale.US, "%0${offDigits}X", off) + "  " +
            hexText(data, emptyList()) + "  " + charText(data, emptyList())
    }

    // ---- rows ----

    private inner class RowVH(val v: ItemHexRowBinding) : RecyclerView.ViewHolder(v.root)

    private inner class HexAdapter : RecyclerView.Adapter<RowVH>() {
        override fun getItemCount() = rowCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowVH(ItemHexRowBinding.inflate(inflater, parent, false)).also { h ->
                for (tv in arrayOf(h.v.off, h.v.hex, h.v.chars)) {
                    // ★ All three column widths are pinned to the character width; off by even one pixel and the text **wraps** —
                    // and maxLines=1 only shows the first wrapped line, so the trailing bytes silently disappear
                    // (same root cause as the ellipsis bug in DiffActivity: the default behaviour when the layout
                    // width isn't infinite).
                    // Lay out at infinite width + disable ellipsis; any overflow is just clipped a little, never loses content.
                    tv.setHorizontallyScrolling(true)
                    tv.ellipsize = null
                }
                h.v.root.setOnLongClickListener {
                    val cb = onRowLongClick ?: return@setOnLongClickListener false
                    val p = h.bindingAdapterPosition
                    if (p < 0) return@setOnLongClickListener false
                    cb(p)
                    true
                }
            }

        override fun onBindViewHolder(holder: RowVH, position: Int) = bindRow(holder, position)
    }

    private fun bindRow(h: RowVH, pos: Int) {
        val t = theme
        val off = pos.toLong() * bpr
        val len = minOf(bpr.toLong(), size - off).coerceAtLeast(0L).toInt()

        for (tv in arrayOf(h.v.off, h.v.hex, h.v.chars)) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        }
        // Column widths explicitly pinned: if the last row has fewer bytes and we rely on wrap_content, that row shrinks
        // the column width and the char column shifts leftward with it
        setWidth(h.v.off, ceil(charW * (offDigits + 1)).toInt())
        setWidth(h.v.chars, ceil(charW * bpr).toInt() + 2)
        if (off >= size) {
            // A padding row (see [padTo]): past the end of this file. It stays completely blank —
            // printing an offset here would claim the file reaches further than it does.
            h.v.off.text = ""
            h.v.hex.text = ""
            h.v.chars.text = ""
            h.v.divider.setBackgroundColor((t.colors[COLOR_DIM] and 0x00FFFFFF) or DIVIDER_ALPHA)
            return
        }
        h.v.off.setTextColor(t.colors[COLOR_DIM])
        h.v.off.text = String.format(Locale.US, "%0${offDigits}X", off)
        // Divider uses the comment colour pressed down to translucent: the original colour is too loud on light themes,
        // and it has to be muted regardless of the background
        h.v.divider.setBackgroundColor((t.colors[COLOR_DIM] and 0x00FFFFFF) or DIVIDER_ALPHA)

        val data = src?.peek(off, len)
        if (data == null) {
            requestChunks(off, len)
            h.v.hex.setTextColor(t.colors[COLOR_DIM])
            h.v.hex.text = placeholder
            h.v.chars.text = ""
            return
        }
        val rowHits = hits(off, data.size)
        h.v.hex.setTextColor(t.fg)
        h.v.hex.text = hexText(data, rowHits)
        h.v.chars.setTextColor(t.colors[COLOR_ASCII])
        h.v.chars.text = charText(data, rowHits)
    }

    /**
     * Hex column. **Always leave one space character between bytes** (so the highlight range's index math
     * is always `i*3`); the two-byte-per-group visual grouping is achieved by squishing the **within-group**
     * space with [ScaleXSpan] — not by deleting it. Deleting it would require indices to shift with the grouping,
     * and the text [rowText] copies out would paste together as a blob.
     */
    private fun hexText(data: ByteArray, rowHits: List<HexHit>): CharSequence {
        val sb = SpannableStringBuilder()
        for (i in data.indices) {
            val v = data[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
            if (i < data.size - 1) {
                val at = sb.length
                sb.append(' ')
                if (i % 2 == 0) {
                    sb.setSpan(ScaleXSpan(HexLayout.PAIR_GAP), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        for ((s, e, isCur) in rowHits) {
            // Highlight also eats the inter-byte spaces so each hit reads as one solid block
            val end = minOf(sb.length, e * 3 - 1)
            if (s * 3 < end) sb.setSpan(bg(isCur), s * 3, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun charText(data: ByteArray, rowHits: List<HexHit>): CharSequence {
        val sb = SpannableStringBuilder()
        for (byte in data) {
            val v = byte.toInt() and 0xFF
            sb.append(if (v in 0x20..0x7E) v.toChar() else '.')
        }
        for ((s, e, isCur) in rowHits) {
            if (e <= sb.length) sb.setSpan(bg(isCur), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun bg(isCur: Boolean) = BackgroundColorSpan(if (isCur) CUR_BG else HIT_BG)

    private fun setWidth(v: TextView, px: Int) {
        val lp = v.layoutParams
        if (lp.width != px) {
            lp.width = px
            v.layoutParams = lp
        }
    }

    /** When binding we find a missing chunk, schedule a read once, then refresh visible rows when it returns — that is the entirety of "async display". */
    private fun requestChunks(off: Long, len: Int) {
        val s = src ?: return
        if (len <= 0) return
        val first = (off / HexSource.CHUNK).toInt()
        val last = ((off + len - 1) / HexSource.CHUNK).toInt()
        for (ci in first..last) {
            if (!loadingChunks.add(ci)) continue
            scope.launch {
                val ok = runCatching { withContext(Dispatchers.IO) { s.load(ci) } }.isSuccess
                loadingChunks.remove(ci)
                if (ok) refreshVisible()
            }
        }
    }

    companion object {
        const val MIN_BPR = 4
        const val MAX_BPR = 64
        // item: left 8dp + right 30dp; the right strip is reserved for the fast scroll bar, so the char column can't slide under it
        private const val DIVIDER_DP = 9f // divider 1dp + 4dp margin on each side
        private const val COLOR_DIM = 2 // comment colour in Theme.colors, reused as the muted offset-column colour
        private const val COLOR_ASCII = 1 // string colour, the char column uses this to differentiate from hex
        private const val DIVIDER_ALPHA = 0x66000000
        const val HIT_BG = 0x66FFC107 // all hits: translucent amber (matches the text viewer)
        val CUR_BG = 0xB3FF6F00.toInt() // current hit: deep orange
        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
