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
 * Directory comparison (Beyond Compare-style): rows aligned line-for-line across the two
 * sides, a status-symbol column in the middle, side-by-side with synchronized scrolling
 * in landscape; in portrait, one side at a time, swipe left/right to switch (the status
 * column is always visible, so the status of every row is always visible).
 *
 * The "active side" follows the dual-pane convention: the side highlighted in the path
 * bar is **the source of operations**; copy and sync both go "active side → other side",
 * and delete deletes from the active side. Actions all live in the top-bar menu and the
 * row long-press menu — no separate action column.
 *
 * Scanning is fully recursive (see [scanCompare]), so stop and exclude rules are must-haves —
 * scanning a large tree from a network source takes minutes. The result tree is built on
 * the main thread: the scan coroutine only emits immutable incremental events, matching
 * the "only gather data, building the table is the collector's job" rule from [scanDirStat].
 */
class CompareActivity : AppCompatActivity() {

    /** A node of the result tree. [key] is the path relative to the roots on both sides; the root has the empty string. */
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
    // Amount excluded mid-scan. The scan thread keeps reporting by its own counters;
    // without subtracting these, the stats would jump back.
    private var goneEntriesTotal = 0
    private var goneDiffsTotal = 0

    /** In portrait, the side currently displayed; in landscape both sides are visible and this just follows [activeSide]. */
    private var side = 0
    /** Active side = the source side for operations (0=left, 1=right), the side highlighted in the path bar. */
    private var activeSide = 0
    private var syncing = false

    private var transferBox: TransferBox? = null
    /** Nodes touched by the current transfer; when the transfer finishes, only these are revalidated, not the whole tree. */
    private var pendingNodes: List<Node> = emptyList()

    /** Rule → entries it blocked off (containing directory key, entry name). Used to precisely restore when a rule is deleted, without rescanning the whole tree. */
    private val excludedByRule = HashMap<String, LinkedHashSet<Pair<String, String>>>()

    private var itemRefresh: MenuItem? = null
    private var itemSwap: MenuItem? = null
    private var itemDiffOnly: MenuItem? = null
    private var itemExpand: MenuItem? = null
    private var itemCopy: MenuItem? = null
    /** The copy shortcut on the top bar — only revealed when there is a selection; the one in the overflow menu is always there. */
    private var itemCopyQuick: MenuItem? = null
    private var itemDelete: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCompareBinding.inflate(layoutInflater)
        setContentView(b.root)
        NavBarTint.surface(this) // both list sides are filled with surface

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
        // All three columns are linked: scrolling any one drags the other two with it,
        // otherwise the status symbols would get misaligned with their rows.
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
        val (l, r) = sidesFrom(intent) ?: return finish()
        leftRoot = l
        rightRoot = r
        options = loadCompareOptions(this)
        refreshPaths()
        startScan()
    }

    override fun onResume() {
        super.onResume()
        showTransferBox() // when returning from the notification, reattach the progress dialog
        // Realign once when returning from the compare page / viewer: in portrait the
        // "side currently shown" and the "active side" must be in sync.
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

    // ---- Scan ----

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
        itemExpand?.setTitle(R.string.compare_expand_all) // tree was rebuilt; the two-state title returns to its initial value
        notifyBoth()
        scanJob = lifecycleScope.launch { runScan(root) }
    }

    /** Compose the absolute key inside the whole tree from a [prefix] subtree's relative key. */
    private fun fullKey(prefix: String, sub: String) = when {
        prefix.isEmpty() -> sub
        sub.isEmpty() -> prefix
        else -> "$prefix/$sub"
    }

    /**
     * Scan the subtree at [node], attaching results under it; when [node] is [root] the
     * whole tree is scanned. Extracted so that after copy/delete only the actually affected
     * directory is rescanned, not the entire tree from scratch.
     */
    private suspend fun runScan(node: Node) {
        // Allow one side to be empty: needed when restoring a directory that exists on only
        // one side; the missing side naturally results in everything being classified as
        // "only on the other side".
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
                        // Auto-expand paths with differences: those are exactly what the
                        // user wants to see — no need to manually expand layer by layer.
                        if (ev.state == PairState.DIFF) n.expanded = true
                    }
                    // Subtree rescan: this counter only covers the subtree; using it as a
                    // global stat would cause numbers to roll back. Only the whole-tree scan
                    // uses it for live progress; on completion recountStats is always authoritative.
                    is CompareEvent.Progress -> if (node === root) {
                        stats = ev.copy(
                            entries = (ev.entries - goneEntriesTotal).coerceAtLeast(0),
                            diffs = (ev.diffs - goneDiffsTotal).coerceAtLeast(0),
                        )
                    }
                    // Record "who blocked what", so that deleting the rule only restores the
                    // entries it actually blocked.
                    is CompareEvent.Excluded -> excludedByRule
                        .getOrPut(ev.rule) { LinkedHashSet() }
                        .add(fullKey(node.key, ev.dirKey) to ev.name)
                    CompareEvent.Truncated -> toast(getString(R.string.compare_truncated))
                }
                val now = System.currentTimeMillis()
                // Rebuilding on every event would flood the main thread on a large tree;
                // once every 150 ms is enough to feel like "scanning with live updates".
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

    /** Recount stats over the current tree, used to correct after partial rescan/detach so the numbers do not drift. */
    private fun recountStats() {
        var e = 0
        var d = 0
        for (n in nodeByKey.values) {
            if (n.key.isEmpty()) continue // the root is not an entry
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
        // ★ Do not use setIcon(resource id): that swaps in an untinted drawable whose
        // native color is black, which becomes a black button on the green toolbar.
        // Tint first, then assign.
        itemRefresh?.icon = tinted(if (on) R.drawable.ic_stop else R.drawable.ic_refresh, R.color.white)
        itemRefresh?.setTitle(if (on) R.string.compare_stop else R.string.compare_refresh)
    }

    // ---- Result tree → visible rows ----

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
            // "Diff only" hides rows classified as SAME; directories still scanning
            // (SCANNING) cannot be hidden, otherwise it looks like they are missing.
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

    // ---- Top-bar menu ----

    private fun buildMenu() {
        val m = b.toolbar.menu
        m.showIcons()
        // ★ Toolbar's ALWAYS icons are laid out left-to-right in add() order — copy must
        // sit to the left of refresh, so it has to be added first. Placing it leftmost is
        // because it is not permanent (hidden when nothing is selected), and when it does
        // appear it should not push the permanent refresh/switch buttons rightward, which
        // would feel visually unstable.
        itemCopyQuick = m.add("").apply {
            icon = tinted(R.drawable.ic_copy, R.color.white)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false // only lit when something is selected; see refreshSubtitle
            setOnMenuItemClickListener { actionCopy(); true }
        }
        itemRefresh = m.add(getString(R.string.compare_refresh)).apply {
            icon = tinted(R.drawable.ic_refresh, R.color.white)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { if (scanning) scanJob?.cancel() else startScan(); true }
        }
        itemSwap = m.add(getString(R.string.compare_switch_side)).apply {
            icon = tinted(R.drawable.ic_pane_to_right, R.color.white) // the actual facing direction is set by setActiveSide
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                // In portrait, it switches "which side is being viewed" (and takes the active side with it); in landscape both sides are visible, so it switches the active side.
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
                // Two-state menu item: if anything is still collapsed, expand all first;
                // once everything is expanded, the next tap collapses all.
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
        // ★ The directions are hard-coded to "left" and "right", and do not follow the active side: sync is irreversible, "sync to the other side" requires the user to first recognize which side is highlighted to know where to push, and the path bar's highlight in portrait follows the swipe switch.
        m.add(getString(R.string.compare_sync_to, sideName(0))).apply {
            icon = menuIcon(R.drawable.ic_sync)
            setOnMenuItemClickListener { actionSync(to = 0); true }
        }
        m.add(getString(R.string.compare_sync_to, sideName(1))).apply {
            icon = menuIcon(R.drawable.ic_sync)
            setOnMenuItemClickListener { actionSync(to = 1); true }
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
            icon = menuIcon(R.drawable.ic_save)
            setOnMenuItemClickListener { saveSession(); true }
        }
        refreshDirectionTitles()
    }

    /** Icons in the overflow menu: the menu background follows the theme, so tint to the body text color rather than the icons' default black. */
    private fun menuIcon(res: Int) = tinted(res, R.color.text_primary)

    private fun tinted(res: Int, colorRes: Int) =
        ContextCompat.getDrawable(this, res)?.mutate()?.apply {
            setTint(ContextCompat.getColor(this@CompareActivity, colorRes))
        }

    /** The titles for copy / sync / delete have the direction baked in, so the user does not have to guess which way they are moving things when they tap. */
    private fun refreshDirectionTitles() {
        val other = sideName(1 - activeSide)
        itemCopy?.title = getString(R.string.compare_copy_to, other)
        itemCopyQuick?.title = getString(R.string.compare_copy_to, other)
        itemDelete?.title = getString(R.string.compare_delete_side, sideName(activeSide))
        itemDiffOnly?.isChecked = diffOnly
    }

    // ---- Selection ----

    private fun selectedNodes(): List<Node> = rows.filter { selected.contains(it.key) }

    /**
     * Remove items whose ancestor is also selected. After "select all", a parent directory
     * and its children are both in the selection set; treating them all equally would copy
     * the directory as a whole, then copy each child again (and for delete it is worse —
     * deleting the parent first, then trying to delete the children, would always error).
     * Letting CopyEngine recurse on the whole directory is enough.
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

    /** Select all / clear: scoped to currently visible rows (affected by "diff only"), matching the file list semantics. */
    private fun toggleSelectAll() {
        if (selected.isEmpty()) rows.forEach { selected.add(it.key) } else selected.clear()
        notifyBoth()
        refreshSubtitle()
    }

    // ---- Excluding single items ----

    /**
     * Exclude a single item. ★ **No rescan**: excluding one item does not affect the
     * other results already scanned, and rescan on a large network tree takes minutes —
     * waiting several minutes to skip a single item makes no sense. We just detach this
     * branch from the result tree and walk back up to fix ancestors' aggregate state and
     * the statistics.
     *
     * (The "exclude rules" dialog still does rescan, because rules can be **deleted** —
     * items previously skipped have to be re-scanned to be found again; there really is
     * no other way.)
     */
    private fun excludeNode(n: Node) {
        // Items under the root only have a name, which incidentally also excludes
        // same-named items deeper in; with a path available the entry is anchored to itself
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

    /** Detach a branch from the result tree (including index and selection set); returns the detached (entry count, diff count). */
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

    /** Only clear the index; do not touch the parent's children (used before rescanning a subtree: that whole subtree is about to be replaced by new results). */
    private fun dropIndex(n: Node) {
        nodeByKey.remove(n.key)
        selected.remove(n.key)
        n.children.forEach(::dropIndex)
    }

    /** After detaching a branch, ancestors may go from "has differences" to "all same"; recompute level by level upward. */
    private fun recomputeAncestors(fromKey: String) {
        var k = fromKey
        while (true) {
            val n = nodeByKey[k]
            // Single-side-only directories are classified by "which side only", independent
            // of subtree contents, so they must not be overwritten.
            if (n != null && n.state != PairState.LEFT_ONLY && n.state != PairState.RIGHT_ONLY) {
                n.state = if (n.children.any { it.state != PairState.SAME }) PairState.DIFF else PairState.SAME
            }
            if (k.isEmpty()) break
            k = k.substringBeforeLast('/', "")
        }
    }

    // ---- Copy / sync / delete ----

    /**
     * Whether the selected item exists at the **corresponding position on the other side**
     * decides its destination directory: the destination is always "the other side's root
     * + that item's relative parent path within the tree". This way a deep file lands in
     * a same-named subdirectory on the other side rather than piling up at the root.
     */
    private fun destDirFor(n: Node, from: Int): XFile? {
        val destRoot = (if (from == 0) rightRoot else leftRoot) ?: return null
        return compareDestDir(destRoot, n.key)
    }

    private fun actionCopy() {
        val sel = prunedSelection()
        if (sel.isEmpty()) return toast(getString(R.string.msg_no_selection))
        val items = sel.mapNotNull { n -> n.sideFile(activeSide)?.let { n to it } }
        if (items.isEmpty()) return toast(getString(R.string.compare_none_on_side))
        confirmAndTransfer(items, getString(R.string.compare_copy_confirm, items.size, sideName(1 - activeSide)))
    }

    /**
     * Sync: push every difference from **[to]'s other side** (the source side) to [to] —
     * source-only items plus items that differ on both sides.
     * Source-only directories are taken as a whole, not descended: CopyEngine recurses
     * through the copy, while iterating children individually is slower and more prone to
     * aborting halfway.
     *
     * Items that exist only on the destination side go into [SyncPlan.deletes]; **whether
     * to actually delete them is decided by the "incremental sync" checkbox on the
     * confirmation dialog** (default incremental = keep). The classification itself runs
     * through [syncActionFor], sharing the same logic with the "compare favorites" row.
     */
    private fun actionSync(to: Int) {
        // If scanning is still in progress, the tree is half-built, and any computed plan
        // is guaranteed to miss items — and what it misses is exactly the differences not
        // yet scanned.
        if (scanning) return toast(getString(R.string.compare_sync_wait_scan))
        val from = 1 - to
        val dstRoot = (if (to == 0) leftRoot else rightRoot) ?: return
        val copies = ArrayList<SyncItem>()
        val deletes = ArrayList<SyncItem>()
        fun walk(n: Node) {
            for (c in n.children) {
                val src = c.sideFile(from)
                val dst = c.sideFile(to)
                when (syncActionFor(c.state, c.isDir, from)) {
                    SyncAct.COPY -> if (src != null) {
                        copies += SyncItem(c.key, src, dst != null && targetIsNewer(src, dst, options))
                    }
                    SyncAct.DELETE -> if (dst != null) deletes += SyncItem(c.key, dst)
                    SyncAct.DESCEND -> walk(c)
                    SyncAct.SKIP -> Unit
                }
            }
        }
        walk(root)
        val plan = SyncPlan(copies, deletes)
        if (plan.copies.isEmpty() && plan.deletes.isEmpty()) {
            return toast(getString(R.string.compare_nothing_to_sync))
        }
        confirmSync(
            this, lifecycleScope, dstRoot, to, plan, options,
            onOptions = { options = it; saveCompareOptions(this, it) },
            onStarted = { keys ->
                // Only revalidate the items actually touched this time, do not rescan the
                // entire tree (same rationale as revalidate). In mirror mode there may be
                // only deletes with no transfer; in that case there is no transfer session
                // to wait on, so revalidate directly.
                val nodes = keys.mapNotNull { nodeByKey[it] }
                if (Transfers.active != null) {
                    pendingNodes = nodes
                    showTransferBox()
                } else {
                    revalidate(nodes)
                }
            },
        )
    }

    /**
     * After confirmation, start a transfer session. The destination directory may not
     * exist yet (when checking a deep item inside a directory that only exists on the
     * source side), so build it level by level before launching — [Transfers.Work.Sync]
     * assumes the destination directory already exists.
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

    /** Delete the selected items on the active side. Which side is deleted is shown in the confirmation dialog — this is irreversible, the user cannot be left to guess. */
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
                    revalidate(sel) // revalidate the deleted items in place; do not rescan the whole tree
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
     * Rules changed. **No whole-tree rescan**; handle the change incrementally in two directions:
     * - **Added rules**: detach matching entries from the tree in place
     * - **Removed rules**: only restore entries that were blocked by those rules
     *   (tracked in [excludedByRule]); directories additionally need their subtree rescanned
     *   — that subtree was pruned before and never scanned
     */
    private fun applyExcludeRules(list: List<String>) {
        val before = options.excludes.toSet()
        val after = list.toSet()
        val added = list.filter { it !in before }
        val removed = options.excludes.filter { it !in after }
        options = options.copy(excludes = list)
        saveCompareOptions(this, options)
        if (added.isEmpty() && removed.isEmpty()) return

        // Added rules: detach currently matching items from the tree (collect first, then
        // detach — do not mutate while iterating)
        if (added.isNotEmpty()) {
            val hit = nodeByKey.values.filter { n ->
                n.key.isNotEmpty() && matchesExclude(n.name, n.key, added)
            }
            // Descendants whose ancestor has already been detached will be removed along
            // with it; detaching them again would not find a parent. Process in depth order
            // (shallow first).
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

        // Removed rules: restore the entries they originally blocked
        val toRestore = removed.flatMap { excludedByRule.remove(it).orEmpty() }
            // If some other rule still blocks it, do not restore
            .filter { (parentKey, name) -> excludeRuleFor(name, fullKey(parentKey, name), list) == null }
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            setScanning(true)
            for ((parentKey, name) in toRestore) {
                val parent = nodeByKey[parentKey] ?: continue
                if (parent.children.any { it.name == name }) continue // already in the tree
                val key = fullKey(parentKey, name)
                // Metadata must come from list(); rationale is the same as statSides
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
                // Directory: the subtree was pruned before and never scanned; rescan now.
                // Single-side-only directories take the same path (scanCompare accepts empty
                // on either side, naturally classifying everything as "only on the other side").
                if (node.isDir) runScan(node)
                recomputeAncestors(parentKey)
            }
            setScanning(false)
            recountStats()
            rebuild()
        }
    }

    /** Find an entry by name within a directory, with metadata taken from `list()`. **Hits the network, so call from a background thread**. */
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

    // ---- Partial revalidation after operations ----

    /**
     * Only revalidate the items actually touched by copy/sync/delete. A whole-tree rescan
     * is on the order of minutes over the network, while a single operation only affects
     * the few items it touched (or, for directories, that one subtree).
     *
     * - **File**: stat each side again and reclassify; if neither side has it, detach it
     * - **Directory**: rescan the whole subtree — copying a directory across results in
     *   an entire tree on the other side, which a single stat cannot discover
     */
    private fun revalidate(nodes: List<Node>) {
        val alive = nodes.filter { nodeByKey[it.key] === it }
        if (alive.isEmpty()) return
        val files = alive.filterNot { it.isDir }
        val dirs = alive.filter { it.isDir }
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            setScanning(true) // revalidation also reads disk / hits network; don't let the UI look frozen
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
                                l == null && r == null -> null // both sides are gone
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
                if (nodeByKey[d.key] !== d) continue // may have been removed by the detach above
                // The directory itself may have just been copied across or deleted; re-locate
                // it on each side first
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
            // Same as the file pane: clear the selection after operations complete
            selected.clear()
            setScanning(false)
            recountStats()
            rebuild()
        }
    }

    /**
     * Refetch the latest metadata of these nodes on one side (entries that no longer exist are not present in the result).
     *
     * ★ **Must go through `list()`, not `resolve()`**: each `resolve` implementation only
     * guarantees "can locate", not "fills in metadata" — `SmbFileSystem.resolve` is just
     * `XFile(scheme, path, isDir = true)`, with size/mtime both 0 and isDir hard-coded to
     * true. The rows in the tree originally came from `list()`, and mixing the two would
     * cause "after copying, the file shows as 0 B". Group by parent directory so each
     * directory is only listed once.
     *
     * **Hits the network, so call from a background thread**.
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

    /** Pairing key shared with [pairEntries]: case per options; a directory and a file with the same name are not the same key. */
    private fun nameKey(name: String, isDir: Boolean) =
        (if (options.ignoreCase) name.lowercase() else name) + (if (isDir) "/" else "")

    // ---- Transfer progress ----

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
                // Only revalidate the items actually moved this time; do not rescan the whole tree
                revalidate(pendingNodes)
                pendingNodes = emptyList()
            },
        )
    }

    // ---- Saved comparisons ----

    private fun saveSession() {
        val l = leftRoot?.let(::compareSideOf)
        val r = rightRoot?.let(::compareSideOf)
        if (l == null || r == null) {
            // restic snapshots require the repository password, and archive mount points
            // are session-scoped temporaries — neither can be restored later
            // (SAF can: its grants are persistent, see resolveCompareSide)
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

    // ---- Behavior ----

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

    /**
     * A pair present on both sides: images go to side-by-side image compare, everything else to the
     * two-column text diff — which hands binaries and oversized files on to [HexCompareActivity]
     * itself, since only after reading can it tell text from binary.
     */
    private fun openPair(n: Node, l: XFile, r: XFile, side: Int = activeSide) {
        if (OpenFiles.isImage(l) && OpenFiles.isImage(r)) {
            ImageCompareActivity.start(this, l, r, n.name)
        } else {
            diffedKey = n.key
            diffLauncher.launch(DiffActivity.pairIntent(this, l, r, n.name, side))
        }
    }

    /** The last item for which the two-column diff was opened; if a merge was applied and saved in the diff page, it has to be revalidated. */
    private var diffedKey: String? = null

    private val diffLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        // RESULT_OK = a merge was applied and saved in the diff page; the state of this
        // pair has (probably) changed.
        // Only revalidate this one item, do not rescan the whole tree — same rationale as
        // [revalidate].
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
            // Always offered, whatever the type: the text diff gives up on binaries, and even for a
            // text pair "which bytes actually differ" is sometimes the question (BOM, line endings,
            // trailing NULs) — none of which a line diff shows.
            menu.menu.add(getString(R.string.compare_hex_diff)).apply {
                icon = menuIcon(R.drawable.ic_file)
            }.setOnMenuItemClickListener {
                HexCompareActivity.start(this@CompareActivity, l, r, n.name); true
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

    // ---- Layout mode (side-by-side in landscape, swipe-switch in portrait; same as the main UI's dual-pane) ----

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyLayoutMode() {
        // ★ In portrait only one side is visible; the active side must be that one —
        // otherwise you would get "left is shown, but the left path bar is dimmed as
        // inactive" (tap the active side to right in landscape, then rotate to portrait,
        // and this is what happens); operation directions also would not match the side
        // currently visible.
        if (!isLandscape && side != activeSide) setActiveSide(side)
        if (isLandscape) {
            b.sideLeft.visibility = View.VISIBLE
            b.sideRight.visibility = View.VISIBLE
            b.divider.visibility = View.VISIBLE
            b.divider2.visibility = View.VISIBLE
        } else {
            b.sideLeft.visibility = if (side == 0) View.VISIBLE else View.GONE
            b.sideRight.visibility = if (side == 1) View.VISIBLE else View.GONE
            // Only keep the divider next to the list; the other one would hang at the
            // edge of the screen all alone.
            b.divider.visibility = if (side == 0) View.VISIBLE else View.GONE
            b.divider2.visibility = if (side == 1) View.VISIBLE else View.GONE
        }
        refreshSubtitle()
    }

    /** Active side = operation source. In portrait, the visible side is always the active one (the invisible side should never be the operation target). */
    private fun setActiveSide(s: Int) {
        // ★ In portrait only one side is visible, so the active side has no independent
        // existence — whatever is passed in, the displayed side wins.
        // Row clicks pass the side that was tapped, which on a fresh swipe-switch is
        // exactly the **old** side.
        activeSide = if (isLandscape) s else side
        // ★ In light themes path_bar_active and path_bar have the same color value, so
        // background alone cannot distinguish them — the main UI's PaneFragment.setActive
        // also uses alpha to separate them, and we do the same here.
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
        // When nothing is selected, the copy button on the top bar would only pop up
        // "nothing selected" — better to not occupy the slot
        itemCopyQuick?.isVisible = selected.isNotEmpty()
        // No longer appending "left/right": portrait is already narrow, and the path bar
        // already shows which side is currently displayed.
        b.toolbar.subtitle = base + selText
    }

    /**
     * In portrait, horizontal swipe switches the displayed side.
     *
     * ★ Once recognized as a horizontal swipe, subsequent events must be **intercepted**
     * (return true from `onInterceptTouchEvent`); you cannot just glance at it and let
     * it pass. If you let it pass, ACTION_UP is still delivered to the row, so a single
     // swipe accidentally opens that row's compare; worse, the row click would set the
     // active side to **the side before the swipe**, resulting in "left is shown, but
     // left is dimmed as inactive". So the swipe is recognized at MOVE time using
     // touchSlop, and the entire event sequence is taken over.
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
                        // Only take over when the intent is clearly horizontal: horizontal
                        // motion exceeds slop and is more than 1.5x the vertical motion,
                        // otherwise normal vertical scrolling would be misread as a side switch
                        if (dx > slop && dx > dy * 1.5f) swiping = true
                    }
                }
                return swiping
            }

            override fun onTouchEvent(view: RecyclerView, e: MotionEvent) {
                gd.onTouchEvent(e) // after taking over, fling still has to be fed to it for detection
                if (e.actionMasked == MotionEvent.ACTION_UP ||
                    e.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    swiping = false
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
        })
    }

    /** Switch the displayed side in portrait, carrying the scroll position over (rows are aligned across both sides, so the position is directly reusable). */
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

    /** Synchronized scrolling. All three columns must move together; `syncing` blocks the echo (a column that is being dragged does not in turn drag the others). */
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

    // ---- List ----

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
                // This side does not have this entry: leave an empty placeholder row so the two columns stay aligned
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

    /** The middle column: one status symbol per row, with the same color as the names on the sides. */
    private inner class StateVH(val b: ItemCompareStateBinding) : RecyclerView.ViewHolder(b.root)

    private inner class StateAdapter : RecyclerView.Adapter<StateVH>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            StateVH(ItemCompareStateBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: StateVH, position: Int) {
            val n = rows[position]
            holder.b.state.text = stateSymbol(n.state)
            holder.b.state.setTextColor(nameColor(n))
            // The middle column does not have its own color scheme; the whole cell follows
            // the row — otherwise the two sides of a diff row would be a continuous color band,
            // with a white gap in the middle.
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
        // Selected state overrides status color: like the file list, what is selected must
        // be obvious at a glance
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
        private const val EXTRA_LEFT_NAME = "ln"
        private const val EXTRA_RIGHT_SCHEME = "rs"
        private const val EXTRA_RIGHT_PATH = "rp"
        private const val EXTRA_RIGHT_NAME = "rn"
        private const val EXTRA_SESSION_ID = "sid"

        fun start(ctx: Context, left: XFile, right: XFile) {
            ctx.startActivity(
                Intent(ctx, CompareActivity::class.java)
                    .putExtra(EXTRA_LEFT_SCHEME, left.scheme)
                    .putExtra(EXTRA_LEFT_PATH, left.path)
                    .putExtra(EXTRA_LEFT_NAME, left.displayName)
                    .putExtra(EXTRA_RIGHT_SCHEME, right.scheme)
                    .putExtra(EXTRA_RIGHT_PATH, right.path)
                    .putExtra(EXTRA_RIGHT_NAME, right.displayName),
            )
        }

        /**
         * intent → left and right sides; missing any required parameter returns null.
         *
         * ★ displayName must be passed along: SAF's path is one full document URI, with no
         * name inside it — without displayName the path bar shows `primary%3ADCIM%2FPhotos`
         * (which is exactly what [Format.pathLabel] extracts as `name` for saf), and
         * "Save this comparison" ([compareSideOf]) would store that encoded string as the name.
         *
         * Extracted only for testability: the section in onCreate is wired together with
         * the scan and is unreachable from unit tests.
         */
        internal fun sidesFrom(intent: Intent): Pair<XFile, XFile>? {
            val ls = intent.getStringExtra(EXTRA_LEFT_SCHEME) ?: return null
            val lp = intent.getStringExtra(EXTRA_LEFT_PATH) ?: return null
            val rs = intent.getStringExtra(EXTRA_RIGHT_SCHEME) ?: return null
            val rp = intent.getStringExtra(EXTRA_RIGHT_PATH) ?: return null
            fun name(key: String) = intent.getStringExtra(key)?.ifEmpty { null }
            return XFile(ls, lp, isDir = true, displayName = name(EXTRA_LEFT_NAME)) to
                XFile(rs, rp, isDir = true, displayName = name(EXTRA_RIGHT_NAME))
        }

        /** A row tapped from the "compare favorites" root in the tree: open the compare page directly, restore both sides by id, and rescan. */
        fun startSaved(ctx: Context, sessionId: String) {
            ctx.startActivity(
                Intent(ctx, CompareActivity::class.java).putExtra(EXTRA_SESSION_ID, sessionId),
            )
        }
    }
}
