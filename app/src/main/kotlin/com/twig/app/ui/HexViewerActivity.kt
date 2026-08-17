package com.twig.app.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ScaleXSpan
import android.util.TypedValue
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.Format
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ActivityHexViewerBinding
import com.twig.app.databinding.ItemHexRowBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 十六进制查看器。读取经 [FsRegistry],各来源通用。
 *
 * 几条设计要点:
 *  - **虚拟滚动**:一行一个 RecyclerView item,字节按需从 [HexSource] 的块缓存里取,
 *    缺块时先铺占位符、块读回来再刷新可见行。因此不再有整读上限,10GB 的文件也能开,
 *    打开瞬间只读了标题栏那一屏。
 *  - **不横向滚动**:每行字节数 [bpr] 由面板宽度 ÷ 字符宽算出来(等宽字体,每字节
 *    占 hex 3 格 + 字符 1 格),字号一变就重算。三列宽度都在 [bindRow] 里按 [charW]
 *    显式定死——靠 wrap_content 的话末行字节不满会缩宽,整列就跟着错位。
 *  - **搜索**:文本(UTF-8,ASCII 大小写不敏感)与十六进制两种模式,同一份字节流上扫,
 *    命中处在 hex 与字符两列同时高亮。
 *  - 配色跟文本查看器同一套 [CodeHighlighter.THEMES],菜单可切;字号双指捏合可调,
 *    记在 [Prefs.hexTextSize]。
 */
class HexViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityHexViewerBinding
    private lateinit var file: XFile
    private lateinit var lm: LinearLayoutManager

    private var src: HexSource? = null
    private var size = 0L
    private var rowCount = 0
    private var bpr = 16 // 每行字节数
    private var offDigits = 8 // 偏移列的十六进制位数
    private var textSp = 12f
    private var charW = 1f // 等宽字体单字符宽(px)
    private var placeholder = ""
    private var pendingRelayout = false
    private val adapter = HexAdapter()
    private val measurePaint = Paint()
    private val loadingChunks = HashSet<Int>()

    // 搜索
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

        lm = LinearLayoutManager(this)
        b.list.layoutManager = lm
        b.list.adapter = adapter
        b.list.setHasFixedSize(true)
        b.list.itemAnimator = null // 行是纯文本、等高,动画只会在跳转时闪
        textSp = Prefs.hexTextSize(this)
        applyTheme()
        wirePinchZoom()
        wireFastScroll()
        wireSearchBar()
        // 宽度变了(转屏/分屏)要重算每行字节数;文件先读出来、视图还没量到宽的那种
        // 顺序也在这里接住([pendingRelayout])。★ 必须 post 出去再算:这个回调是在布局
        // 遍历里发的,当场 notifyDataSetChanged 会触发"requestLayout during layout",
        // 这一帧的更新直接被系统丢掉。
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
        val s = src
        src = null
        // 关闭要碰 IO(SMB/SFTP 都会发包),lifecycleScope 这会儿已经取消了,借个线程
        if (s != null) Thread { runCatching { s.close() } }.start()
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
            applyTextSize(textSp + 1f, persist = true); true
        }
        b.toolbar.menu.add(R.string.viewer_text_smaller).setOnMenuItemClickListener {
            applyTextSize(textSp - 1f, persist = true); true
        }
        b.toolbar.menu.add(R.string.viewer_theme).setOnMenuItemClickListener { pickTheme(); true }
    }

    // ---- 载入 ----

    private fun open(declaredSize: Long) {
        b.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    HexSource(FsRegistry.of(file), file).also { it.open(declaredSize) }
                }
            }
            b.loading.visibility = View.GONE
            r.fold(
                onSuccess = { s ->
                    src = s
                    size = s.size
                    offDigits = HexLayout.offsetDigits(size)
                    if (s.truncated) {
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

    // ---- 版式:每行字节数 / 列宽 ----

    /** 按当前宽度与字号重算每行字节数(算法见 [HexLayout.bytesPerRow])。 */
    private fun relayout(force: Boolean) {
        if (src == null) return
        val w = b.list.width
        if (w <= 0) {
            // 还没量到宽(文件读得比第一次布局快)。**不能自己 post 重试**——那是一条
            // 会一直空转的消息链;挂个标记等布局回调来叫即可。
            pendingRelayout = true
            return
        }
        val dm = resources.displayMetrics
        measurePaint.typeface = Typeface.MONOSPACE
        measurePaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp, dm)
        charW = measurePaint.measureText("0").coerceAtLeast(1f)
        val usable = w - (ROW_PADDING_DP + DIVIDER_DP) * dm.density
        val newBpr = HexLayout.bytesPerRow(usable, charW, offDigits, MIN_BPR, MAX_BPR)
        if (!force && newBpr == bpr) return
        val top = topOffset()
        bpr = newBpr
        placeholder = "·· ".repeat(bpr).trimEnd()
        rowCount = ((size + bpr - 1) / bpr).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        adapter.notifyDataSetChanged()
        lm.scrollToPositionWithOffset((top / bpr).toInt(), 0)
        b.list.post { syncBar() }
    }

    /** 当前屏顶那一行对应的文件偏移(换行宽/换字号时用它把视线钉在原处)。 */
    private fun topOffset(): Long {
        val p = lm.findFirstVisibleItemPosition()
        return if (p < 0) 0L else p.toLong() * bpr
    }

    private fun applyTextSize(sp: Float, persist: Boolean) {
        // 量化到 0.5sp:捏合手势每帧都会来一次,不量化就是每帧一次全表重排
        val v = (sp.coerceIn(MIN_SP, MAX_SP) * 2).roundToInt() / 2f
        if (v != textSp) {
            textSp = v
            relayout(force = true)
        }
        if (persist) Prefs.setHexTextSize(this, textSp)
    }

    private fun wirePinchZoom() {
        val detector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    applyTextSize(textSp * d.scaleFactor, persist = false)
                    return true
                }

                override fun onScaleEnd(d: ScaleGestureDetector) {
                    Prefs.setHexTextSize(this@HexViewerActivity, textSp)
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

    // ---- 快速滚动条 ----

    private fun wireFastScroll() {
        b.fastscroll.onDrag = { f, ended ->
            if (rowCount > 1) {
                val row = (maxFirstRow() * f).roundToInt().coerceIn(0, rowCount - 1)
                lm.scrollToPositionWithOffset(row, 0)
                showDragHint(row.toLong() * bpr, !ended)
            }
        }
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!b.fastscroll.dragging) syncBar()
            }
        })
    }

    /**
     * 滑块比例 1.0 对应的首行行号——是"总行数 - 一屏行数",不是"总行数"。拿总行数当
     * 分母的话,拖到底也只能滚到倒数一屏处,最后那一屏永远够不着。
     */
    private fun maxFirstRow(): Int {
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        val onScreen = if (first in 0..last) last - first + 1 else 1
        return (rowCount - onScreen).coerceAtLeast(1)
    }

    private fun syncBar() {
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        val onScreen = if (first in 0..last) last - first + 1 else 0
        b.fastscroll.isEnabled = rowCount > onScreen && rowCount > 1
        b.fastscroll.invalidate()
        if (first >= 0 && rowCount > 1) b.fastscroll.fraction = first.toFloat() / maxFirstRow()
    }

    private fun showDragHint(off: Long, show: Boolean) {
        b.dragHint.visibility = if (show) View.VISIBLE else View.GONE
        if (show) b.dragHint.text = String.format(Locale.US, "%0${offDigits}X", off)
    }

    // ---- 搜索 ----

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
            b.searchInput.setText("") // 顺带清掉命中高亮
        }
    }

    private fun runSearch(jumpFirst: Boolean) {
        searchJob?.cancel()
        val q = b.searchInput.text.toString()
        val pat = if (hexMode) HexLayout.parseHex(q) else q.toByteArray(Charsets.UTF_8)
        matches = emptyList()
        cur = -1
        patLen = pat.size
        val s = src
        if (pat.isEmpty() || s == null) {
            b.searchCount.text = ""
            refreshVisible()
            return
        }
        b.searchCount.text = "…"
        refreshVisible()
        val from = topOffset()
        searchJob = lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE) // 打字期间别每个键都扫一遍全文
            val found = withContext(Dispatchers.IO) {
                val active = { coroutineContext.isActive }
                runCatching { s.search(pat, fold = !hexMode, limit = MAX_MATCHES, active = active) }
                    .getOrDefault(emptyList())
            }
            matches = found
            patLen = pat.size
            if (found.isEmpty()) {
                b.searchCount.text = "0"
                refreshVisible()
            } else if (jumpFirst) {
                // 从当前屏位置往下找最近的一处,而不是一律回文件开头
                val i = found.indexOfFirst { it >= from }
                jumpTo(if (i >= 0) i else 0)
            } else {
                b.searchCount.text = "${matches.size}"
                refreshVisible()
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
        lm.scrollToPositionWithOffset((off / bpr).toInt(), b.list.height / 3)
        b.list.post {
            refreshVisible()
            syncBar()
        }
    }

    /** 行内命中区间(字节下标),[Triple.third] 标记是不是当前那一处。 */
    private fun hitsIn(off: Long, len: Int): List<Triple<Int, Int, Boolean>> {
        if (matches.isEmpty() || patLen <= 0) return emptyList()
        val out = ArrayList<Triple<Int, Int, Boolean>>(2)
        // 第一个可能与本行相交的命中:它的结束位置要越过行首,即 m > off - patLen
        var i = matches.binarySearch { m -> if (m <= off - patLen) -1 else 1 }
        if (i < 0) i = -i - 1
        while (i < matches.size && matches[i] < off + len) {
            val s = maxOf(0L, matches[i] - off).toInt()
            val e = minOf(len.toLong(), matches[i] + patLen - off).toInt()
            if (e > s) out.add(Triple(s, e, i == cur))
            i++
        }
        return out
    }

    private fun refreshVisible() {
        val f = lm.findFirstVisibleItemPosition()
        val l = lm.findLastVisibleItemPosition()
        if (f in 0..l) adapter.notifyItemRangeChanged(f, l - f + 1)
    }

    // ---- 配色 ----

    private fun theme(): CodeHighlighter.Theme =
        CodeHighlighter.THEMES[Prefs.codeTheme(this).coerceIn(0, CodeHighlighter.THEMES.size - 1)]

    private fun applyTheme() {
        b.list.setBackgroundColor(theme().bg)
    }

    private fun pickTheme() {
        val names = CodeHighlighter.THEMES.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_theme)
            .setSingleChoiceItems(names, Prefs.codeTheme(this).coerceIn(0, names.size - 1)) { d, i ->
                Prefs.setCodeTheme(this, i)
                applyTheme()
                adapter.notifyItemRangeChanged(0, rowCount)
                d.dismiss()
            }
            .show()
    }

    // ---- 行 ----

    private inner class RowVH(val v: ItemHexRowBinding) : RecyclerView.ViewHolder(v.root)

    private inner class HexAdapter : RecyclerView.Adapter<RowVH>() {
        override fun getItemCount() = rowCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowVH(ItemHexRowBinding.inflate(layoutInflater, parent, false)).also { h ->
                for (tv in arrayOf(h.v.off, h.v.hex, h.v.chars)) {
                    // ★ 三列宽度是按字符宽算死的,估算差一个像素文本就会**折行**——而
                    // maxLines=1 只显示折出来的第一行,末尾几个字节就这么无声消失了
                    // (与 DiffActivity 那个省略号坑同源:布局宽度不是无限宽时的默认行为)。
                    // 按无限宽排版 + 关掉省略号,溢出就只是裁掉一点点,不会丢内容。
                    tv.setHorizontallyScrolling(true)
                    tv.ellipsize = null
                }
                h.v.root.setOnLongClickListener { copyRow(h.bindingAdapterPosition); true }
            }

        override fun onBindViewHolder(holder: RowVH, position: Int) = bindRow(holder, position)
    }

    private fun bindRow(h: RowVH, pos: Int) {
        val t = theme()
        val off = pos.toLong() * bpr
        val len = minOf(bpr.toLong(), size - off).coerceAtLeast(0L).toInt()

        for (tv in arrayOf(h.v.off, h.v.hex, h.v.chars)) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        }
        // 列宽显式定死:末行字节不满时若靠 wrap_content,这行会缩宽、字符列就跟着左移
        setWidth(h.v.off, ceil(charW * (offDigits + 1)).toInt())
        setWidth(h.v.chars, ceil(charW * bpr).toInt() + 2)
        h.v.off.setTextColor(t.colors[COLOR_DIM])
        h.v.off.text = String.format(Locale.US, "%0${offDigits}X", off)
        // 分割线用注释色压到半透明:直接用原色在浅色主题上太抢眼,盖在哪套背景上都得淡
        h.v.divider.setBackgroundColor((t.colors[COLOR_DIM] and 0x00FFFFFF) or DIVIDER_ALPHA)

        val data = src?.peek(off, len)
        if (data == null) {
            requestChunks(off, len)
            h.v.hex.setTextColor(t.colors[COLOR_DIM])
            h.v.hex.text = placeholder
            h.v.chars.text = ""
            return
        }
        val hits = hitsIn(off, data.size)
        h.v.hex.setTextColor(t.fg)
        h.v.hex.text = hexText(data, hits)
        h.v.chars.setTextColor(t.colors[COLOR_ASCII])
        h.v.chars.text = charText(data, hits)
    }

    /**
     * hex 列。字节之间**一律留一个空格字符**(高亮区间的下标算术因此始终是 `i*3`),
     * 两字节一组的分组靠把**组内**那个空格用 [ScaleXSpan] 压窄来做——不是删掉它。
     * 删的话下标要跟着分组变、[copyRow] 复制出去的文本也会粘成一坨。
     */
    private fun hexText(data: ByteArray, hits: List<Triple<Int, Int, Boolean>>): CharSequence {
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
        for ((s, e, isCur) in hits) {
            // 高亮连带吃掉字节之间的空格,一段命中看着是整块的
            val end = minOf(sb.length, e * 3 - 1)
            if (s * 3 < end) sb.setSpan(bg(isCur), s * 3, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun charText(data: ByteArray, hits: List<Triple<Int, Int, Boolean>>): CharSequence {
        val sb = SpannableStringBuilder()
        for (byte in data) {
            val v = byte.toInt() and 0xFF
            sb.append(if (v in 0x20..0x7E) v.toChar() else '.')
        }
        for ((s, e, isCur) in hits) {
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

    private fun copyRow(pos: Int): Boolean {
        if (pos < 0) return false
        val off = pos.toLong() * bpr
        val data = src?.peek(off, minOf(bpr.toLong(), size - off).toInt()) ?: return false
        val line = String.format(Locale.US, "%0${offDigits}X", off) + "  " +
            hexText(data, emptyList()) + "  " + charText(data, emptyList())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hex", line))
        toast(getString(R.string.info_copied))
        return true
    }

    /** 绑定时发现缺块就排一次读取,读回来刷新可见行——这就是"异步显示"的全部。 */
    private fun requestChunks(off: Long, len: Int) {
        val s = src ?: return
        if (len <= 0) return
        val first = (off / HexSource.CHUNK).toInt()
        val last = ((off + len - 1) / HexSource.CHUNK).toInt()
        for (ci in first..last) {
            if (!loadingChunks.add(ci)) continue
            lifecycleScope.launch {
                val ok = runCatching { withContext(Dispatchers.IO) { s.load(ci) } }.isSuccess
                loadingChunks.remove(ci)
                if (ok && !isFinishing) refreshVisible()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"
        private const val MIN_SP = 7f
        private const val MAX_SP = 28f
        private const val MIN_BPR = 4
        private const val MAX_BPR = 64
        // item 左 8dp + 右 30dp:右边这一截是给快速滚动条让出来的,字符列不能压在它下面
        private const val ROW_PADDING_DP = 38f
        private const val DIVIDER_DP = 9f // 分割线 1dp + 两侧各 4dp 外边距
        private const val MAX_MATCHES = 2000
        private const val SEARCH_DEBOUNCE = 250L
        private const val COLOR_DIM = 2 // Theme.colors 里的注释色,拿来当偏移列的弱化色
        private const val DIVIDER_ALPHA = 0x66000000
        private const val COLOR_ASCII = 1 // 字符串色,字符列用它跟 hex 区分开
        private const val HIT_BG = 0x66FFC107 // 全部命中:半透明琥珀(与文本查看器一致)
        private val CUR_BG = 0xB3FF6F00.toInt() // 当前命中:深橙
        private val HEX = "0123456789ABCDEF".toCharArray()

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
