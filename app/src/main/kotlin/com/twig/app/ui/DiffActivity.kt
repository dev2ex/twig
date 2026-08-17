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
 * 这一对 diff 的两侧各是哪个版本(对应 `GitVfs.diffSides` 的取值)。
 * 同样叫"旧/新",staged 比的是 HEAD↔暂存区、unstaged 比的是暂存区↔工作区——
 * 不写出来就看不出这次到底比了哪两个版本。untracked 是新增文件,旧侧压根不存在,给空串。
 */
internal fun gitSideSources(ctx: android.content.Context, p: String): Pair<String, String>? = when {
    p.startsWith("/changes/") -> when (p.removePrefix("/changes/").substringBefore('/')) {
        "staged" -> "HEAD" to ctx.getString(R.string.git_src_index)
        "unstaged" -> ctx.getString(R.string.git_src_index) to ctx.getString(R.string.git_src_work)
        "untracked" -> "" to ctx.getString(R.string.git_src_work)
        else -> null
    }
    // 父提交 ↔ 该提交;短 sha 够认了,完整的 40 位在标题里太占地方
    p.startsWith("/history/") ->
        p.removePrefix("/history/").substringBefore('/').take(7)
            .takeIf { it.isNotEmpty() }?.let { "$it^" to it }
    else -> null
}

/**
 * git 虚拟路径 → 仓库内相对路径:`/changes/<组>/a/b.kt` 与 `/history/<sha>/a/b.kt` 都取 `a/b.kt`
 * (前缀后面那一段分别是 GitVfs 的分组名与提交 sha,不属于仓库里的路径)。
 */
internal fun gitRelPath(p: String): String =
    p.removePrefix("/changes/").removePrefix("/history/").substringAfter('/', "")

/**
 * diff 行文本的固定配置。抽成函数是为了能单测锁住(见 `DiffLineLayoutTest`)——这三条
 * 少哪一条都会让"超长行看不全"以不同的形式回来。
 */
internal fun configureDiffLineText(tv: android.widget.TextView) {
    // ★ 省略号只能在这里关。XML 的 `ellipsize="none"` 等于"没设",而 TextView 构造函数里有
    // `if (singleLine && keyListener == null && ellipsize 未设) ellipsize = END`
    // ——**只读的单行 TextView 默认就在末尾省略**。排版本身是无限宽的(横滚滚得动),
    // 但绘制被省略号截断,于是表现成"能滚却始终看不到后面"。
    tv.ellipsize = null
    // 横滚要求按"无限宽"排版,否则超出视图宽度的部分压根不参与布局,scrollTo 只会滚出
    // 一片空白。`singleLine="true"` 内部顺带开了它,但那是副作用——显式写一遍,免得哪天
    // 换成 `maxLines="1"`(不开横滚)就悄悄失效。
    tv.setHorizontallyScrolling(true)
    // 长按选中、复制片段(而不是只能整行复制)。代价是每个 TextView 会多一个 Editor,
    // 并且变成 focusable/longClickable;横滚与竖直滚动都由 RecyclerView 层的
    // OnItemTouchListener 先行拦截,优先级在它之上,不会被抢走。
    tv.setTextIsSelectable(true)
}

/**
 * 双栏 diff 视图(左旧右新):横屏并排 + 同步滚动;竖屏一次只显示一侧,顶栏按钮切换。
 *
 * **横滑是横向滚动长行**,不是切换侧——代码行动辄超出屏幕宽度,而 [ItemDiffLineBinding]
 * 的文本是单行不折行的(折行会让两栏的行彻底对不齐,diff 就没法看了),看不到行尾就只能
 * 靠横滚。两栏共用一个 [hScroll],否则左右错开同样对不齐;行号列不跟着滚,始终留在左边。
 */
class DiffActivity : AppCompatActivity() {

    private lateinit var b: ActivityDiffBinding
    private var rows: List<Diff.Row> = emptyList()
    private var side = 1 // 竖屏当前显示侧:0=旧 1=新(默认看新版)
    /** 任意两文件对比(目录对比页进来),而不是 git 的旧/新两版。 */
    private var pairMode = false
    /** 两侧各自的来源,标题条上写完整路径用;git 模式两边是同一个文件的两版,只有左边有值。 */
    private var fileLeft: XFile? = null
    private var fileRight: XFile? = null
    private var itemSwap: MenuItem? = null
    private var itemStack: MenuItem? = null
    /** 上下两栏(而不是左右并排 / 竖屏单侧切换);记在 [Prefs] 里,下次打开还是这个。 */
    private var stacked = false
    private var syncing = false
    /** 两栏共用的横向滚动量(px)。 */
    private var hScroll = 0
    /** 最长一行的像素宽;后台量完再填,量之前不许横滚(否则不知道边界在哪)。 */
    private var maxLineWidth = 0f
    private var hFling: android.animation.ValueAnimator? = null
    /** 按行号取整侧预着色好的行(见 [highlightLines]);null = 该侧不着色。 */
    private var hlLeft: List<CharSequence>? = null
    private var hlRight: List<CharSequence>? = null
    /** 差异块(连续变更行段)的起始行下标;[blockIdx] 为当前所在块。 */
    private var blocks: List<Int> = emptyList()
    private var blockIdx = -1
    private var statBase = ""
    /** 非空 = 本次高亮生效的主题,SideAdapter 里用它兜底没被 token 覆盖的字符颜色。 */
    private var hlTheme: CodeHighlighter.Theme? = null

    // ---- 合并态(把一段差异搬到对侧,见 [mergeBlock]) ----
    /** 两侧当前的行序列;合并改的是它们,[rows] 每次由它们重算。 */
    private var linesLeft: List<String> = emptyList()
    private var linesRight: List<String> = emptyList()
    /** 各侧原文件末尾有没有换行——切行时唯一丢掉的信息,写回要原样还回去。 */
    private var nlLeft = false
    private var nlRight = false
    /** 打开时的内容,与当前行序列一比即知某侧是否有未保存改动。 */
    private var baseLeft: List<String> = emptyList()
    private var baseRight: List<String> = emptyList()
    /** 能不能合并;不能时 [mergeBlocked] 是理由(点了箭头 toast 出来),null = 压根没这回事。 */
    private var mergeable = false
    private var mergeBlocked: String? = null
    /** 各侧能不能写。只有一侧只读时(比如对面是压缩包内的条目)另一个方向照样能合。 */
    private var writableLeft = false
    private var writableRight = false
    /** 每次合并前的两侧快照,撤销就是弹一层。 */
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
                // 换布局后一行的可见宽变了(左右并排是半屏、上下并排是整屏),
                // 原来的横向位置可能已经越界
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
            isVisible = false // 有未保存改动才出现
            setOnMenuItemClickListener { save(); true }
        }
        itemUndo = b.toolbar.menu.add(getString(R.string.diff_undo)).apply {
            isVisible = false
            setOnMenuItemClickListener { undo(); true }
        }
        b.toolbar.menu.showIcons() // 撤销掉进溢出菜单,也要有图标

        // 合并这两个按钮不进 toolbar——挤,而且离要合并的那行内容太远,不直观。改用悬浮
        // 胶囊贴在当前差异块旁边(见 updateMergePill),这里只接线点击行为。
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
        // 从对比页哪一侧点进来的,竖屏就先显示哪一侧——点的是左边那份,当然想先看左边
        if (pairMode) side = intent.getIntExtra(EXTRA_SIDE, 0)
        applyLayoutMode()

        val tooBig = pairMode && maxOf(
            intent.getLongExtra(EXTRA_L_SIZE, 0L),
            intent.getLongExtra(EXTRA_R_SIZE, 0L),
        ) > PAIR_MAX_BYTES

        lifecycleScope.launch {
            if (tooBig) {
                b.loading.visibility = View.GONE
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
                b.tvEmpty.text = getString(R.string.diff_binary)
                b.tvEmpty.visibility = View.VISIBLE
                return@launch
            }
            if (pairMode) {
                // 只有两侧都是真实文件时才谈得上"搬过去再写回"。git 那边左侧是 HEAD /
                // 暂存区这种虚拟版本,写回去的含义是 stage/checkout,是另一回事。
                val l = fileLeft!!
                val r = fileRight!!
                val w = withContext(Dispatchers.IO) { canWriteTo(l) to canWriteTo(r) }
                writableLeft = w.first
                writableRight = w.second
                mergeable = writableLeft || writableRight
                // 读取端写死 UTF-8:不是合法 UTF-8 的文件本来就显示成乱码,再写回去
                // 等于把原字节永久毁掉。仍给按钮,点了 toast 说明原因(与文本编辑器一致)。
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
     * 由两侧当前的行序列算出 [rows]/高亮/差异块并铺到界面。合并之后再走一遍——合并掉
     * 一处,后面所有块的行下标都变了,不整体重算就会指到错的行上。
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
            // 整侧背景换成主题底色,不然深色主题的浅色前景字会叠在系统默认的
            // 亮色列表背景上看不清——之前靠强制回退浅色主题绕开这个问题,现在
            // 换成让背景跟着主题走,深色主题也能正常用。
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
            // 整表重绑:合并会把行整段增删,位置对不上,payload 局部刷新没有意义
            b.listLeft.adapter?.notifyDataSetChanged()
            b.listRight.adapter?.notifyDataSetChanged()
        }
        b.tvEmpty.text = getString(R.string.diff_identical)
        b.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        refreshSideTitles()
        refreshToolbar()
        updateMergePill() // 行还没重新布局,多半只是先隐藏;setBlock 的 post 会再摆一次
        measureMaxLineWidth() // 横滚的边界靠它,量完之前横滚被自然禁掉
        // 打开即定位到第一处差异(等布局完拿到视口高)
        if (first && blocks.isNotEmpty()) b.listRight.post { setBlock(0) }
    }

    // ---- 合并差异 ----

    /**
     * 把当前所在的差异块整段搬到对侧。**只改内存**,写盘要另点保存:一处一处挑着合的
     * 时候,每合一次就写回去既慢(网络来源尤其)又没法反悔。
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
     * 重新渲染并停在原来那一处。合并掉当前块之后它就不存在了,同一个下标顺延指到的正是
     * **下一处**差异——正好是接着往下合的位置,不用手动再跳一次。
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
            // 逐侧认账:一侧存上了另一侧失败时,存上的那侧不能还标着"未保存",
            // 否则用户再点保存会把它又写一遍
            if (okL) baseLeft = snapL
            if (okR) baseRight = snapR
            if (okL || okR) setResult(RESULT_OK) // 对比页据此就地重判这一对,不整树重扫
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
        if (saving) return // 写入进行中,别让 Activity 跑掉
        if (!dirtyLeft && !dirtyRight) return finish()
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_discard_title)
            .setMessage(R.string.viewer_discard_msg)
            .setPositiveButton(R.string.viewer_save) { _, _ -> save(exitAfter = true) }
            .setNegativeButton(R.string.viewer_discard_ok) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    /** toolbar 上跟合并/保存状态有关的那几项:保存按钮的出现、撤销的出现、标题的 `*`。 */
    private fun refreshToolbar() {
        itemSave?.isVisible = dirtyLeft || dirtyRight
        itemUndo?.isVisible = undoStack.isNotEmpty()
        itemUndo?.icon = tinted(R.drawable.ic_undo, R.color.text_primary)
        // 标题带 * 是"有未保存改动"的常驻提示——保存图标在窄屏上挤没了也还看得见
        b.toolbar.title = if (dirtyLeft || dirtyRight) "*$fileTitle" else fileTitle
    }

    /**
     * 合并悬浮胶囊:位置贴着当前定位的那块差异([blockIdx]),跟着滚动移动;那块滚出
     * 视口就整体隐藏(没有意义的位置不如不显示)。
     *
     * - **左右并排**(横屏,或上下布局):胶囊卡在两栏中间的分隔线上,y 取该行在
     *   [b.listLeft] 里的位置——两栏行高严格一致、又同步滚动,取哪栏的 y 都一样。
     * - **上下并排**:分隔线是水平的,不提供有意义的 x 参照,退回屏幕水平居中;
     *   上下两栏各自独立占半屏,y 优先取上栏([b.listLeft])里的位置,那行滚出上栏
     *   (仍在下栏)才退而取下栏的。
     * - **竖屏单栏切换**:同上退回居中,y 取当前显示那一栏的位置。
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
     * [row] 这一行在 [rv] 里当前的竖直中心,换算到 [b.overlayHost] 的坐标系;
     * 那一行没被布局出来(滚出视口)就返回 null。
     */
    private fun rowCenterY(rv: RecyclerView, row: Int): Float? {
        val child = (rv.layoutManager as LinearLayoutManager).findViewByPosition(row) ?: return null
        val (_, top) = locationWithin(child, b.overlayHost)
        return top + child.height / 2f
    }

    /** [view] 左上角相对 [container] 的坐标(两者可能不在同一父子链上,靠屏幕坐标换算)。 */
    private fun locationWithin(view: View, container: View): Pair<Float, Float> {
        val a = IntArray(2)
        val b0 = IntArray(2)
        view.getLocationOnScreen(a)
        container.getLocationOnScreen(b0)
        return (a[0] - b0[0]).toFloat() to (a[1] - b0[1]).toFloat()
    }

    /** 滚动(含横竖屏切换后的重新布局)要跟着重摆悬浮胶囊的位置。 */
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

    // ---- 语法高亮 ----

    /**
     * 用户在 [Prefs.codeTheme] 选的主题深浅色跟系统当前深浅色模式冲突时(比如系统
     * 浅色模式下选的是 Monokai),临时换成对应深浅色的默认主题,让 diff 区域跟
     * toolbar/系统状态栏这些还是跟着系统走的 chrome 不撞色;不冲突就直接用用户选的。
     * 只影响这次显示,不改 [Prefs.codeTheme] 本身。增删行底色([DEL_BG]/[ADD_BG])是
     * 半透明叠色,盖在任意背景上都会自然偏红/偏绿,不用为换后的主题单独调配色;
     * 真正需要配合的是 onCreate 里把整侧背景同步换成 [CodeHighlighter.Theme.bg]。
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
     * 整侧文本一次词法着色(块注释/跨行字符串状态才正确),再按行切成带 span 的片段,
     * 行号即下标。超过 [CodeHighlighter.MAX_HIGHLIGHT] 不着色。
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
        if (text.endsWith("\n")) out.removeAt(out.size - 1) // 与 toLines 一致:末尾换行不算一行
        return out
    }

    // ---- 差异块导航 ----

    private fun jump(dir: Int) {
        if (blocks.isEmpty()) return
        setBlock(((blockIdx + dir) % blocks.size + blocks.size) % blocks.size) // 循环
    }

    private fun setBlock(i: Int) {
        blockIdx = i
        val visible = if (!isLandscape && side == 0) b.listLeft else b.listRight
        val off = visible.height / 3
        (b.listLeft.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(blocks[i], off)
        (b.listRight.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(blocks[i], off)
        refreshSubtitle()
        // scrollToPositionWithOffset 只是排上了下一次布局,这一刻子 View 还在老位置——
        // 悬浮胶囊要等布局落定才知道该摆哪,跟横滚边界量宽的道理一样都得 post。
        b.listLeft.post { updateMergePill() }
    }

    private fun isBinary(bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        if (bytes.size > 4 shl 20) return true // >4MB 不做行 diff
        val n = minOf(bytes.size, 8192)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        return false
    }

    // ---- 横竖屏布局 ----

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyLayoutMode() {
        refreshToolbar()

        b.panes.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val sideBySide = !stacked && isLandscape
        // 上下并排与横屏左右并排都是两栏全显;只有竖屏的左右模式才一次看一侧
        b.sideLeft.visibility = if (stacked || sideBySide || side == 0) View.VISIBLE else View.GONE
        b.sideRight.visibility = if (stacked || sideBySide || side == 1) View.VISIBLE else View.GONE
        b.divider.visibility = if (stacked || sideBySide) View.VISIBLE else View.GONE

        // 两栏在主轴上各占一半:左右并排是宽,上下并排是高
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
        // 分隔线方向、哪栏可见都变了,悬浮胶囊的参照系跟着变——等这轮布局落定再重摆
        b.listLeft.post { updateMergePill() }
    }

    /**
     * 每栏标题:侧别 + 路径。两侧路径通常只差中间一小段,光看文件名分不出谁是谁,
     * 而 toolbar 标题只有文件名。
     */
    private fun refreshSideTitles() {
        val l = getString(if (pairMode) R.string.compare_side_left else R.string.diff_old)
        val r = getString(if (pairMode) R.string.compare_side_right else R.string.diff_new)
        if (pairMode) {
            b.titleLeft.text = fileLeft?.let { "$l · ${Format.pathLabel(it)}" } ?: l
            b.titleRight.text = fileRight?.let { "$r · ${Format.pathLabel(it)}" } ?: r
            return
        }
        // ★ git 模式给的是**虚拟路径**(/changes/<组>/… 、/history/<sha>/…),
        // 直接套 Format.pathLabel 会显示成 "git:/changes/…"——那既不是磁盘上的路径,
        // 前缀那段也只是 GitVfs 的分组名/提交 sha,对用户没有意义。剥掉前缀只留仓库内
        // 相对路径。两栏都写:虽然是同一个文件的两个版本、路径必然相同,但只给一边写、
        // 另一边空着,看着就像"右边这版没有出处"。
        val p = fileLeft?.path.orEmpty()
        val rel = gitRelPath(p)
        val src = gitSideSources(this, p)
        // 同样叫"旧/新",staged 比的是 HEAD↔暂存区、unstaged 比的是暂存区↔工作区,
        // 光写"旧/新"看不出这次到底比了哪两个版本
        val lt = src?.first?.takeIf { it.isNotEmpty() }?.let { "$l($it)" } ?: l
        val rt = src?.second?.takeIf { it.isNotEmpty() }?.let { "$r($it)" } ?: r
        b.titleLeft.text = if (rel.isEmpty()) lt else "$lt · $rel"
        b.titleRight.text = if (rel.isEmpty()) rt else "$rt · $rel"
    }



    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 整份读一侧;走 [FsRegistry] 所以本地/压缩包内/网络来源都一样。 */
    private fun readSide(f: XFile): ByteArray =
        FsRegistry.of(f).openInput(f).use { OpenFiles.readAllBytes(it) }

    private fun refreshSubtitle() {
        if (statBase.isEmpty()) return
        val pos = if (blockIdx >= 0) " · ${blockIdx + 1}/${blocks.size}" else ""
        // 不再往这里塞"左/右""旧/新":那是每栏标题条的活儿,写两遍只会把本来就窄的
        // 副标题挤到显示不全(竖屏尤其明显)
        b.toolbar.subtitle = "$statBase$pos"
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayoutMode()
        // 换方向后一行的可见宽度变了,原来的横向位置可能已经越界
        b.listLeft.post { setHScroll(hScroll) }
    }

    override fun onDestroy() {
        hFling?.cancel()
        super.onDestroy()
    }

    /**
     * 横滑 = 横向滚动长行。判定为横向意图后**接管整串事件**,不然 RecyclerView 还会
     * 跟着做竖直滚动,手感是斜着飘。切换侧交给顶栏按钮,不再抢这个手势。
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
                        // 明确横向才接管:横向超过 slop 且比纵向多出一半以上,
                        // 免得把正常的上下滚动误判成横滚
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

    /** 能横滚多远:最长行的宽度减去一行文本区的可见宽度。 */
    private fun hScrollMax(): Int {
        val vw = visibleTextWidth()
        if (vw <= 0) return 0
        return (maxLineWidth - vw).coerceAtLeast(0f).toInt()
    }

    /** 文本区(扣掉左边固定的行号列)的可见宽度;还没有行时退回整个列表宽度。 */
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

    /** 把当前横向滚动量刷到某一栏已经绑好的行上(新绑定的行在 onBindViewHolder 里各自应用)。 */
    private fun applyHScroll(rv: RecyclerView) {
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? VH ?: continue
            holder.b.tvText.scrollTo(hScroll, 0)
        }
    }

    /**
     * 量出最长一行有多宽,横滚的边界靠它。放后台是因为要逐行 measureText,大文件几万行;
     * 量完之前 [hScrollMax] 返回 0,横滚被自然禁掉,不会滚进一片空白。
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
        // 带上滚动位置
        val lm = from.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        val off = from.getChildAt(0)?.top ?: 0
        applyLayoutMode()
        if (pos >= 0) (dest.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, off)
        // 切过去那一栏的行可能是早先绑的,带的还是旧的横向位置
        dest.post { applyHScroll(dest) }
    }

    /** 横屏两栏同步滚动。 */
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

    // ---- 列表 ----

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
            // 没被 token 覆盖的字符(标点/空白/未识别语言)沿用 XML 默认色,除非本次
            // 高亮生效——那样整侧背景已换成主题底色,默认字色也要跟着换,不然大半
            // 字符还是 app 自己的 text_primary,深色主题背景上可能是黑字看不清。
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

        /** patience diff 是内存算法,两侧都得整份读进来再切行——超过这个大小直接拒绝。 */
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
         * 对比任意两个文件(目录对比页点进来的那条路)。与 git 模式共用整套双栏渲染,
         * 只是文本来源从 `GitFileSystem.diffSides` 换成两侧各读一份。
         *
         * 给的是 Intent 而不是直接启动:这条路要拿返回值——页内合并差异并保存过的话,
         * 回 RESULT_OK 让对比页就地重判这一对的状态。
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
