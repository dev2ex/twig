package com.twig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.FileInfo
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.favoriteDisplayName
import com.twig.app.favoriteFullPath
import com.twig.app.databinding.ItemFileBinding
import com.twig.app.databinding.ItemInfoCardBinding
import com.twig.app.databinding.ItemThumbCellBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile

/**
 * The whole tree's adapter (files / directories / storages / groups / servers / action rows),
 * X-plore-style compact rows + right-side grey checkboxes for multi-select. Row height scales
 * with the [density] three-level setting; font size scales with [textSize] (the two are
 * independent). The current directory is highlighted with a border.
 *
 * Grid view ([gridMode], independent of the thumbnails toggle):
 * - 0: tree-style list; 1/2: eligible files render as grid cells (media-only / all files),
 *   directories and expandable items (archives) still take a full row, tree expansion logic
 *   is unchanged.
 * Thumbnails (when [thumbs] is on): the tree-row icon is replaced with a larger thumbnail;
 * grid cells are filled with thumbnails; when off, cells show only the type icon.
 */
class FileAdapter(
    private val density: Int,
    private val onClick: (PaneViewModel.Node) -> Unit,
    private val onLongClick: (PaneViewModel.Node) -> Unit,
    private val onSelectionChanged: () -> Unit,
    private val onInfoTab: (PaneViewModel.InfoNode, Int) -> Unit,
    private val onInfoHash: (PaneViewModel.InfoNode) -> Unit,
    private val thumbs: Boolean = false,
    private val gridMode: Int = 0,
    private val gridNames: Boolean = true,
    /** Text size tier (0/1/2), independent of [density]; defaults to follow row height, see `Prefs.textSize`. */
    private val textSize: Int = density,
) : ListAdapter<PaneViewModel.Node, RecyclerView.ViewHolder>(DIFF) {

    private val rowH = intArrayOf(36, 44, 54)[density]
    private val iconDp = rowIconDp(density)
    private val thumbDp = intArrayOf(40, 48, 56)[density]

    private val nameSp = floatArrayOf(12.5f, 14f, 15.5f)[textSize]
    private val metaSp = floatArrayOf(10f, 11f, 12f)[textSize]

    /** Image edge length (px) for grid cells, back-filled by PaneFragment based on pane width / column count. */
    var cellPx = 0

    /** Whether this position renders as a grid cell (spanSize check for GridLayoutManager). */
    fun isCellAt(position: Int): Boolean =
        position in currentList.indices && isCell(currentList[position])

    /** Whether this position is a plain full row (neither grid cell nor info card); used by divider logic. */
    fun isPlainRowAt(position: Int): Boolean =
        position in currentList.indices &&
            currentList[position] !is PaneViewModel.InfoNode && !isCell(currentList[position])

    private fun isCell(n: PaneViewModel.Node): Boolean =
        gridMode > 0 && n is PaneViewModel.FileNode &&
            n.label == null && !n.file.isDir && !n.expandable &&
            !n.file.scheme.startsWith("git") &&
            (gridMode == 2 || Thumbs.canThumb(n.file))

    /** Key of the most recently selected node (synced from State by PaneFragment); used to position the highlight frame. */
    var currentKey: String? = null

    private val selected = HashSet<String>()
    /** Identifier of the directory containing the current selection (scheme:parentPath); cleared when selecting across directories. */
    private var selectParent: String? = null

    fun selectedItems(): List<XFile> =
        currentList.filterIsInstance<PaneViewModel.FileNode>()
            .filter { selected.contains(it.key) }
            .map { it.file }

    fun isSelected(node: PaneViewModel.FileNode): Boolean = selected.contains(node.key)

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        selectParent = null
        refreshSelection()
        onSelectionChanged()
    }

    /**
     * Three-state cycle for the check button (only for "expanded directories"):
     * ① select the parent directory itself → ② switch to selecting the expanded direct children
     * (excluding the parent) → ③ clear all selections.
     * Unexpanded directories / files keep the normal toggle behaviour.
     */
    fun cycleSelection(node: PaneViewModel.FileNode) {
        if (!(node.file.isDir && node.expanded)) return toggleSelection(node)
        val kids = directChildren(node)
        if (kids.isEmpty()) return toggleSelection(node)
        val parentSelected = selected.contains(node.key)
        val kidsAllSelected = kids.all { selected.contains(it.key) }
        when {
            parentSelected -> { // ② parent → children
                selected.clear()
                selectParent = "${kids[0].file.scheme}:${kids[0].file.parentPath}"
                kids.forEach { selected.add(it.key) }
            }
            kidsAllSelected -> { // ③ children → clear
                selected.clear()
                selectParent = null
            }
            else -> { // ① none → parent directory
                selected.clear()
                selectParent = "${node.file.scheme}:${node.file.parentPath}"
                selected.add(node.key)
            }
        }
        refreshSelection()
        onSelectionChanged()
    }

    /** Direct children (depth+1) file nodes below this node — not limited to [PaneViewModel.FileNode] itself;
     *  also used to fetch the result list below a search virtual directory ([PaneViewModel.SearchNode] uses the
     *  same key/depth positioning). */
    private fun directChildren(node: PaneViewModel.Node): List<PaneViewModel.FileNode> {
        val list = currentList
        val i = list.indexOfFirst { it.key == node.key }
        if (i < 0) return emptyList()
        val res = ArrayList<PaneViewModel.FileNode>()
        var j = i + 1
        while (j < list.size && list[j].depth > node.depth) {
            val n = list[j]
            if (n.depth == node.depth + 1 && n is PaneViewModel.FileNode) res.add(n)
            j++
        }
        return res
    }

    /** Whether this node has selectable direct children (the "Select all" entry under search results / favorites uses this to decide whether to show). */
    fun hasChildren(node: PaneViewModel.Node): Boolean = directChildren(node).isNotEmpty()

    /** Selection for virtual directories (search results / favorites): select/deselect all direct children below —
     *  two states only, no "select the parent itself" state (the virtual row itself is not an object that can be
     *  copied or deleted). Results may span multiple real directories, so the "same-directory multi-select" limit
     *  is not applied — copy / delete operations already work on arbitrary XFile lists, and tree / space-map
     *  multi-select has long allowed cross-directory selection; see mapSelected in [PaneFragment]. */
    fun toggleChildrenSelection(node: PaneViewModel.Node) {
        val kids = directChildren(node)
        if (kids.isEmpty()) return
        val allSelected = kids.all { selected.contains(it.key) }
        selected.clear()
        selectParent = null
        if (!allSelected) kids.forEach { selected.add(it.key) }
        refreshSelection()
        onSelectionChanged()
    }

    /** Replace the current selection wholesale with a batch of files (selected images in the image viewer come
     *  back to the tree and highlight in sync). Subsequent on-tree selection treats the directory of the first
     *  file as the "same directory" — cross-directory selections still clear first as usual. */
    fun setSelection(files: List<XFile>) {
        selected.clear()
        files.forEach { selected.add(PaneViewModel.fileKey(it)) }
        selectParent = files.firstOrNull()?.let { "${it.scheme}:${it.parentPath}" }
        if (selected.isEmpty()) selectParent = null
        refreshSelection()
        onSelectionChanged()
    }

    /** Toggle selection; only same-directory multi-select allowed, switching directories clears the old selection first. */
    fun toggleSelection(node: PaneViewModel.FileNode) {
        val parent = "${node.file.scheme}:${node.file.parentPath}"
        if (selected.isNotEmpty() && parent != selectParent) {
            selected.clear() // different directory → cancel previous selection
        }
        selectParent = parent
        if (!selected.add(node.key)) selected.remove(node.key)
        if (selected.isEmpty()) selectParent = null
        refreshSelection()
        onSelectionChanged()
    }

    override fun getItemViewType(position: Int): Int {
        val n = getItem(position)
        return when {
            n is PaneViewModel.InfoNode -> TYPE_INFO
            isCell(n) -> TYPE_CELL
            else -> TYPE_ROW
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_INFO -> InfoVH(ItemInfoCardBinding.inflate(inflater, parent, false))
            TYPE_CELL -> CellVH(ItemThumbCellBinding.inflate(inflater, parent, false))
            else -> VH(ItemFileBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is VH -> holder.bind(getItem(position))
            is InfoVH -> holder.bind(getItem(position) as PaneViewModel.InfoNode)
            is CellVH -> holder.bind(getItem(position) as PaneViewModel.FileNode)
        }
    }

    /**
     * Selection-state changes only use partial refresh ([PAYLOAD_SELECTION]), not a full row rebind —
     * rebinding a tree-style thumbnail row first resets the icon frame to square and the image back
     * to the type icon, then on the next frame ([Thumbs.fillAspect]'s post) stretches the height
     * back to the original aspect ratio; to the eye it looks like the entire list flickers with each tick.
     */
    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isNotEmpty() && payloads.all { it === PAYLOAD_SELECTION }) {
            val node = getItem(position)
            when (holder) {
                is VH -> holder.bindSelection(node)
                is CellVH -> (node as? PaneViewModel.FileNode)?.let { holder.bindSelection(it) }
                else -> Unit // info cards have no selection state
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun refreshSelection() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    /** Checkbox selected-state colours: solid brand colour when selected, grey and translucent when not
     * (the checkbox in cells has a darker background, give alpha separately). */
    private fun tintCheck(v: ImageView, on: Boolean, offAlpha: Float = 0.45f) {
        v.setColorFilter(
            ContextCompat.getColor(v.context, if (on) R.color.accent else R.color.text_secondary),
        )
        v.alpha = if (on) 1f else offAlpha
    }

    /** Grid cell: square thumbnail + corner grey checkbox, file name is toggleable. Indentation is omitted; the parent is the directory row above. */
    inner class CellVH(private val b: ItemThumbCellBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(node: PaneViewModel.FileNode) {
            val ctx = b.root.context
            val dpi = ctx.resources.displayMetrics.density
            val edge = if (cellPx > 0) cellPx else (92 * dpi).toInt()
            b.thumbBox.layoutParams = b.thumbBox.layoutParams.apply { height = edge }
            if (gridNames) {
                b.cellName.visibility = View.VISIBLE
                b.cellName.text = node.file.name
                b.cellName.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp + 0.5f)
            } else {
                b.cellName.visibility = View.GONE
            }
            // Placeholder: ordinary type icon centred with inset; once the thumbnail is filled in, it automatically switches to full-cell centerCrop
            b.thumb.tag = null
            b.thumb.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = edge / 5
            b.thumb.setPadding(pad, pad, pad, pad)
            FileIcons.bind(b.thumb, node.file)
            if (thumbs) Thumbs.bind(b.thumb, node.file)

            bindSelection(node)
            b.cellCheck.setOnClickListener { toggleSelection(node) }
            b.root.setOnClickListener { onClick(node) }
            b.root.setOnLongClickListener { onLongClick(node); true }
        }

        fun bindSelection(node: PaneViewModel.FileNode) {
            val isSel = selected.contains(node.key)
            b.root.isActivated = isSel
            tintCheck(b.cellCheck, isSel, offAlpha = 0.55f)
        }
    }

    /**
     * Info card: one tab per information section + a "hash" tab at the file's tail, ✕ to close.
     * The tab title bar is a thin strip that indents along with the tree, with its left edge aligned to
     * the file row's icon (row-start indent 2+depth*14 + chevron 16 + icon margin 2, minus the card's
     * outer margin 4), so it looks like a node in the tree; the content box does not indent and fills
     * left and right.
     */
    inner class InfoVH(private val b: ItemInfoCardBinding) : RecyclerView.ViewHolder(b.root) {

        init {
            // The copy icon at the end of the name / path rows is a ClickableSpan; rely on it to dispatch clicks
            b.infoBody.movementMethod = LinkMovementMethod.getInstance()
        }

        fun bind(node: PaneViewModel.InfoNode) {
            val ctx = b.root.context
            val dpi = ctx.resources.displayMetrics.density
            (b.tabBar.layoutParams as LinearLayout.LayoutParams).marginStart =
                ((16 + (node.depth - 1) * 14) * dpi).toInt().coerceAtLeast(0)
            b.infoBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp + 1.5f)
            b.infoBody.visibility = View.VISIBLE
            b.btnHash.visibility = View.GONE
            // Directory recursive stats still running: spinner (a persistent View; we only change visibility on refresh, animation continues).
            // It overlays the bottom-right of the content box; when spinning, give the text a bit of right padding so it doesn't cover the value on the last line
            b.scanSpin.visibility = if (node.scanning) View.VISIBLE else View.GONE
            b.infoBody.setPaddingRelative(
                b.infoBody.paddingStart, b.infoBody.paddingTop,
                ((if (node.scanning) 32 else 10) * dpi).toInt(), b.infoBody.paddingBottom,
            )

            val d = node.details
            if (d == null || d.sections.isEmpty()) {
                b.tabBar.visibility = View.GONE
                b.infoBody.text = when {
                    d == null -> ctx.getString(R.string.info_loading)
                    else -> d.error ?: ctx.getString(R.string.info_failed)
                }
                return
            }

            val titles = ArrayList(d.sections.map { it.title })
            val hashIdx = if (node.file.isDir) -1 else titles.size
            if (hashIdx >= 0) titles += ctx.getString(R.string.info_tab_hash)
            val cur = node.tab.coerceIn(0, titles.size - 1)
            buildTabs(node, titles, cur)

            if (cur == hashIdx) {
                when {
                    node.hashing -> b.infoBody.text = ctx.getString(R.string.info_hash_computing)
                    node.hashRows != null -> b.infoBody.text = renderRows(node.hashRows)
                    else -> { // not yet computed (network file / not triggered yet): require a manual button tap
                        b.infoBody.visibility = View.GONE
                        b.btnHash.visibility = View.VISIBLE
                        b.btnHash.setOnClickListener { onInfoHash(node) }
                    }
                }
            } else {
                // Directory's recursive stats are appended at the end of the "basic" section, updating in real time as scanning progresses
                val stat = node.dirStat
                val rows = if (cur == 0 && stat != null) {
                    d.sections[0].rows + FileInfo.dirStatRows(ctx, stat)
                } else {
                    d.sections[cur].rows
                }
                b.infoBody.text = renderRows(rows)
            }
        }

        private fun buildTabs(node: PaneViewModel.InfoNode, titles: List<String>, cur: Int) {
            val ctx = b.root.context
            val dpi = ctx.resources.displayMetrics.density
            val active = ContextCompat.getColor(ctx, R.color.primary)
            val idle = ContextCompat.getColor(ctx, R.color.text_secondary)
            b.tabBar.visibility = View.VISIBLE
            b.tabBar.removeAllViews()
            titles.forEachIndexed { i, t ->
                b.tabBar.addView(
                    android.widget.TextView(ctx).apply {
                        text = t
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp + 1f)
                        setTextColor(if (i == cur) active else idle)
                        typeface = if (i == cur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setPadding((9 * dpi).toInt(), (6 * dpi).toInt(), (9 * dpi).toInt(), (6 * dpi).toInt())
                        if (i != cur) setOnClickListener { onInfoTab(node, i) }
                    },
                )
            }
            b.tabBar.addView(
                View(ctx),
                LinearLayout.LayoutParams(0, 1).apply { weight = 1f },
            )
            b.tabBar.addView(
                android.widget.TextView(ctx).apply {
                    text = ctx.getString(R.string.info_close)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp + 1f)
                    setTextColor(idle)
                    setPadding((12 * dpi).toInt(), (6 * dpi).toInt(), (12 * dpi).toInt(), (6 * dpi).toInt())
                    setOnClickListener { onClick(node) }
                },
            )
        }

        /** Labels grey, values normal, one per line; a copy icon hangs at the end of name / path rows. */
        private fun renderRows(rows: List<Pair<String, String>>): CharSequence {
            val ctx = b.root.context
            val label = ContextCompat.getColor(ctx, R.color.text_secondary)
            val copyable = setOf(ctx.getString(R.string.info_name), ctx.getString(R.string.info_path))
            val sb = SpannableStringBuilder()
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append("\n")
                val s = sb.length
                sb.append(k).append("  ")
                sb.setSpan(ForegroundColorSpan(label), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(v)
                if (k in copyable && v.isNotEmpty()) appendCopyIcon(sb, v)
            }
            return sb
        }

        /**
         * Append a "copy" icon after the value: `ImageSpan` draws the icon + `ClickableSpan` catches clicks.
         * The icon itself is only a dozen dp, so include the preceding space in the click range to make it easier to hit.
         */
        private fun appendCopyIcon(sb: SpannableStringBuilder, value: String) {
            val ctx = b.root.context
            val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_copy)?.mutate() ?: return
            val size = (b.infoBody.textSize * 1.15f).toInt()
            icon.setBounds(0, 0, size, size)
            icon.setTint(ContextCompat.getColor(ctx, R.color.primary))
            val start = sb.length
            sb.append("  \u200B") // the trailing placeholder character is wholly replaced by the ImageSpan with the icon
            sb.setSpan(
                ImageSpan(icon, ImageSpan.ALIGN_BOTTOM),
                sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = copyValue(value)
                    // No link underline under the icon; the colour is already pinned by setTint
                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                    }
                },
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        private fun copyValue(value: String) {
            val ctx = b.root.context
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("twig", value))
            // From Android 13 onward the system itself shows a copy-confirmation toast; showing one too would be redundant
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(ctx, R.string.info_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class VH(private val b: ItemFileBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(node: PaneViewModel.Node) {
            val ctx = b.root.context
            val dpi = ctx.resources.displayMetrics.density

            b.root.minimumHeight = (rowH * dpi).toInt()
            size(b.icon, iconDp, dpi)
            b.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp)
            b.meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp)
            b.root.setPaddingRelative(
                ((2 + node.depth * 14) * dpi).toInt(), 0, 0, 0,
            )
            // Reset reusable state
            b.icon.tag = null // cancel any async icon that hasn't filled in yet
            b.icon.scaleType = ImageView.ScaleType.FIT_CENTER // thumbnail rows switch to centerCrop when filled in
            b.icon.setPadding(0, 0, 0, 0)
            b.icon.clearColorFilter() // compare-favorite rows are tinted temporarily; clear before reuse or it leaks to other rows
            iconVMargin(b.icon, 0)
            b.name.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            b.name.alpha = 1f
            b.appVersion.visibility = View.GONE // only app entries open it (see bindApp)
            b.sub.visibility = View.GONE
            b.appVersion.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp)
            b.sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp)
            b.root.isActivated = false
            b.progress.visibility = View.GONE

            when (node) {
                is PaneViewModel.FileNode -> bindFile(node)
                is PaneViewModel.GroupNode -> bindGroup(node)
                is PaneViewModel.ServerNode -> bindServer(node)
                is PaneViewModel.ActionNode -> bindAction(node)
                is PaneViewModel.ResticNode -> bindRestic(node)
                is PaneViewModel.FavoriteNode -> bindFavorite(node)
                is PaneViewModel.CompareNode -> bindCompareFavorite(node)
                is PaneViewModel.SearchNode -> bindSearch(node)
                is PaneViewModel.InfoNode -> Unit // routes to InfoVH, never reaches here
            }
        }

        /**
         * Only refresh selection state, leave icon / text / listeners alone. The row's selected background
         * ([View.isActivated]) is only present on genuinely selectable file rows; for virtual rows like
         * favorites / search the checkbox indicates "all children below are selected", the row itself is
         * not highlighted.
         */
        fun bindSelection(node: PaneViewModel.Node) {
            when (node) {
                is PaneViewModel.FileNode -> {
                    if (node.label != null) return // top-level storage nodes don't take part in multi-select
                    val isSel = selected.contains(node.key)
                    b.root.isActivated = isSel
                    tintCheck(b.check, isSel)
                }
                is PaneViewModel.FavoriteNode, is PaneViewModel.SearchNode -> {
                    val kids = directChildren(node)
                    tintCheck(b.check, kids.isNotEmpty() && kids.all { selected.contains(it.key) })
                }
                else -> Unit // groups / servers / restic / action rows have no checkbox
            }
        }

        private fun bindFavorite(node: PaneViewModel.FavoriteNode) {
            val ctx = b.root.context
            val fav = node.fav
            b.name.text = favoriteDisplayName(fav, node.conn)
            setMeta(if (node.connecting) ctx.getString(R.string.ftp_connecting) else favoriteFullPath(fav, node.conn))
            b.icon.setImageResource(
                when (fav.kind) {
                    "restic" -> R.drawable.ic_restic
                    // The type table lives only in FileIcons (the path bar, recents, and copy-target rows all use this same set)
                    "conn" -> node.conn?.type?.let { FileIcons.sourceIconOfType(it) }
                        ?: R.drawable.ic_star // connection was deleted, type unknown
                    else -> R.drawable.ic_folder // local
                },
            )
            setIndicator(node.expanded)
            if (node.connecting) { b.progress.visibility = View.VISIBLE; setIndicator(null) }
            // Selection: select / deselect all children below (two states), same as search-result rows (the favorite row itself is not an actionable object);
            // when no children are expanded there's nothing to select, hide this checkbox
            val kids = directChildren(node)
            if (kids.isEmpty()) {
                b.check.visibility = View.GONE
            } else {
                b.check.visibility = View.VISIBLE
                bindSelection(node)
                b.check.setOnClickListener { toggleChildrenSelection(node) }
            }
            b.root.setOnClickListener { onClick(node) }
            b.root.setOnLongClickListener { onLongClick(node); true }
        }

        /** Compare-favorite row: not expandable, click jumps straight to the compare page; no selection state (not an object that can be copied or deleted).
         *  The dedicated icon is reserved for the "compare favorites" root group above ([bindGroup]); this row's icon reuses the same black [R.drawable.ic_compare] as the action bar,
         *  but is tinted with the row title's [R.color.text_primary] —
         *  both light and dark themes follow the text colour, no longer fixed black that turns into a smear in the dark theme. */
        private fun bindCompareFavorite(node: PaneViewModel.CompareNode) {
            val ctx = b.root.context
            b.name.text = node.session.label
            setMeta(null)
            b.icon.setImageResource(R.drawable.ic_compare)
            b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.text_primary))
            setIndicator(null)
            b.check.visibility = View.GONE
            b.root.setOnClickListener { onClick(node) }
            b.root.setOnLongClickListener { onLongClick(node); true }
        }

        private fun bindRestic(node: PaneViewModel.ResticNode) {
            val ctx = b.root.context
            b.name.text = ctx.getString(R.string.restic_repo)
            setMeta(
                when {
                    node.connecting -> ctx.getString(R.string.restic_unlocking)
                    node.unlocked -> null
                    else -> ctx.getString(R.string.restic_locked)
                },
            )
            b.icon.setImageResource(R.drawable.ic_restic)
            setIndicator(if (node.unlocked) node.expanded else null)
            // Unlocking requires scrypt + reading the snapshot list; network repos can take tens of seconds; same as server / favorite rows, the spinner replaces the chevron
            if (node.connecting) { b.progress.visibility = View.VISIBLE; setIndicator(null) }
            b.check.visibility = View.GONE
            b.root.setOnClickListener { onClick(node) }
            b.root.setOnLongClickListener { false }
        }

        /**
         * Search-results virtual directory: the name shows the live match count, with a spinner while scanning.
         * Visually identical to a normal directory — the chevron always shows "expanded" (▼, the result rows
         * always render with it, unlike normal directories which have an independent collapse state); but
         * clicking this row is not a normal collapse — it removes both the row and its results from the
         * tree (see [PaneViewModel.toggle]); the collapse / expand semantics are handled by [onClick] on
         * the ViewModel side; here we only render.
         */
        private fun bindSearch(node: PaneViewModel.SearchNode) {
            val ctx = b.root.context
            b.name.text = ctx.getString(R.string.search_result_title, node.matchedFiles + node.matchedDirs)
            setMeta(node.pattern)
            b.icon.setImageResource(R.drawable.ic_search)
            setIndicator(true)
            if (node.scanning) b.progress.visibility = View.VISIBLE

            // Selection: select / deselect all results below (not the three-state cycle of normal rows, search results have no "parent directory itself" to pick)
            b.check.visibility = View.VISIBLE
            bindSelection(node)
            b.check.setOnClickListener { toggleChildrenSelection(node) }

            b.root.setOnClickListener { onClick(node) }
            b.root.setOnLongClickListener { onLongClick(node); true }
        }

        private fun bindFile(node: PaneViewModel.FileNode) {
            val ctx = b.root.context
            val file = node.file

            b.name.text = node.label ?: file.name
            if (file.scheme == com.twig.app.AppsFileSystem.SCHEME) bindApp(file)
            setMeta(
                node.capacity ?: when {
                    file.isDir -> Format.time(file.lastModified).takeIf { file.lastModified > 0 }
                    else -> listOfNotNull(
                        // When size is unknown (media servers don't give byte counts for photos) don't show the whole segment, don't write "0 B"
                        Format.sizeOrNull(file)?.takeIf { file.size > 0 || file.lastModified > 0 },
                        Format.time(file.lastModified).takeIf { file.lastModified > 0 },
                    ).joinToString("  ").ifEmpty { null } // virtual entries (git etc.) with no size / time are hidden
                },
            )
            // ★★ Do **not touch `imageTintList` here** (bitten on 2026-08-19):
            // `ImageView.setImageTintList(null)` does not mean "no tint" — it actively applies
            // null as a tint to the drawable, setting `mHasDrawableTint = true`, so
            // `mDrawable.mutate().setTintList(null)` erases the `android:tint` from the drawable XML.
            // The `fillColor` of `ic_folder` / `ic_file_*` is all `@android:color/white`, and the colour
            // comes **solely** from that tint — erase it and the whole screen's icons turn white.
            // For any icon that needs a colour, make your own drawable with `android:tint` baked in
            // (see `ic_md_*`).
            // The size / margin reset for reuse is done in the whole block at the top of [VH.bind]
            // (the thumbnail route stretches the icon cell to thumbDp and even taller for posters);
            // here we only handle what this row itself should look like.
            val dpi = ctx.resources.displayMetrics.density
            when {
                // Root row with a label: an internal storage one is ic_storage, but when the chooser is locked to a particular server
                // this row represents that server and should use its source-type icon (same set as the path bar / recents /
                // copy-target rows, see FileIcons.sourceIconRes)
                node.label != null -> b.icon.setImageResource(FileIcons.sourceIconRes(file.scheme))
                file.scheme.startsWith("git") && file.path == "/" ->
                    b.icon.setImageResource(R.drawable.ic_git)
                file.isDir -> {
                    // Directories on media servers are all virtual (albums / artists / libraries…), give them a distinguishable icon
                    val mediaIcon = FileIcons.mediaDirIcon(file)
                    b.icon.setImageResource(mediaIcon ?: R.drawable.ic_folder)
                    // Third-party app-authorised document tree roots: the whole tree is that app's data; use its icon
                    // (the row name is also swapped for the app name, see PaneViewModel.safRoots). The icon is filled in
                    // asynchronously; the folder-icon assignment above is its placeholder.
                    if (com.twig.app.SafFileSystem.isTreeRoot(file)) {
                        com.twig.app.SafFileSystem.providerApp(ctx, file)?.let {
                            FileIcons.bindApp(b.icon, it.pkg)
                        }
                    }
                    // If there's a cover, swap to it — **but only stretch to thumbnail size once the image arrives** (growPx):
                    // most directories don't have covers, and stretching to thumbnail size at bind time would make every
                    // folder icon on screen look huge ("they look big" was reported before).
                    // Movie / series posters are 2:3 portraits, so once stretched the row also grows accordingly.
                    if (thumbs && Thumbs.canThumb(file)) {
                        // ★ Vertical margins must match the file branch: skip them and adjacent covers touch directly.
                        // Series / collections / playlists / media libraries **are all directories** going through this
                        // branch — movies are files, taking the branch below, hence the symptom "only the movie library has spacing".
                        iconVMargin(b.icon, dpi.toInt())
                        Thumbs.bind(b.icon, file, (thumbDp * dpi).toInt())
                    }
                }
                else -> {
                    FileIcons.bind(b.icon, file)
                    // Tree-style thumbnail (rows not handled by grid): the icon slot becomes a larger thumbnail;
                    // add minimum top/bottom margin so that thumbnails on adjacent rows don't touch when filenames are short
                    if (thumbs && Thumbs.canThumb(file)) {
                        size(b.icon, thumbDp, dpi)
                        iconVMargin(b.icon, dpi.toInt())
                        // ★ Movies are **files** (Jellyfin's Movie entries have IsFolder=false), and their posters
                        // go through this branch instead of the directory branch above — without giving growPx,
                        // `maxH = maxOf(box.height, w)` crops 2:3 posters back to squares.
                        // Video / image thumbnails don't get this; the existing "crop if too tall" behaviour stands.
                        val poster = if (Thumbs.hasCover(file)) (thumbDp * dpi).toInt() else 0
                        Thumbs.bind(b.icon, file, poster)
                    }
                }
            }
            setIndicator(if (node.expandable) node.expanded else null)
            if (node.loading) {
                b.progress.visibility = View.VISIBLE
                setIndicator(null)
            }

            // Top-level storage nodes don't take part in multi-select; ordinary entries show a grey checkbox
            if (node.label == null) {
                b.check.visibility = View.VISIBLE
                bindSelection(node)
                b.check.setOnClickListener { cycleSelection(node) }
            } else {
                b.check.visibility = View.GONE
            }

            b.root.setOnClickListener { onClick(node) }
            b.root.setOnLongClickListener { onLongClick(node); true }
        }

        private fun bindGroup(node: PaneViewModel.GroupNode) {
            b.name.text = node.label
            setMeta(null)
            b.icon.setImageResource(
                when (node.id) {
                    "fav" -> R.drawable.ic_star
                    "cmp" -> R.drawable.ic_compare_fav
                    "lan" -> R.drawable.ic_lan
                    "ftp" -> R.drawable.ic_ftp
                    "dav", "s3" -> R.drawable.ic_cloud
                    // The group id "media" is shared by Jellyfin / Emby (the other two names exist only as defensive aliases), so
                    // here we use the neutral monitor icon — the play button with the official colours is on each
                    // server's own row below; the shapes differ so they won't be confused with Emby's green triangle
                    "media", "jellyfin", "emby" -> R.drawable.ic_media_server
                    "sftp" -> R.drawable.ic_sftp
                    else -> R.drawable.ic_storage
                },
            )
            setIndicator(node.expanded)
            b.check.visibility = View.GONE
            b.root.setOnClickListener { onClick(node) }
            b.root.setOnLongClickListener { false }
        }

        private fun bindServer(node: PaneViewModel.ServerNode) {
            val ctx = b.root.context
            b.name.text = node.conn.displayLabel()
            setMeta(
                if (node.connecting) {
                    ctx.getString(R.string.ftp_connecting)
                } else {
                    // When a custom name is set, the subtitle gives the full address; then append connection info (e.g. SMB version)
                    val addr = if (node.conn.name.isNotEmpty()) node.conn.label() else node.conn.host
                    listOfNotNull(addr, node.info).joinToString("  ")
                },
            )
            // Media servers get a dedicated icon: easy to spot among a row of green server icons, and to tell which one it is.
            // Other types keep the generic server icon — this column's semantics are just "a server".
            b.icon.setImageResource(
                if (node.conn.isMediaServer()) FileIcons.sourceIconOfType(node.conn.type)
                else R.drawable.ic_server,
            )
            setIndicator(node.expanded)
            if (node.connecting) { b.progress.visibility = View.VISIBLE; setIndicator(null) }
            b.check.visibility = View.GONE
            b.root.setOnClickListener { onClick(node) }
            b.root.setOnLongClickListener { onLongClick(node); true }
        }

        private fun bindAction(node: PaneViewModel.ActionNode) {
            b.name.text = node.label
            b.name.alpha = 0.6f
            setMeta(null)
            b.icon.setImageResource(R.drawable.ic_add)
            setIndicator(null)
            b.check.visibility = View.GONE
            b.root.setOnClickListener { onClick(node) }
            b.root.setOnLongClickListener { false }
        }

        /**
         * Three-segment row for app entries: the main title keeps only the app name (the version number and
         * .apk/.xapk suffix in the file name are for when you copy it out; squeezing them after the name in the
         * list is harder to read); the version number is moved to the right end of the main title, and the package
         * name goes to the subtitle on the next line. Category directories ("installed" / "system") have no package
         * name, so they are unaffected.
         */
        private fun bindApp(file: XFile) {
            val fs = runCatching { FsRegistry.of(file) }.getOrNull()
                as? com.twig.app.AppsFileSystem ?: return
            val meta = fs.metaOf(file) ?: return
            b.name.text = meta.label
            if (meta.version.isNotEmpty()) {
                b.appVersion.visibility = View.VISIBLE
                b.appVersion.text = meta.version
            }
            b.sub.visibility = View.VISIBLE
            b.sub.text = meta.pkg
        }

        private fun setMeta(text: String?) {
            if (text.isNullOrBlank()) {
                b.meta.visibility = View.GONE
            } else {
                b.meta.visibility = View.VISIBLE
                b.meta.text = text
            }
        }

        /** expanded=null hides the chevron (not expandable), otherwise shows ▶/▼. */
        private fun setIndicator(expanded: Boolean?) {
            if (expanded == null) {
                b.indicator.visibility = View.INVISIBLE
            } else {
                b.indicator.visibility = View.VISIBLE
                b.indicator.setImageResource(
                    if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
                )
            }
        }

        private fun iconVMargin(v: View, px: Int) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            if (lp.topMargin != px || lp.bottomMargin != px) {
                lp.topMargin = px
                lp.bottomMargin = px
                v.layoutParams = lp
            }
        }

        private fun size(v: View, dp: Int, dpi: Float) {
            val lp = v.layoutParams
            lp.width = (dp * dpi).toInt()
            lp.height = (dp * dpi).toInt()
            v.layoutParams = lp
        }
    }

    companion object {
        /** Row icon edge length (dp), scales with the row-height tier; the action-column icons also use this so both sides match. */
        fun rowIconDp(density: Int): Int = intArrayOf(22, 26, 30)[density]

        private const val TYPE_ROW = 0
        private const val TYPE_INFO = 1
        private const val TYPE_CELL = 2

        /** Partial-refresh marker: only the selection state has changed, the rest of the row (especially icon / thumbnail) doesn't need rebinding. */
        private val PAYLOAD_SELECTION = Any()

        private val DIFF = object : DiffUtil.ItemCallback<PaneViewModel.Node>() {
            override fun areItemsTheSame(a: PaneViewModel.Node, b: PaneViewModel.Node) = a.key == b.key
            override fun areContentsTheSame(a: PaneViewModel.Node, b: PaneViewModel.Node) = a == b
        }
    }
}
