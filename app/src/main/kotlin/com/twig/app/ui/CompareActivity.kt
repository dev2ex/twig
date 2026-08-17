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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.CompareSession
import com.twig.app.CompareStore
import com.twig.app.Connections
import com.twig.app.Format
import com.twig.app.OpenFiles
import com.twig.app.R
import com.twig.app.compareSideOf
import com.twig.app.databinding.ActivityCompareBinding
import com.twig.app.databinding.ItemCompareRowBinding
import com.twig.app.databinding.ItemCompareStateBinding
import com.twig.app.resolveCompareSide
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 目录对比(Beyond Compare 式):两侧逐行对齐,中间一列状态符号,横屏并排 + 同步滚动,
 * 竖屏一次显示一侧、左右滑动切换(状态列一直在,所以状态永远看得见)。
 *
 * 「活动侧」照搬双面板:路径栏高亮的那侧是**操作的源**,复制/同步都是"活动侧 → 对侧",
 * 删除删的是活动侧。动作都在顶栏菜单与行长按菜单里,不再另占一条操作列。
 *
 * 扫描是全量递归的(见 [scanCompare]),所以停止与排除规则是刚需——网络来源的大树扫起来
 * 是分钟级。结果树在主线程构建:扫描协程只发不可变的增量事件,与 [scanDirStat] 那条
 * "只取数据,落表在收集方做"的规则一致。
 */
class CompareActivity : AppCompatActivity() {

    /** 结果树的一个节点。[key] 是相对两侧根的路径,根为空串。 */
    private class Node(
        val key: String,
        val name: String,
        val depth: Int,
        val isDir: Boolean,
        var left: XFile?,
        var right: XFile?,
        var state: PairState,
        var expanded: Boolean = false,
    ) {
        val children = ArrayList<Node>()
        fun sideFile(side: Int) = if (side == 0) left else right
    }

    private lateinit var b: ActivityCompareBinding

    private var leftRoot: XFile? = null
    private var rightRoot: XFile? = null
    private var options = CompareOptions()

    private var root = Node("", "", -1, isDir = true, left = null, right = null, state = PairState.SCANNING, expanded = true)
    private val nodeByKey = HashMap<String, Node>()
    private var rows: List<Node> = emptyList()
    private val selected = HashSet<String>()

    private var scanJob: Job? = null
    private var scanning = false
    private var diffOnly = false
    private var stats = CompareEvent.Progress(0, 0, "")
    // 扫描中途排除掉的量。扫描线程仍在按自己的计数往上报,不扣掉的话统计会跳回去
    private var goneEntriesTotal = 0
    private var goneDiffsTotal = 0

    /** 竖屏当前显示侧;横屏两侧都在,这个值只跟着 [activeSide] 走。 */
    private var side = 0
    /** 活动侧 = 操作的源侧(0=左 1=右),路径栏高亮那一侧。 */
    private var activeSide = 0
    private var syncing = false

    private var transferBox: TransferBox? = null
    /** 本次传输涉及的节点,传输结束后只重判这些,不整树重扫。 */
    private var pendingNodes: List<Node> = emptyList()

    /** 规则 → 被它挡掉的项(所在目录 key, 项名)。删规则时靠它精确恢复,不必整树重扫。 */
    private val excludedByRule = HashMap<String, LinkedHashSet<Pair<String, String>>>()

    private var itemRefresh: MenuItem? = null
    private var itemSwap: MenuItem? = null
    private var itemDiffOnly: MenuItem? = null
    private var itemExpand: MenuItem? = null
    private var itemCopy: MenuItem? = null
    /** 顶栏上那个复制快捷键,只在有选中时露出来;溢出菜单里那条一直都在。 */
    private var itemCopyQuick: MenuItem? = null
    private var itemSync: MenuItem? = null
    private var itemDelete: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCompareBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.title = getString(R.string.compare_title)
        b.toolbar.setNavigationOnClickListener { finish() }
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(getString(R.string.compare_title)))

        b.listLeft.layoutManager = LinearLayoutManager(this)
        b.listRight.layoutManager = LinearLayoutManager(this)
        b.listState.layoutManager = LinearLayoutManager(this)
        b.listLeft.adapter = SideAdapter(left = true)
        b.listRight.adapter = SideAdapter(left = false)
        b.listState.adapter = StateAdapter()
        b.listLeft.itemAnimator = null
        b.listRight.itemAnimator = null
        b.listState.itemAnimator = null
        // 三列两两联动:谁滚都要带上另外两列,否则状态符号会跟行错开
        linkScroll(b.listLeft, b.listRight, b.listState)
        linkScroll(b.listRight, b.listLeft, b.listState)
        linkScroll(b.listState, b.listLeft, b.listRight)
        installSwipe(b.listLeft)
        installSwipe(b.listRight)
        installSwipe(b.listState)
        b.pathBarLeft.setOnClickListener { setActiveSide(0) }
        b.pathBarRight.setOnClickListener { setActiveSide(1) }
        buildMenu()
        applyLayoutMode()

        val sid = intent.getStringExtra(EXTRA_SESSION_ID)
        if (sid != null) {
            val session = CompareStore.all(this).firstOrNull { it.id == sid }
            if (session == null) { toast(getString(R.string.compare_open_failed)); return finish() }
            loadSession(session)
            return
        }
        val ls = intent.getStringExtra(EXTRA_LEFT_SCHEME)
        val lp = intent.getStringExtra(EXTRA_LEFT_PATH)
        val rs = intent.getStringExtra(EXTRA_RIGHT_SCHEME)
        val rp = intent.getStringExtra(EXTRA_RIGHT_PATH)
        if (ls == null || lp == null || rs == null || rp == null) return finish()
        leftRoot = XFile(ls, lp, isDir = true)
        rightRoot = XFile(rs, rp, isDir = true)
        options = loadCompareOptions(this)
        refreshPaths()
        startScan()
    }

    override fun onResume() {
        super.onResume()
        showTransferBox() // 从通知栏点回来时把进度框接上
        // 从对比页/查看器返回时再校正一次:竖屏下"看的是哪侧"与"活动侧"必须一致
        if (!isLandscape && activeSide != side) setActiveSide(side)
    }

    override fun onDestroy() {
        scanJob?.cancel()
        transferBox?.detach()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayoutMode()
    }

    // ---- 扫描 ----

    private fun startScan() {
        val l = leftRoot ?: return
        val r = rightRoot ?: return
        scanJob?.cancel()
        root = Node("", "", -1, isDir = true, left = l, right = r, state = PairState.SCANNING, expanded = true)
        nodeByKey.clear()
        nodeByKey[""] = root
        rows = emptyList()
        selected.clear()
        stats = CompareEvent.Progress(0, 0, "")
        goneEntriesTotal = 0
        goneDiffsTotal = 0
        excludedByRule.clear()
        itemExpand?.setTitle(R.string.compare_expand_all) // 树重建了,两态标题也回到初始
        notifyBoth()
        scanJob = lifecycleScope.launch { runScan(root) }
    }

    /** 把 [prefix] 这棵子树内部的相对 key 拼成整树里的绝对 key。 */
    private fun fullKey(prefix: String, sub: String) = when {
        prefix.isEmpty() -> sub
        sub.isEmpty() -> prefix
        else -> "$prefix/$sub"
    }

    /**
     * 扫描 [node] 这棵子树,结果挂在它下面;[node] 是 [root] 时就是整树。
     * 抽出来是为了让「复制/删除之后」只重扫真正受影响的那个目录,而不是整棵树重来。
     */
    private suspend fun runScan(node: Node) {
        // 允许一侧为空:恢复"只在某一侧"的目录时要靠它,缺的那侧自然全判成"只在另一侧"
        if (node.left == null && node.right == null) return
        node.children.forEach { dropIndex(it) }
        node.children.clear()
        setScanning(true)
        var lastUi = 0L
        try {
            scanCompare(node.left, node.right, options).collect { ev ->
                when (ev) {
                    is CompareEvent.Children -> addChildren(fullKey(node.key, ev.dirKey), ev.rows)
                    is CompareEvent.DirDone -> nodeByKey[fullKey(node.key, ev.dirKey)]?.let { n ->
                        n.state = ev.state
                        // 有差异的路径自动铺开:用户要的就是这些,不该还得手点一层层展
                        if (ev.state == PairState.DIFF) n.expanded = true
                    }
                    // 子树重扫时这个计数只覆盖子树,拿它当全局统计会把数字打回去;
                    // 整树扫描才用它做实时进度,结束时一律以 recountStats 为准
                    is CompareEvent.Progress -> if (node === root) {
                        stats = ev.copy(
                            entries = (ev.entries - goneEntriesTotal).coerceAtLeast(0),
                            diffs = (ev.diffs - goneDiffsTotal).coerceAtLeast(0),
                        )
                    }
                    // 记下"谁挡掉了什么",删这条规则时才能只把它挡掉的恢复回来
                    is CompareEvent.Excluded -> excludedByRule
                        .getOrPut(ev.rule) { LinkedHashSet() }
                        .add(fullKey(node.key, ev.dirKey) to ev.name)
                    CompareEvent.Truncated -> toast(getString(R.string.compare_truncated))
                }
                val now = System.currentTimeMillis()
                // 每条事件都 rebuild 会把大树的主线程打满;150ms 一次足够"边扫边看"
                if (now - lastUi > 150L) {
                    lastUi = now
                    rebuild()
                }
            }
        } finally {
            setScanning(false)
            recountStats()
            rebuild()
        }
    }

    /** 按当前树重新点一遍统计,局部重扫/摘除之后拿它校正,免得数字越走越偏。 */
    private fun recountStats() {
        var e = 0
        var d = 0
        for (n in nodeByKey.values) {
            if (n.key.isEmpty()) continue // 根不是一个条目
            e++
            if (n.state != PairState.SAME) d++
        }
        stats = stats.copy(entries = e, diffs = d)
    }

    private fun addChildren(dirKey: String, entries: List<CompareEntry>) {
        val parent = nodeByKey[dirKey] ?: return
        parent.children.clear()
        for (e in entries) {
            val key = fullKey(dirKey, e.name)
            val n = Node(key, e.name, parent.depth + 1, e.isDir, e.left, e.right, e.state)
            parent.children += n
            nodeByKey[key] = n
        }
    }

    private fun setScanning(on: Boolean) {
        scanning = on
        b.progress.visibility = if (on) View.VISIBLE else View.GONE
        // ★ 别用 setIcon(资源 id):那会换成未染色的 drawable,图标原色是黑的,
        // 在绿色 toolbar 上就成了一个黑按钮。要染好色再塞进去。
        itemRefresh?.icon = tinted(if (on) R.drawable.ic_stop else R.drawable.ic_refresh, R.color.white)
        itemRefresh?.setTitle(if (on) R.string.compare_stop else R.string.compare_refresh)
    }

    // ---- 结果树 → 可见行 ----

    private fun rebuild() {
        val out = ArrayList<Node>()
        flatten(root, out)
        rows = out
        notifyBoth()
        refreshSubtitle()
        b.tvEmpty.visibility = if (rows.isEmpty() && !scanning) View.VISIBLE else View.GONE
        b.tvEmpty.text = getString(if (diffOnly) R.string.compare_no_diff else R.string.compare_empty)
    }

    private fun flatten(n: Node, out: MutableList<Node>) {
        for (c in n.children) {
            // "只看差异"隐藏认定相同的;还没扫完的目录(SCANNING)不能藏,否则看着像漏了
            if (diffOnly && c.state == PairState.SAME) continue
            out += c
            if (c.isDir && c.expanded) flatten(c, out)
        }
    }

    private fun notifyBoth() {
        b.listLeft.adapter?.notifyDataSetChanged()
        b.listRight.adapter?.notifyDataSetChanged()
        b.listState.adapter?.notifyDataSetChanged()
    }

    private fun setAllExpanded(expanded: Boolean) {
        fun walk(n: Node) {
            for (c in n.children) if (c.isDir) { c.expanded = expanded; walk(c) }
        }
        walk(root)
        rebuild()
    }

    // ---- 顶栏菜单 ----

    private fun buildMenu() {
        val m = b.toolbar.menu
        m.showIcons()
        // ★ Toolbar 的 ALWAYS 图标按 add() 顺序从左到右排——复制要落在刷新左边,
        // 就必须先加它。放最左是因为它不常驻(没选中时隐藏),露出来时不该把
        // 刷新/切换这两个常驻按钮的位置往右挤动,视觉上更稳。
        itemCopyQuick = m.add("").apply {
            icon = tinted(R.drawable.ic_copy, R.color.white)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false // 有选中才亮出来,见 refreshSubtitle
            setOnMenuItemClickListener { actionCopy(); true }
        }
        itemRefresh = m.add(getString(R.string.compare_refresh)).apply {
            icon = tinted(R.drawable.ic_refresh, R.color.white)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { if (scanning) scanJob?.cancel() else startScan(); true }
        }
        itemSwap = m.add(getString(R.string.compare_switch_side)).apply {
            icon = tinted(R.drawable.ic_pane_to_right, R.color.white) // 真正的朝向由 setActiveSide 定
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                // 竖屏切的是"看哪一侧"(顺带把活动侧带过去);横屏两侧都在,切的是活动侧
                if (isLandscape) setActiveSide(1 - activeSide) else switchSide(1 - side)
                true
            }
        }
        itemDiffOnly = m.add(getString(R.string.compare_diff_only)).apply {
            isCheckable = true
            icon = menuIcon(R.drawable.ic_filter)
            setOnMenuItemClickListener {
                diffOnly = !diffOnly
                it.isChecked = diffOnly
                rebuild()
                true
            }
        }
        itemExpand = m.add(getString(R.string.compare_expand_all)).apply {
            icon = menuIcon(R.drawable.ic_chevron_down)
            setOnMenuItemClickListener {
                // 一个菜单项两态:还有折叠的就先全展开,全展开了再点即全折叠
                val anyCollapsed =
                    nodeByKey.values.any { n -> n.isDir && !n.expanded && n.children.isNotEmpty() }
                setAllExpanded(anyCollapsed)
                it.setTitle(if (anyCollapsed) R.string.compare_collapse_all else R.string.compare_expand_all)
                it.icon = menuIcon(if (anyCollapsed) R.drawable.ic_chevron_right else R.drawable.ic_chevron_down)
                true
            }
        }
        m.add(getString(R.string.compare_select_all)).apply {
            icon = menuIcon(R.drawable.ic_sel_check)
            setOnMenuItemClickListener { toggleSelectAll(); true }
        }
        itemCopy = m.add("").apply {
            icon = menuIcon(R.drawable.ic_copy)
            setOnMenuItemClickListener { actionCopy(); true }
        }
        itemSync = m.add("").apply {
            icon = menuIcon(R.drawable.ic_sync)
            setOnMenuItemClickListener { actionSync(); true }
        }
        itemDelete = m.add("").apply {
            icon = menuIcon(R.drawable.ic_delete)
            setOnMenuItemClickListener { actionDelete(); true }
        }
        m.add(getString(R.string.compare_text_diff)).apply {
            icon = menuIcon(R.drawable.ic_compare)
            setOnMenuItemClickListener { actionTextDiff(); true }
        }
        m.add(getString(R.string.compare_excludes)).apply {
            icon = menuIcon(R.drawable.ic_exclude)
            setOnMenuItemClickListener {
                showExcludeEditor(this@CompareActivity, options.excludes) { list ->
                    applyExcludeRules(list)
                }
                true
            }
        }
        m.add(getString(R.string.compare_options)).apply {
            icon = menuIcon(R.drawable.ic_tune)
            setOnMenuItemClickListener {
                showCompareOptions(this@CompareActivity, options) { newOpts ->
                    options = newOpts
                    startScan()
                }
                true
            }
        }
        m.add(getString(R.string.compare_save)).apply {
            icon = menuIcon(R.drawable.ic_star)
            setOnMenuItemClickListener { saveSession(); true }
        }
        m.add(getString(R.string.compare_saved)).apply {
            icon = menuIcon(R.drawable.ic_history)
            setOnMenuItemClickListener { openSaved(); true }
        }
        refreshDirectionTitles()
    }

    /** 溢出菜单里的图标:菜单背景跟着主题走,所以染成正文色而不是图标自带的黑。 */
    private fun menuIcon(res: Int) = tinted(res, R.color.text_primary)

    private fun tinted(res: Int, colorRes: Int) =
        ContextCompat.getDrawable(this, res)?.mutate()?.apply {
            setTint(ContextCompat.getColor(this@CompareActivity, colorRes))
        }

    /** 复制/同步/删除三项的标题里写死方向,省得用户点开还要猜是往哪边搬。 */
    private fun refreshDirectionTitles() {
        val other = sideName(1 - activeSide)
        itemCopy?.title = getString(R.string.compare_copy_to, other)
        itemCopyQuick?.title = getString(R.string.compare_copy_to, other)
        itemSync?.title = getString(R.string.compare_sync_to, other)
        itemDelete?.title = getString(R.string.compare_delete_side, sideName(activeSide))
        itemDiffOnly?.isChecked = diffOnly
    }

    // ---- 选择 ----

    private fun selectedNodes(): List<Node> = rows.filter { selected.contains(it.key) }

    /**
     * 去掉"祖先也被选中"的项。全选之后父目录和它的子项会同时在选中集里,照单全收的话
     * 目录会被整个复制一遍、子项再单独复制一遍(删除更糟:父删完了再删子必然报错)。
     * 目录整体交给 CopyEngine 递归处理就够了。
     */
    private fun prunedSelection(): List<Node> = selectedNodes().filter { n ->
        var p = n.key.substringBeforeLast('/', "")
        while (p.isNotEmpty()) {
            if (selected.contains(p)) return@filter false
            p = p.substringBeforeLast('/', "")
        }
        true
    }

    private fun toggleSelect(n: Node) {
        if (!selected.remove(n.key)) selected.add(n.key)
        notifyBoth()
        refreshSubtitle()
    }

    /** 全选/取消全选:按当前可见行(受「只看差异」影响),与文件列表的语义一致。 */
    private fun toggleSelectAll() {
        if (selected.isEmpty()) rows.forEach { selected.add(it.key) } else selected.clear()
        notifyBoth()
        refreshSubtitle()
    }

    // ---- 排除单项 ----

    /**
     * 排除某一项。★ **不重扫**:排除一项不影响其它已经扫出来的结果,而网络大树重扫是
     * 分钟级的,为少比一项等上几分钟没道理。这里只把这一枝从结果树上摘掉、回溯修正
     * 祖先的汇总状态与统计数字。
     *
     * (「排除规则」对话框那边仍然重扫,因为规则可以被**删掉**——之前跳过的东西得重新
     * 扫出来才知道,那是真的没有别的办法。)
     */
    private fun excludeNode(n: Node) {
        // 根下的项只有名字可用,会连带排除深层同名项;有路径的就锚死这一条
        if (n.key !in options.excludes) {
            options = options.copy(excludes = options.excludes + n.key)
            saveCompareOptions(this, options)
        }
        excludedByRule.getOrPut(n.key) { LinkedHashSet() }
            .add(n.key.substringBeforeLast('/', "") to n.name)
        val (goneEntries, goneDiffs) = detachNode(n)
        goneEntriesTotal += goneEntries
        goneDiffsTotal += goneDiffs
        recountStats()
        rebuild()
    }

    /** 把一枝从结果树上摘掉(含索引与选中集),返回摘掉的 (条目数, 其中有差异的数)。 */
    private fun detachNode(n: Node): Pair<Int, Int> {
        nodeByKey[n.key.substringBeforeLast('/', "")]?.children?.remove(n)
        var entries = 0
        var diffs = 0
        fun drop(x: Node) {
            entries++
            if (x.state != PairState.SAME) diffs++
            nodeByKey.remove(x.key)
            selected.remove(x.key)
            x.children.forEach(::drop)
        }
        drop(n)
        recomputeAncestors(n.key.substringBeforeLast('/', ""))
        return entries to diffs
    }

    /** 只清索引,不动父的 children(重扫子树前用:那棵子树整个要被新结果替换)。 */
    private fun dropIndex(n: Node) {
        nodeByKey.remove(n.key)
        selected.remove(n.key)
        n.children.forEach(::dropIndex)
    }

    /** 摘掉一枝后,祖先可能从"有差异"变成"全同",逐级往上重算。 */
    private fun recomputeAncestors(fromKey: String) {
        var k = fromKey
        while (true) {
            val n = nodeByKey[k]
            // 单侧独有的目录状态由"只在哪侧"决定,与子树内容无关,不能被改写
            if (n != null && n.state != PairState.LEFT_ONLY && n.state != PairState.RIGHT_ONLY) {
                n.state = if (n.children.any { it.state != PairState.SAME }) PairState.DIFF else PairState.SAME
            }
            if (k.isEmpty()) break
            k = k.substringBeforeLast('/', "")
        }
    }

    // ---- 复制 / 同步 / 删除 ----

    /**
     * 选中项在**对侧的对应位置**存在与否,决定它的目标目录:目标始终是"对侧根 + 该项
     * 在树里的相对父路径"。这样复制一个深层文件也会落到对侧同名子目录里,而不是堆到根上。
     */
    private fun destDirFor(n: Node, from: Int): XFile? {
        val destRoot = (if (from == 0) rightRoot else leftRoot) ?: return null
        val relParent = n.key.substringBeforeLast('/', "")
        val base = destRoot.path.trimEnd('/')
        val path = if (relParent.isEmpty()) (base.ifEmpty { "/" }) else "$base/$relParent"
        return XFile(destRoot.scheme, path, isDir = true)
    }

    private fun actionCopy() {
        val sel = prunedSelection()
        if (sel.isEmpty()) return toast(getString(R.string.msg_no_selection))
        val items = sel.mapNotNull { n -> n.sideFile(activeSide)?.let { n to it } }
        if (items.isEmpty()) return toast(getString(R.string.compare_none_on_side))
        confirmAndTransfer(items, getString(R.string.compare_copy_confirm, items.size, sideName(1 - activeSide)))
    }

    /**
     * 同步:把活动侧的**所有差异**推到对侧(只在活动侧有的 + 两侧不同的)。
     * 只在对侧有的不动——那是"对侧多出来的",删不删由用户自己决定(选中后用删除)。
     * 只在活动侧有的目录整个收走、不下探:CopyEngine 递归复制,逐个子项反而更慢更容易半途而废。
     */
    private fun actionSync() {
        val items = ArrayList<Pair<Node, XFile>>()
        val onlyThisSide = if (activeSide == 0) PairState.LEFT_ONLY else PairState.RIGHT_ONLY
        fun walk(n: Node) {
            for (c in n.children) {
                val f = c.sideFile(activeSide)
                when {
                    c.state == onlyThisSide && f != null -> items += c to f // 目录整体收走,不下探
                    c.state == PairState.DIFF && !c.isDir && f != null -> items += c to f
                    c.isDir -> walk(c)
                }
            }
        }
        walk(root)
        if (items.isEmpty()) return toast(getString(R.string.compare_nothing_to_sync))
        confirmAndTransfer(items, getString(R.string.compare_sync_confirm, items.size, sideName(1 - activeSide)))
    }

    /**
     * 确认后起传输会话。目标目录可能还不存在(勾了一个只在源侧有的目录里的深层项),
     * 发起前先逐级建好——[Transfers.Work.Sync] 那边假定目标目录已存在。
     */
    private fun confirmAndTransfer(items: List<Pair<Node, XFile>>, message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.compare_title)
            .setMessage(message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                lifecycleScope.launch {
                    val pairs = withContext(Dispatchers.IO) {
                        runCatching {
                            items.mapNotNull { (n, f) ->
                                val dir = destDirFor(n, activeSide) ?: return@mapNotNull null
                                ensureDir(dir)?.let { f to it }
                            }
                        }.getOrDefault(emptyList())
                    }
                    if (pairs.isEmpty()) return@launch toast(getString(R.string.msg_dest_not_writable))
                    pendingNodes = items.map { it.first }
                    val destRoot = (if (activeSide == 0) rightRoot else leftRoot)!!
                    val started = runCatching {
                        Transfers.start(
                            applicationContext,
                            Transfers.Session(
                                Transfers.Work.Sync(pairs, move = false),
                                R.string.progress_copy,
                                Format.pathLabel(destRoot), FileIcons.sourceIconRes(destRoot.scheme), null,
                            ),
                        )
                    }.getOrElse { toast(it.message ?: getString(R.string.err_failed)); return@launch }
                    if (!started) return@launch toast(getString(R.string.transfer_busy))
                    showTransferBox()
                }
            }
            .show()
    }

    /** 逐级建出目标目录(已存在就直接用);建不出来返回 null。**会连网,要在后台线程调**。 */
    private fun ensureDir(dir: XFile): XFile? = runCatching {
        val fs = FsRegistry.of(dir)
        if (fs.exists(dir)) return@runCatching fs.resolve(dir.path)
        val parts = dir.path.trim('/').split('/').filter { it.isNotEmpty() }
        var cur = fs.resolve("/")
        for (p in parts) {
            val next = XFile(dir.scheme, "${cur.path.trimEnd('/')}/$p", isDir = true)
            cur = if (fs.exists(next)) fs.resolve(next.path) else fs.mkdir(cur, p)
        }
        cur
    }.getOrNull()

    /** 删除活动侧的选中项。删哪一侧写在确认框里——这是不可逆操作,不能靠用户猜。 */
    private fun actionDelete() {
        val sel = prunedSelection()
        if (sel.isEmpty()) return toast(getString(R.string.msg_no_selection))
        val files = sel.mapNotNull { it.sideFile(activeSide) }
        if (files.isEmpty()) return toast(getString(R.string.compare_none_on_side))
        AlertDialog.Builder(this)
            .setTitle(R.string.compare_title)
            .setMessage(getString(R.string.compare_delete_confirm, files.size, sideName(activeSide)))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                lifecycleScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        runCatching { files.forEach { FsRegistry.of(it).delete(it) } }.exceptionOrNull()
                    }
                    toast(err?.message ?: getString(R.string.msg_done))
                    revalidate(sel) // 删掉的那几项就地重判,不整树重扫
                }
            }
            .show()
    }

    private fun actionTextDiff() {
        val sel = selectedNodes().firstOrNull { !it.isDir && it.left != null && it.right != null }
            ?: return toast(getString(R.string.compare_pick_pair))
        openPair(sel, sel.left!!, sel.right!!)
    }

    private fun sideName(s: Int) =
        getString(if (s == 0) R.string.compare_side_left else R.string.compare_side_right)

    /**
     * 规则改了。**不整树重扫**,分两头做增量:
     * - **新增的规则**:把树上匹配到的项就地摘掉
     * - **删掉的规则**:只把"当初被这条规则挡掉的项"([excludedByRule] 记着)恢复回来,
     *   目录还要顺带扫出它的子树——那棵子树当初被剪枝,从没扫过
     */
    private fun applyExcludeRules(list: List<String>) {
        val before = options.excludes.toSet()
        val after = list.toSet()
        val added = list.filter { it !in before }
        val removed = options.excludes.filter { it !in after }
        options = options.copy(excludes = list)
        saveCompareOptions(this, options)
        if (added.isEmpty() && removed.isEmpty()) return

        // 新增规则:摘掉现在树上匹配的项(先收集再摘,别边遍历边改)
        if (added.isNotEmpty()) {
            val hit = nodeByKey.values.filter { n ->
                n.key.isNotEmpty() && matchesExclude(n.name, n.key, added)
            }
            // 祖先已被摘掉的后代会跟着走,再摘一次会找不到父,先按层级从浅到深处理
            for (n in hit.sortedBy { it.depth }) {
                if (nodeByKey[n.key] !== n) continue
                excludeRuleFor(n.name, n.key, added)?.let { rule ->
                    excludedByRule.getOrPut(rule) { LinkedHashSet() }
                        .add(n.key.substringBeforeLast('/', "") to n.name)
                }
                val (e, d) = detachNode(n)
                goneEntriesTotal += e
                goneDiffsTotal += d
            }
        }

        if (removed.isEmpty()) {
            recountStats()
            rebuild()
            return
        }

        // 删掉的规则:把它当初挡下的项恢复回来
        val toRestore = removed.flatMap { excludedByRule.remove(it).orEmpty() }
            // 可能还有别的规则照样挡着它,那就别恢复
            .filter { (parentKey, name) -> excludeRuleFor(name, fullKey(parentKey, name), list) == null }
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            setScanning(true)
            for ((parentKey, name) in toRestore) {
                val parent = nodeByKey[parentKey] ?: continue
                if (parent.children.any { it.name == name }) continue // 已经在树上了
                val key = fullKey(parentKey, name)
                // 元数据必须来自 list(),理由同 statSides
                val l = withContext(Dispatchers.IO) { lookupChild(leftRoot, parentKey, name) }
                val r = withContext(Dispatchers.IO) { lookupChild(rightRoot, parentKey, name) }
                val rep = l ?: r ?: continue
                val node = Node(
                    key, name, parent.depth + 1, rep.isDir, l, r,
                    when {
                        l == null -> PairState.RIGHT_ONLY
                        r == null -> PairState.LEFT_ONLY
                        rep.isDir -> PairState.SCANNING
                        else -> withContext(Dispatchers.IO) { compareFiles(l, r, options) }
                    },
                )
                parent.children += node
                nodeByKey[key] = node
                // 目录:子树当初被剪枝、从没扫过,现在补扫。单侧独有的也走同一条路
                // (scanCompare 两侧可空,缺的那侧自然全判成"只在另一侧")
                if (node.isDir) runScan(node)
                recomputeAncestors(parentKey)
            }
            setScanning(false)
            recountStats()
            rebuild()
        }
    }

    /** 在某个目录里按名字找一项,元数据取自 `list()`。**会碰网络,放后台线程调**。 */
    private fun lookupChild(rootFile: XFile?, parentKey: String, name: String): XFile? {
        val base = rootFile ?: return null
        val dirPath = if (parentKey.isEmpty()) base.path else "${base.path.trimEnd('/')}/$parentKey"
        return runCatching {
            FsRegistry.of(base.scheme)
                .list(XFile(base.scheme, dirPath, isDir = true))
                .firstOrNull {
                    if (options.ignoreCase) it.name.equals(name, ignoreCase = true) else it.name == name
                }
        }.getOrNull()
    }

    // ---- 操作之后的局部重判 ----

    /**
     * 复制/同步/删除之后只重判受影响的那些项。整树重扫在网络上是分钟级的,而一次操作
     * 改变的只是它碰过的那几项(以及目录的话,那一棵子树)。
     *
     * - **文件**:两侧各重新 stat 一次再判一次状态;两边都没了就从树上摘掉
     * - **目录**:整棵子树重扫一遍——复制一个目录过去,对侧多出来的是一整棵树,
     *   靠 stat 一项是问不出来的
     */
    private fun revalidate(nodes: List<Node>) {
        val alive = nodes.filter { nodeByKey[it.key] === it }
        if (alive.isEmpty()) return
        val files = alive.filterNot { it.isDir }
        val dirs = alive.filter { it.isDir }
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            setScanning(true) // 重判也要读盘/走网络,别让界面看着像卡住
            if (files.isNotEmpty()) {
                val updates = withContext(Dispatchers.IO) {
                    val lm = statSides(files, leftRoot)
                    val rm = statSides(files, rightRoot)
                    files.map { n ->
                        val l = lm[n.key]
                        val r = rm[n.key]
                        Triple(
                            n, l to r,
                            when {
                                l == null && r == null -> null // 两边都没了
                                l == null -> PairState.RIGHT_ONLY
                                r == null -> PairState.LEFT_ONLY
                                else -> compareFiles(l, r, options)
                            },
                        )
                    }
                }
                for ((n, sides, st) in updates) {
                    if (st == null) {
                        detachNode(n)
                    } else {
                        n.left = sides.first
                        n.right = sides.second
                        n.state = st
                        recomputeAncestors(n.key.substringBeforeLast('/', ""))
                    }
                }
                rebuild()
            }
            val dirL = withContext(Dispatchers.IO) { statSides(dirs, leftRoot) }
            val dirR = withContext(Dispatchers.IO) { statSides(dirs, rightRoot) }
            for (d in dirs) {
                if (nodeByKey[d.key] !== d) continue // 可能已被上面的摘除带走
                // 目录本身也可能刚被复制出来/删掉,两侧先各自重新定位一次
                val l = dirL[d.key]
                val r = dirR[d.key]
                d.left = l
                d.right = r
                when {
                    l == null && r == null -> { detachNode(d); continue }
                    l == null -> { d.state = PairState.RIGHT_ONLY; continue }
                    r == null -> { d.state = PairState.LEFT_ONLY; continue }
                }
                runScan(d)
                recomputeAncestors(d.key.substringBeforeLast('/', ""))
            }
            // 与文件面板一样,操作完就把选中清掉
            selected.clear()
            setScanning(false)
            recountStats()
            rebuild()
        }
    }

    /**
     * 重新取这批节点在某一侧的最新元数据(不存在的不出现在结果里)。
     *
     * ★ **只能走 `list()`,不能用 `resolve()`**:`resolve` 各实现只保证"能定位",不保证
     * 回填元数据——`SmbFileSystem.resolve` 就是直接 `XFile(scheme, path, isDir = true)`,
     * size/mtime 全是 0、isDir 还硬编码成 true。树上的行本来是 `list()` 给的,混用两者
     * 就会出现"复制完文件显示成 0B"。按父目录分组,一个目录只列一次。
     *
     * **会碰网络,放后台线程调**。
     */
    private fun statSides(nodes: List<Node>, rootFile: XFile?): Map<String, XFile> {
        val base = rootFile ?: return emptyMap()
        val fs = runCatching { FsRegistry.of(base.scheme) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, XFile>()
        for ((parentKey, group) in nodes.groupBy { it.key.substringBeforeLast('/', "") }) {
            val dirPath =
                if (parentKey.isEmpty()) base.path else "${base.path.trimEnd('/')}/$parentKey"
            val listed = runCatching { fs.list(XFile(base.scheme, dirPath, isDir = true)) }
                .getOrDefault(emptyList())
            val byName = HashMap<String, XFile>()
            for (f in listed) byName[nameKey(f.name, f.isDir)] = f
            for (n in group) byName[nameKey(n.name, n.isDir)]?.let { out[n.key] = it }
        }
        return out
    }

    /** 与 [pairEntries] 同一套配对键:大小写按选项,目录与文件同名不算一个。 */
    private fun nameKey(name: String, isDir: Boolean) =
        (if (options.ignoreCase) name.lowercase() else name) + (if (isDir) "/" else "")

    // ---- 传输进度 ----

    private fun showTransferBox() {
        val session = Transfers.active ?: return
        if (Transfers.ui != null) return
        transferBox = TransferBox(
            this, layoutInflater, session,
            alive = { !isFinishing && !isDestroyed },
            onBackground = { toast(getString(R.string.transfer_background_hint)) },
            onDetach = { transferBox = null },
            onFinished = {
                toast(it.finished?.exceptionOrNull()?.message ?: getString(R.string.msg_done))
                // 只重判这次搬过的那些项,不整树重扫
                revalidate(pendingNodes)
                pendingNodes = emptyList()
            },
        )
    }

    // ---- 对比收藏 ----

    private fun saveSession() {
        val l = leftRoot?.let(::compareSideOf)
        val r = rightRoot?.let(::compareSideOf)
        if (l == null || r == null) {
            // restic 快照要仓库密码、zip/SAF 的挂载点是会话内临时的,都还原不回来
            toast(getString(R.string.compare_save_unsupported))
            return
        }
        val input = android.widget.EditText(this).apply {
            setText("${l.label} ↔ ${r.label}")
            setSingleLine()
            setPadding(48, 32, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.compare_save)
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().ifBlank { "${l.label} ↔ ${r.label}" }
                CompareStore.add(this, CompareSession(name, l, r, options))
                toast(getString(R.string.compare_saved_ok))
            }
            .show()
    }

    private fun openSaved() {
        val list = CompareStore.all(this)
        if (list.isEmpty()) return toast(getString(R.string.compare_saved_empty))
        val names = list.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.compare_saved)
            .setItems(names) { _, i -> loadSession(list[i]) }
            .setNeutralButton(R.string.compare_saved_delete) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.compare_saved_delete)
                    .setItems(names) { _, i ->
                        CompareStore.remove(this, list[i])
                        toast(getString(R.string.compare_saved_deleted))
                    }
                    .show()
            }
            .show()
    }

    private fun loadSession(s: CompareSession) {
        scanJob?.cancel()
        lifecycleScope.launch {
            b.progress.visibility = View.VISIBLE
            val pair = withContext(Dispatchers.IO) {
                resolveCompareSide(this@CompareActivity, s.left) to
                    resolveCompareSide(this@CompareActivity, s.right)
            }
            val (l, r) = pair
            if (l == null || r == null) {
                b.progress.visibility = View.GONE
                return@launch toast(getString(R.string.compare_open_failed))
            }
            leftRoot = l
            rightRoot = r
            options = s.options
            refreshPaths()
            startScan()
        }
    }

    // ---- 行为 ----

    private fun onRowClick(n: Node, clickedSide: Int) {
        setActiveSide(clickedSide)
        if (n.isDir) {
            n.expanded = !n.expanded
            rebuild()
            return
        }
        val l = n.left
        val r = n.right
        if (l != null && r != null) openPair(n, l, r, clickedSide)
        else (l ?: r)?.let { OpenFiles.openWith(this, it) }
    }

    /** 两侧都有的一对:图片走并排图片对比,其余走双栏文本 diff。 */
    private fun openPair(n: Node, l: XFile, r: XFile, side: Int = activeSide) {
        if (OpenFiles.isImage(l) && OpenFiles.isImage(r)) {
            ImageCompareActivity.start(this, l, r, n.name)
        } else {
            diffedKey = n.key
            diffLauncher.launch(DiffActivity.pairIntent(this, l, r, n.name, side))
        }
    }

    /** 上一次打开双栏 diff 的那一项;它在 diff 页里合并并保存了差异就得重判。 */
    private var diffedKey: String? = null

    private val diffLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        // RESULT_OK = 在 diff 页里把某段差异合并并存回去了,这一对的状态(多半)变了。
        // 只重判它一项,不整树重扫——理由同 [revalidate]。
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        diffedKey?.let { k -> nodeByKey[k]?.let { revalidate(listOf(it)) } }
    }

    private fun showRowMenu(anchor: View, n: Node) {
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
        menu.menu.showIcons()
        val l = n.left
        val r = n.right
        if (l != null && r != null && !n.isDir) {
            val isImg = OpenFiles.isImage(l) && OpenFiles.isImage(r)
            menu.menu.add(getString(if (isImg) R.string.compare_image_diff else R.string.compare_text_diff)).apply {
                icon = menuIcon(R.drawable.ic_compare)
            }.setOnMenuItemClickListener {
                openPair(n, l, r, activeSide); true
            }
        }
        menu.menu.add(getString(R.string.compare_copy_to, sideName(1 - activeSide))).apply {
            icon = menuIcon(R.drawable.ic_copy)
        }.setOnMenuItemClickListener {
            selected.clear()
            selected.add(n.key)
            notifyBoth()
            actionCopy()
            true
        }
        menu.menu.add(getString(R.string.compare_delete_side, sideName(activeSide))).apply {
            icon = menuIcon(R.drawable.ic_delete)
        }.setOnMenuItemClickListener {
            selected.clear()
            selected.add(n.key)
            notifyBoth()
            actionDelete()
            true
        }
        menu.menu.add(getString(R.string.compare_exclude_this)).apply {
            icon = menuIcon(R.drawable.ic_exclude)
        }.setOnMenuItemClickListener {
            excludeNode(n)
            true
        }
        menu.show()
    }

    // ---- 布局模式(横屏并排、竖屏滑动切换,与主界面双面板一致) ----

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyLayoutMode() {
        // ★ 竖屏只看得见一侧,活动侧必须就是那一侧——否则会出现"显示的是左边,
        // 左边路径栏却是非活动的暗色"(横屏时把活动侧点到右边,再转竖屏就会这样),
        // 而且操作方向也会跟眼前看到的那一侧对不上
        if (!isLandscape && side != activeSide) setActiveSide(side)
        if (isLandscape) {
            b.sideLeft.visibility = View.VISIBLE
            b.sideRight.visibility = View.VISIBLE
            b.divider.visibility = View.VISIBLE
            b.divider2.visibility = View.VISIBLE
        } else {
            b.sideLeft.visibility = if (side == 0) View.VISIBLE else View.GONE
            b.sideRight.visibility = if (side == 1) View.VISIBLE else View.GONE
            // 只留贴着列表那一侧的分隔线,另一条会孤零零地挂在屏幕边上
            b.divider.visibility = if (side == 0) View.VISIBLE else View.GONE
            b.divider2.visibility = if (side == 1) View.VISIBLE else View.GONE
        }
        refreshSubtitle()
    }

    /** 活动侧 = 操作源。竖屏切到哪侧,哪侧就是活动侧(看不见的那侧不该是操作目标)。 */
    private fun setActiveSide(s: Int) {
        // ★ 竖屏只有一侧看得见,活动侧没有独立存在的余地——外面传什么都以显示侧为准。
        // 行点击会把点到的那一侧传进来,而横滑刚切走时那正好是**旧的**那一侧
        activeSide = if (isLandscape) s else side
        // ★ 浅色主题下 path_bar_active 与 path_bar 是同一个色值,单靠背景根本分不出来
        // ——主界面 PaneFragment.setActive 也是靠 alpha 拉开的,这里照做
        b.pathBarLeft.setBackgroundResource(if (s == 0) R.color.path_bar_active else R.color.path_bar)
        b.pathBarRight.setBackgroundResource(if (s == 1) R.color.path_bar_active else R.color.path_bar)
        b.pathBarLeft.alpha = if (s == 0) 1f else 0.5f
        b.pathBarRight.alpha = if (s == 1) 1f else 0.5f
        itemSwap?.icon = tinted(
            if (s == 0) R.drawable.ic_pane_to_right else R.drawable.ic_pane_to_left,
            R.color.white,
        )
        refreshDirectionTitles()
        refreshSubtitle()
    }

    private fun refreshPaths() {
        leftRoot?.let {
            b.tvPathLeft.text = Format.pathLabel(it)
            b.ivPathLeft.setImageResource(FileIcons.sourceIconRes(it.scheme))
        }
        rightRoot?.let {
            b.tvPathRight.text = Format.pathLabel(it)
            b.ivPathRight.setImageResource(FileIcons.sourceIconRes(it.scheme))
        }
        setActiveSide(activeSide)
    }

    private fun refreshSubtitle() {
        val base = getString(R.string.compare_stat, stats.entries, stats.diffs)
        val selText = if (selected.isEmpty()) "" else " · ${getString(R.string.compare_selected, selected.size)}"
        // 没选中时顶栏那个复制按钮点了也只会弹「未选择任何项」,不如不占位置
        itemCopyQuick?.isVisible = selected.isNotEmpty()
        // 不再追加"左/右":竖屏本来就窄,而当前显示哪一侧,路径栏已经在说了
        b.toolbar.subtitle = base + selText
    }

    /**
     * 竖屏横滑切换显示侧。
     *
     * ★ 判定为横滑后必须**拦截**后续事件(`onInterceptTouchEvent` 返回 true),不能只是
     * 顺手看一眼就放行:放行的话 ACTION_UP 照样传到行上,一次滑动会顺带点开那一行的
     * 对比;更糟的是行点击里会把活动侧设成**滑走之前**那一侧,于是"显示左边、左边却是
     * 非活动的暗色"。所以在 MOVE 阶段就按 touchSlop 认定横滑并接管整串事件。
     */
    private fun installSwipe(rv: RecyclerView) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var swiping = false
        var downX = 0f
        var downY = 0f
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (isLandscape || abs(vx) <= abs(vy) || abs(vx) < 600) return false
                switchSide(if (vx < 0) 1 else 0)
                return true
            }
        })
        rv.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(view: RecyclerView, e: MotionEvent): Boolean {
                gd.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        swiping = false
                        downX = e.x
                        downY = e.y
                    }
                    MotionEvent.ACTION_MOVE -> if (!swiping && !isLandscape) {
                        val dx = abs(e.x - downX)
                        val dy = abs(e.y - downY)
                        // 明确是横向意图才接管:横向超过 slop 且比纵向多出一半以上,
                        // 免得把正常的上下滚动误判成切换
                        if (dx > slop && dx > dy * 1.5f) swiping = true
                    }
                }
                return swiping
            }

            override fun onTouchEvent(view: RecyclerView, e: MotionEvent) {
                gd.onTouchEvent(e) // 接管之后 fling 还得继续喂给它判定
                if (e.actionMasked == MotionEvent.ACTION_UP ||
                    e.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    swiping = false
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
        })
    }

    /** 切换竖屏显示侧,并把滚动位置带过去(两侧逐行对齐,位置直接通用)。 */
    private fun switchSide(to: Int) {
        if (side == to) return
        val from = if (side == 0) b.listLeft else b.listRight
        val dest = if (to == 0) b.listLeft else b.listRight
        val lm = from.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        val off = lm.findViewByPosition(pos)?.top ?: 0
        side = to
        setActiveSide(to)
        applyLayoutMode()
        if (pos >= 0) (dest.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, off)
    }

    /** 同步滚动。三列都要跟着动,`syncing` 挡住回声(被带动的那列滚动时不再反向带动别人)。 */
    private fun linkScroll(src: RecyclerView, vararg dsts: RecyclerView) {
        src.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (syncing || dy == 0) return
                syncing = true
                for (d in dsts) if (d.visibility == View.VISIBLE) d.scrollBy(0, dy)
                syncing = false
            }
        })
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---- 列表 ----

    private inner class VH(val b: ItemCompareRowBinding) : RecyclerView.ViewHolder(b.root)

    private inner class SideAdapter(private val left: Boolean) : RecyclerView.Adapter<VH>() {
        private val mySide = if (left) 0 else 1

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemCompareRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = rows[position]
            val f = n.sideFile(mySide)
            val v = holder.b
            val dp = resources.displayMetrics.density
            v.indent.layoutParams = v.indent.layoutParams.apply { width = (n.depth * 12 * dp).toInt() }

            val isSel = selected.contains(n.key)
            v.root.setBackgroundColor(rowColor(n, f != null, isSel))
            if (f == null) {
                // 该侧没有这一项:留一行空占位,两栏才对得上
                v.indicator.visibility = View.INVISIBLE
                v.icon.visibility = View.INVISIBLE
                v.name.text = ""
                v.meta.text = ""
                v.check.visibility = View.INVISIBLE
                v.root.setOnClickListener(null)
                v.root.setOnLongClickListener(null)
                v.root.isClickable = false
                return
            }
            v.indicator.visibility = if (n.isDir) View.VISIBLE else View.INVISIBLE
            v.indicator.setImageResource(
                if (n.expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
            )
            v.icon.visibility = View.VISIBLE
            if (n.isDir) v.icon.setImageResource(R.drawable.ic_folder) else FileIcons.bind(v.icon, f)
            v.name.text = n.name
            v.name.setTextColor(nameColor(n))
            v.meta.text = if (n.isDir) "" else "${Format.size(f.size)}  ${Format.time(f.lastModified)}"
            v.check.visibility = View.VISIBLE
            v.check.setColorFilter(
                ContextCompat.getColor(this@CompareActivity, if (isSel) R.color.accent else R.color.text_secondary),
            )
            v.check.alpha = if (isSel) 1f else 0.45f
            v.check.setOnClickListener { toggleSelect(n) }
            v.root.setOnClickListener { onRowClick(n, mySide) }
            v.root.setOnLongClickListener { setActiveSide(mySide); showRowMenu(v.root, n); true }
        }
    }

    /** 中间那一列:一行一个状态符号,颜色与两侧的名字同色。 */
    private inner class StateVH(val b: ItemCompareStateBinding) : RecyclerView.ViewHolder(b.root)

    private inner class StateAdapter : RecyclerView.Adapter<StateVH>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            StateVH(ItemCompareStateBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: StateVH, position: Int) {
            val n = rows[position]
            holder.b.state.text = stateSymbol(n.state)
            holder.b.state.setTextColor(nameColor(n))
            // 中间这一列没有自己的配色,整格跟着行走——否则差异行两侧是连着的色带,
            // 中间却断一截白
            holder.b.state.setBackgroundColor(
                rowColor(n, present = true, selected = selected.contains(n.key)),
            )
        }
    }

    private fun stateSymbol(s: PairState) = when (s) {
        PairState.SAME -> "="
        PairState.DIFF -> "≠"
        PairState.LEFT_ONLY -> "◀"
        PairState.RIGHT_ONLY -> "▶"
        PairState.SCANNING -> "…"
    }

    private fun nameColor(n: Node) = when (n.state) {
        PairState.DIFF -> ContextCompat.getColor(this, R.color.cmp_diff)
        PairState.LEFT_ONLY, PairState.RIGHT_ONLY -> ContextCompat.getColor(this, R.color.cmp_only)
        else -> ContextCompat.getColor(this, R.color.text_primary)
    }

    private fun rowColor(n: Node, present: Boolean, selected: Boolean) = when {
        // 选中态压过状态色:跟文件列表一样,选了什么必须一眼看得出来
        selected -> ContextCompat.getColor(this, R.color.selected)
        !present -> ContextCompat.getColor(this, R.color.cmp_missing_bg)
        n.state == PairState.DIFF -> ContextCompat.getColor(this, R.color.cmp_diff_bg)
        n.state == PairState.LEFT_ONLY || n.state == PairState.RIGHT_ONLY ->
            ContextCompat.getColor(this, R.color.cmp_only_bg)
        else -> ContextCompat.getColor(this, R.color.surface)
    }

    companion object {
        private const val EXTRA_LEFT_SCHEME = "ls"
        private const val EXTRA_LEFT_PATH = "lp"
        private const val EXTRA_RIGHT_SCHEME = "rs"
        private const val EXTRA_RIGHT_PATH = "rp"
        private const val EXTRA_SESSION_ID = "sid"

        fun start(ctx: Context, left: XFile, right: XFile) {
            ctx.startActivity(
                Intent(ctx, CompareActivity::class.java)
                    .putExtra(EXTRA_LEFT_SCHEME, left.scheme)
                    .putExtra(EXTRA_LEFT_PATH, left.path)
                    .putExtra(EXTRA_RIGHT_SCHEME, right.scheme)
                    .putExtra(EXTRA_RIGHT_PATH, right.path),
            )
        }

        /** 树上"对比收藏"根目录点一条:直接开对比页并按 id 还原左右两侧、重新扫描。 */
        fun startSaved(ctx: Context, sessionId: String) {
            ctx.startActivity(
                Intent(ctx, CompareActivity::class.java).putExtra(EXTRA_SESSION_ID, sessionId),
            )
        }
    }
}
