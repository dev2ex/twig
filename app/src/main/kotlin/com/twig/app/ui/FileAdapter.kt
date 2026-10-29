package com.twig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.Rect
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.AppDetails
import com.twig.app.FileInfo
import com.twig.app.FileSniff
import com.twig.app.OpenFiles
import com.twig.app.PlaybackStore
import com.twig.app.Prefs
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.favoriteDisplayName
import com.twig.app.favoriteFullPath
import com.twig.app.databinding.ItemFileBinding
import com.twig.app.databinding.ItemInfoCardBinding
import com.twig.app.databinding.ItemThumbCellBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.UnixPermissions

/**
 * The whole tree's adapter (files / directories / storages / groups / servers / action rows),
 * X-plore-style compact rows + right-side grey checkboxes for multi-select. Row height scales
 * with the [density] three-level setting; font size scales with [textSize] (the two are
 * independent). The current directory is highlighted with a border.
 *
 * Grid view ([gridMode], independent of the thumbnails toggle):
 * - 0: tree-style list; 1/2: eligible files render as grid cells (media-only / all files),
 *   directories and expandable items (archives) still take a full row, tree expansion logic
 *   is unchanged. Websites use cells only in all-files mode.
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
    var gridMode: Int = 0,
    private val gridNames: Boolean = true,
    /** Text size tier (0/1/2), independent of [density]; defaults to follow row height, see `Prefs.textSize`. */
    private val textSize: Int = density,
    private val onInfo: (PaneViewModel.FileNode) -> Unit = {},
    private val onInfoLongClick: (PaneViewModel.FileNode) -> Unit = {},
) : ListAdapter<PaneViewModel.Node, RecyclerView.ViewHolder>(DIFF) {

    var onItemDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    private val rowH = intArrayOf(36, 44, 54)[density]
    private val iconDp = rowIconDp(density)
    private val thumbDp = intArrayOf(40, 48, 56)[density]

    private val nameSp = floatArrayOf(12.5f, 14f, 15.5f)[textSize]
    private val metaSp = floatArrayOf(10f, 11f, 12f)[textSize]

    /** Image edge length (px) for grid cells, back-filled by PaneFragment based on pane width / column count. */
    var cellPx = 0

    /** One small snapshot per adapter/resume, never JSON parsing or network I/O per row. */
    private var playbackProgress: Map<String, Float>? = null

    private fun playbackSnapshot(ctx: Context): Map<String, Float> =
        if (!thumbs || !Prefs.thumbsPlaybackProgress(ctx) || !Prefs.resumePlayback(ctx)) emptyMap()
        else PlaybackStore.all(ctx).filter { it.posMs > 0 && it.durMs > it.posMs }
            .associate { it.key to (it.posMs.toDouble() / it.durMs).toFloat() }

    private fun videoFile(file: XFile): Boolean = OpenFiles.isVideo(
        FileSniff.cached(file)?.let { FileSniff.typedTwin(file, it) } ?: file,
    )

    private fun playbackFraction(ctx: Context, file: XFile): Float {
        if (!thumbs || !videoFile(file)) return 0f
        val snapshot = playbackProgress ?: playbackSnapshot(ctx).also { playbackProgress = it }
        return snapshot[PlaybackStore.keyOf(file)] ?: 0f
    }

    /** Progress-only payloads preserve loaded images and the thumbnail's measured shape. */
    fun refreshPlaybackProgress(ctx: Context) {
        val previous = playbackProgress.orEmpty()
        val next = playbackSnapshot(ctx)
        playbackProgress = next
        currentList.forEachIndexed { index, node ->
            val file = (node as? PaneViewModel.FileNode)?.file ?: return@forEachIndexed
            if (videoFile(file)) {
                val key = PlaybackStore.keyOf(file)
                if (previous[key] != next[key]) notifyItemChanged(index, PAYLOAD_PLAYBACK_PROGRESS)
            }
        }
    }

    /** Whether this position renders as a grid cell (spanSize check for GridLayoutManager). */
    fun isCellAt(position: Int): Boolean =
        position in currentList.indices && isCell(currentList[position])

    /** Whether this position is a plain full row (neither grid cell nor info card); used by divider logic. */
    fun isPlainRowAt(position: Int): Boolean =
        position in currentList.indices &&
            currentList[position] !is PaneViewModel.InfoNode && !isCell(currentList[position])

    private fun isCell(n: PaneViewModel.Node): Boolean = when (n) {
        is PaneViewModel.WebsiteNode -> gridMode == 2 && !n.reordering
        is PaneViewModel.FileNode -> (if (n.gridMode >= 0) n.gridMode else gridMode).let { mode ->
            mode > 0 && n.label == null && !n.file.isDir && !n.expandable &&
                !n.file.scheme.startsWith("git") && (mode == 2 || Thumbs.canThumb(n.file))
        }
        else -> false
    }

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

    private val appExpanded = HashMap<String, MutableSet<AppDetails.Kind>>()

    /** Drop selected keys that no longer appear in the tree — collapsing a directory removes its (and its
     *  expanded sub-directories') rows from the list without going through [toggleSelection], so without this
     *  the checkbox state would survive a collapse and reappear checked when the directory is re-expanded. */
    override fun onCurrentListChanged(
        previousList: MutableList<PaneViewModel.Node>,
        currentList: MutableList<PaneViewModel.Node>,
    ) {
        super.onCurrentListChanged(previousList, currentList)
        appExpanded.keys.retainAll(currentList.filterIsInstance<PaneViewModel.InfoNode>().mapTo(HashSet()) { it.key })
        if (selected.isEmpty()) return
        val liveKeys = currentList.mapTo(HashSet()) { it.key }
        if (selected.retainAll(liveKeys)) {
            if (selected.isEmpty()) selectParent = null
            onSelectionChanged()
        }
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

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder.itemView as? InfoCardLayout)?.resetPage()
        holder.itemView.findViewById<View>(R.id.folderCounts)?.setTag(R.id.directory_count, null)
        holder.itemView.findViewById<View>(R.id.indicator)?.setTag(R.id.directory_count, null)
        holder.itemView.findViewById<View>(R.id.meta)?.setTag(R.id.size_probe, null)
        holder.itemView.findViewById<android.widget.TextView>(R.id.sub)?.let(LinkTargets::clear)
        holder.itemView.findViewById<android.widget.TextView>(R.id.link_target)?.let(LinkTargets::clear)
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is VH -> holder.bind(getItem(position))
            is InfoVH -> holder.bind(getItem(position) as PaneViewModel.InfoNode)
            is CellVH -> holder.bind(getItem(position))
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
        if (payloads.isNotEmpty() && payloads.all {
                it === PAYLOAD_APP_ICON || it === PAYLOAD_SELECTION || it === PAYLOAD_PLAYBACK_PROGRESS
            }) {
            val node = getItem(position)
            if (payloads.any { it === PAYLOAD_SELECTION }) {
                when (holder) {
                    is VH -> holder.bindSelection(node)
                    is CellVH -> (node as? PaneViewModel.FileNode)?.let { holder.bindSelection(it) }
                }
            }
            if (node is PaneViewModel.FileNode) {
                if (payloads.any { it === PAYLOAD_APP_ICON }) when (holder) {
                    is VH -> holder.bindDefaultAppIcon(node)
                    is CellVH -> holder.bindDefaultAppIcon(node)
                }
                if (payloads.any { it === PAYLOAD_PLAYBACK_PROGRESS }) when (holder) {
                    is VH -> holder.bindPlaybackProgress(node)
                    is CellVH -> holder.bindPlaybackProgress(node)
                }
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    /**
     * For [FileIcons.bind]: once a bind-time sniff has typed [file] (see FileSniff), rebind its
     * row if that type gets a thumbnail — the thumbnail frame is laid out in bind, so setting
     * the icon alone would leave it at icon size.
     */
    private fun onTyped(holder: RecyclerView.ViewHolder, file: XFile): (() -> Unit)? =
        if (!thumbs) null else {
            {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && Thumbs.canThumb(file)) notifyItemChanged(pos)
            }
        }

    private fun refreshSelection() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    /** Returning from a resolver can change default openers; leave row geometry and thumbnails alone. */
    fun refreshDefaultAppIcons() {
        currentList.forEachIndexed { index, node ->
            val file = (node as? PaneViewModel.FileNode)?.file ?: return@forEachIndexed
            if (!file.isDir && file.extension.isNotEmpty() && FileIcons.baseIconRes(file) == R.drawable.ic_file &&
                !(thumbs && Thumbs.canThumb(file))) notifyItemChanged(index, PAYLOAD_APP_ICON)
        }
    }

    /** Checkbox selected-state colours: solid brand colour when selected, grey and translucent when not
     * (the checkbox in cells has a darker background, give alpha separately). */
    private fun tintCheck(v: ImageView, on: Boolean, offAlpha: Float = IDLE_CHECK_ALPHA) {
        v.setColorFilter(
            ContextCompat.getColor(v.context, if (on) R.color.accent else R.color.text_secondary),
        )
        v.alpha = if (on) 1f else offAlpha
    }

    /** A gesture starting outside the button must not click the row if it ends inside its touch bounds. */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Cancels a row click; the button owns normal/accessibility clicks.
    private fun guardButtonClicks(row: ViewGroup, vararg buttons: View) {
        val bounds = Rect()
        row.setOnTouchListener { view, event ->
            if (event.actionMasked != MotionEvent.ACTION_UP) return@setOnTouchListener false
            val inside = buttons.any { button ->
                if (button.visibility != View.VISIBLE) false else {
                    button.getDrawingRect(bounds)
                    row.offsetDescendantRectToMyCoords(button, bounds)
                    bounds.contains(event.x.toInt(), event.y.toInt())
                }
            }
            if (inside) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try { view.onTouchEvent(cancel) } finally { cancel.recycle() }
                true
            } else false
        }
    }

    /** Grid cell: square thumbnail + corner grey checkbox, file name is toggleable. Indentation is omitted; the parent is the directory row above. */
    inner class CellVH(private val b: ItemThumbCellBinding) : RecyclerView.ViewHolder(b.root) {
        fun bindDefaultAppIcon(node: PaneViewModel.FileNode) { FileIcons.bind(b.thumb, node.file) }
        fun bindPlaybackProgress(node: PaneViewModel.FileNode) {
            b.thumb.playbackFraction = playbackFraction(b.root.context, node.file)
        }
        fun bind(node: PaneViewModel.Node) {
            val ctx = b.root.context
            val dpi = ctx.resources.displayMetrics.density
            val edge = if (cellPx > 0) cellPx else (92 * dpi).toInt()
            b.thumbBox.layoutParams = b.thumbBox.layoutParams.apply { height = edge }
            if (gridNames) {
                b.cellName.visibility = View.VISIBLE
                b.cellName.text = when (node) {
                    is PaneViewModel.WebsiteNode -> node.site.name
                    is PaneViewModel.FileNode -> node.file.name
                    else -> return
                }
                b.cellName.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp + 0.5f)
            } else {
                b.cellName.visibility = View.GONE
            }
            // Placeholder: ordinary type icon centred with inset; once the thumbnail is filled in, it automatically switches to full-cell centerCrop
            LinkTargets.clear(b.linkTarget)
            b.linkTarget.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp)
            b.thumb.resetThumbnail()
            b.thumb.symbolicLink = (node as? PaneViewModel.FileNode)?.file?.isSymbolicLink == true
            b.thumb.tag = null
            b.thumb.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = edge / 5
            b.thumb.setPadding(pad, pad, pad, pad)
            if (node is PaneViewModel.WebsiteNode) {
                WebsiteIcons.bind(b.thumb, node.site)
                b.root.isActivated = false
                b.cellCheck.visibility = View.GONE
                b.infoToggle.visibility = View.GONE
                b.cellCheck.setOnClickListener(null)
                b.infoToggle.setOnClickListener(null)
                b.infoToggle.setOnLongClickListener(null)
                b.root.setOnTouchListener(null)
                b.root.setOnClickListener { onClick(node) }
                b.root.setOnLongClickListener { onLongClick(node); true }
                return
            }
            node as PaneViewModel.FileNode
            if (gridNames) LinkTargets.bind(b.linkTarget, node.file)
            FileIcons.bind(b.thumb, node.file, onTyped(this, node.file))
            if (thumbs) Thumbs.bind(b.thumb, node.file)
            bindPlaybackProgress(node)
            b.cellCheck.visibility = View.VISIBLE
            b.infoToggle.visibility = View.VISIBLE

            bindSelection(node)
            b.cellCheck.setOnClickListener { toggleSelection(node) }
            b.infoToggle.setOnClickListener { onInfo(node) }
            b.infoToggle.setOnLongClickListener { onInfoLongClick(node); true }
            guardButtonClicks(b.root, b.infoToggle, b.cellCheck)
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
     * Info card: one tab per information section + Hash for readable files, Unix last, ✕ to close.
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
            val tabs = FileInfo.tabs(b.root.context, node.file, node.details, node.selection)
            val count = tabs.size
            val tab = node.tab.coerceIn(0, (count - 1).coerceAtLeast(0))
            b.root.swipeEnabled = count > 1
            b.root.onSwipe = { step ->
                val target = tab + step
                if (target in 0 until count) onInfoTab(node, target)
            }
            val page = tabs.getOrNull(tab)?.title
            b.root.showPage(node.key, page, tab, b.infoContent) { bindContent(node) }
        }

        private fun bindContent(node: PaneViewModel.InfoNode) {
            val ctx = b.root.context
            val dpi = ctx.resources.displayMetrics.density
            (b.tabBar.layoutParams as LinearLayout.LayoutParams).marginStart =
                ((16 + (node.depth - 1) * 14) * dpi).toInt().coerceAtLeast(0)
            b.infoBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp + 1.5f)
            b.infoBody.visibility = View.VISIBLE
            b.btnHash.visibility = View.GONE
            b.appGroups.visibility = View.GONE
            b.appGroups.removeAllViews()
            // Persistent spinner for directory stats or the currently selected section's metadata.
            // It overlays the bottom-right of the content box; when spinning, give the text a bit of right padding so it doesn't cover the value on the last line
            val pages = FileInfo.tabs(ctx, node.file, node.details, node.selection)
            val pageSection = pages.getOrNull(node.tab)?.section
            val spinning = node.scanning || pageSection?.let { node.details?.sections?.getOrNull(it)?.loading } == true
            b.scanSpin.visibility = if (spinning) View.VISIBLE else View.GONE
            b.infoBody.setPaddingRelative(
                b.infoBody.paddingStart, b.infoBody.paddingTop,
                ((if (spinning) 32 else 10) * dpi).toInt(), b.infoBody.paddingBottom,
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

            val cur = node.tab.coerceIn(0, pages.size - 1)
            buildTabs(node, pages.map { it.title }, cur)
            val sectionIndex = pages[cur].section

            if (sectionIndex == null) {
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
                val rows = if (sectionIndex == 0 && stat != null) {
                    d.sections[0].rows + FileInfo.dirStatRows(ctx, stat)
                } else {
                    d.sections[sectionIndex].rows
                }
                val section = d.sections[sectionIndex]
                b.infoBody.text = when {
                    section.loading -> ctx.getString(R.string.info_loading)
                    rows.isEmpty() && section.appGroups.isEmpty() -> ctx.getString(R.string.info_failed)
                    else -> renderRows(rows)
                }
                if (section.appGroups.isNotEmpty()) renderAppGroups(node, section.appGroups)
            }
        }

        private fun renderAppGroups(node: PaneViewModel.InfoNode, groups: List<AppDetails.Group>) {
            val ctx = b.root.context
            val dpi = ctx.resources.displayMetrics.density
            val expanded = appExpanded.getOrPut(node.key) { HashSet() }
            val actionColor = ContextCompat.getColor(ctx, R.color.primary)
            val secondaryColor = ContextCompat.getColor(ctx, R.color.text_secondary)
            val selectable = TypedValue().also {
                ctx.theme.resolveAttribute(androidx.appcompat.R.attr.selectableItemBackground, it, true)
            }.resourceId
            fun textView(text: CharSequence, actionable: Boolean = false) = android.widget.TextView(ctx).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, b.infoBody.textSize)
                setTextColor(b.infoBody.textColors)
                breakStrategy = b.infoBody.breakStrategy
                hyphenationFrequency = b.infoBody.hyphenationFrequency
                if (actionable) setTextColor(actionColor)
                setPaddingRelative((30 * dpi).toInt(), (3 * dpi).toInt(), (10 * dpi).toInt(), (3 * dpi).toInt())
                if (actionable && selectable != 0) setBackgroundResource(selectable)
            }
            val visibleGroups = groups.filter {
                it.kind == AppDetails.Kind.SIGNATURE || it.entries.isNotEmpty()
            }
            b.appGroups.visibility = if (visibleGroups.isEmpty()) View.GONE else View.VISIBLE
            for (group in visibleGroups) {
                val suffix = if (group.kind == AppDetails.Kind.SIGNATURE)
                    group.entries.map { it.title }.filter { it.isNotBlank() }.distinct().joinToString(", ")
                else group.entries.size.toString()
                val title = ctx.getString(group.kind.title) + if (suffix.isEmpty()) "" else " ($suffix)"
                val header = textView(title, true).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = (32 * dpi).toInt()
                    setPaddingRelative((10 * dpi).toInt(), (4 * dpi).toInt(), (10 * dpi).toInt(), (4 * dpi).toInt())
                    compoundDrawablePadding = (4 * dpi).toInt()
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                fun update() {
                    val open = group.kind in expanded
                    val arrow = ContextCompat.getDrawable(ctx,
                        if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)!!.mutate()
                    arrow.setBounds(0, 0, (16 * dpi).toInt(), (16 * dpi).toInt())
                    arrow.setTint(actionColor)
                    header.setCompoundDrawablesRelative(arrow, null, null, null)
                    content.visibility = if (open) View.VISIBLE else View.GONE
                    if (!open || content.childCount > 0) return
                    if (group.entries.isEmpty() && group.notice == null)
                        content.addView(textView(ctx.getString(R.string.info_app_none)))
                    group.notice?.let { content.addView(textView(it)) }
                    for (entry in group.entries) {
                        val signature = group.kind == AppDetails.Kind.SIGNATURE
                        val text = SpannableStringBuilder(if (signature) renderRows(entry.rows) else entry.title)
                        if (entry.exported && group.kind != AppDetails.Kind.ACTIVITIES) {
                            val start = text.length
                            text.append(" · ").append(ctx.getString(R.string.info_app_exported))
                            text.setSpan(ForegroundColorSpan(secondaryColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        val detailStart = text.length
                        entry.subtitle?.let { text.append("\n").append(it) }
                        if (group.kind == AppDetails.Kind.PROVIDERS && entry.rows.isNotEmpty()) text.append("\n").append(renderRows(entry.rows))
                        if (text.length > detailStart)
                            text.setSpan(ForegroundColorSpan(secondaryColor), detailStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        val row = textView(text, entry.actionable).apply {
                            if (!entry.enabled) setTextColor(secondaryColor)
                        }
                        if (entry.actionable) {
                            row.setOnClickListener {
                                runCatching { AppDetails.open(ctx, entry) }.onFailure {
                                    val message = if (group.kind == AppDetails.Kind.SHORTCUTS)
                                        R.string.info_app_shortcut_failed else R.string.info_activity_failed
                                    Toast.makeText(ctx, ctx.getString(message), Toast.LENGTH_SHORT).show()
                                }
                            }
                            if (entry.action is AppDetails.Action.Activity) row.setOnLongClickListener { anchor ->
                                PopupMenu(ctx, anchor).apply {
                                    menu.add(R.string.action_pin_shortcut).setOnMenuItemClickListener {
                                        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
                                            Toast.makeText(ctx, R.string.pin_shortcut_unsupported, Toast.LENGTH_SHORT).show()
                                        } else {
                                            runCatching {
                                                check(ShortcutManagerCompat.requestPinShortcut(ctx, AppDetails.activityShortcut(ctx, entry), null))
                                            }.onFailure {
                                                Toast.makeText(ctx, R.string.pin_shortcut_failed, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        true
                                    }
                                    show()
                                }
                                true
                            }
                        }
                        content.addView(row, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                }
                header.setOnClickListener {
                    if (!expanded.add(group.kind)) expanded.remove(group.kind)
                    update()
                }
                b.appGroups.addView(header, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                b.appGroups.addView(content)
                update()
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

        /** Field names are gray and values use the normal text color. */
        private fun renderRows(rows: List<Pair<String, String>>): CharSequence {
            val ctx = b.root.context
            val label = ContextCompat.getColor(ctx, R.color.text_secondary)
            val linkTarget = ctx.getString(R.string.info_link_target)
            val copyable = setOf(ctx.getString(R.string.info_name), ctx.getString(R.string.info_path), linkTarget)
            val sb = SpannableStringBuilder()
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append("\n")
                val s = sb.length
                // Keep the value attached to its key; long unbroken values must split on this line.
                sb.append(k).append("\u00A0\u00A0")
                sb.setSpan(ForegroundColorSpan(label), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // CJK permits a break between any two characters; keep the field name atomic too.
                if (k.isNotEmpty()) sb.setSpan(object : android.text.style.ReplacementSpan() {
                    override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int,
                        fm: android.graphics.Paint.FontMetricsInt?): Int = kotlin.math.ceil(paint.measureText(k)).toInt()
                    override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int,
                        x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
                        // ReplacementSpan bypasses ForegroundColorSpan; apply the label color here.
                        val previousColor = paint.color
                        paint.color = label
                        try {
                            canvas.drawText(k, x, y.toFloat(), paint)
                        } finally {
                            paint.color = previousColor
                        }
                    }
                }, s, s + k.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val indent = kotlin.math.ceil(android.text.Layout.getDesiredWidth(sb, s, sb.length, b.infoBody.paint).toDouble()).toInt()
                sb.append(v)
                if (k in copyable && v.isNotEmpty() &&
                    (k != linkTarget || v != ctx.getString(R.string.info_unavailable))) appendCopyIcon(sb, v)
                // Explicit value newlines need indentation on their first line too.
                var start = s
                while (start < sb.length) {
                    val newline = sb.indexOf("\n", start)
                    val end = if (newline < 0) sb.length else newline + 1
                    sb.setSpan(LeadingMarginSpan.Standard(if (start == s) 0 else indent, indent),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    start = end
                }
            }
            return sb
        }

        /**
         * Append a "copy" icon after the value: `ImageSpan` draws the icon + `ClickableSpan` catches clicks.
         * It shares a span and touch target with the final grapheme so it cannot wrap onto a line by itself.
         */
        private fun appendCopyIcon(sb: SpannableStringBuilder, value: String) {
            val ctx = b.root.context
            val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_copy)?.mutate() ?: return
            val size = (b.infoBody.textSize * 1.15f).toInt()
            icon.setBounds(0, 0, size, size)
            icon.setTint(ContextCompat.getColor(ctx, R.color.primary))
            val valueEnd = sb.length
            val tailStart = if (value.endsWith("\n")) valueEnd else {
                val boundary = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(value) }
                valueEnd - (value.length - boundary.preceding(value.length))
            }
            val tail = value.takeLast(valueEnd - tailStart)
            sb.append("  \uFFFC")
            // Keep the final grapheme and icon in one replacement span, including during forced word breaks.
            sb.setSpan(object : ImageSpan(icon, ImageSpan.ALIGN_BOTTOM) {
                private fun textWidth(paint: android.graphics.Paint) = paint.measureText(tail) + paint.measureText("  ")
                override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int,
                    fm: android.graphics.Paint.FontMetricsInt?): Int =
                    kotlin.math.ceil(textWidth(paint) + super.getSize(paint, text, start, end, fm)).toInt()
                override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int,
                    x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
                    canvas.drawText(tail, x, y.toFloat(), paint)
                    super.draw(canvas, text, start, end, x + textWidth(paint), top, y, bottom, paint)
                }
            }, tailStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = copyValue(value)
                    // No link underline under the icon; the colour is already pinned by setTint
                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                    }
                },
                tailStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
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
        fun bindDefaultAppIcon(node: PaneViewModel.FileNode) { FileIcons.bind(b.icon, node.file) }
        fun bindPlaybackProgress(node: PaneViewModel.FileNode) {
            b.icon.playbackFraction = playbackFraction(b.root.context, node.file)
        }

        fun bind(node: PaneViewModel.Node) {
            val ctx = b.root.context
            val dpi = ctx.resources.displayMetrics.density

            b.root.minimumHeight = (rowH * dpi).toInt()
            size(b.icon, iconDp, dpi)
            b.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp)
            b.meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp)
            for (count in listOf(b.directoryCount, b.fileCount)) {
                count.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp)
                // Equal slots stay fixed across rows, including when one count is hidden.
                count.layoutParams = count.layoutParams.apply { width = (count.textSize * 3.5f).toInt() }
            }
            b.folderCounts.alpha = IDLE_CHECK_ALPHA
            b.folderCounts.visibility = View.GONE
            b.permissions.visibility = View.GONE
            b.permissions.text = ""
            b.permissions.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp)
            b.folderCounts.contentDescription = null
            b.folderCounts.setTag(R.id.directory_count, null)
            b.indicator.setTag(R.id.directory_count, null)
            b.meta.setTag(R.id.size_probe, null)
            b.rowContent.setPaddingRelative(
                ((2 + node.depth * 14) * dpi).toInt(), 0, 0, 0,
            )
            // Reset reusable state
            b.icon.resetThumbnail()
            b.icon.symbolicLink = (node as? PaneViewModel.FileNode)?.file?.isSymbolicLink == true
            b.icon.tag = null // cancel any async icon that hasn't filled in yet
            b.icon.scaleType = ImageView.ScaleType.FIT_CENTER // thumbnail rows switch to centerCrop when filled in
            b.icon.setPadding(0, 0, 0, 0)
            b.icon.clearColorFilter() // compare-favorite rows are tinted temporarily; clear before reuse or it leaks to other rows
            iconVMargin(b.icon, 0)
            b.name.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            b.name.alpha = 1f
            b.appVersion.visibility = View.GONE // only app entries open it (see bindApp)
            LinkTargets.clear(b.sub)
            b.appVersion.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp)
            b.sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSp)
            b.root.isActivated = false
            b.progress.visibility = View.GONE
            // Hiding a live RippleDrawable ends its animation, even when shown again in this bind.
            b.infoToggle.visibility = if (node is PaneViewModel.FileNode) View.VISIBLE else View.INVISIBLE
            b.infoToggle.setOnClickListener(null)
            b.infoToggle.setOnLongClickListener(null)
            b.root.setOnTouchListener(null)
            b.indicator.setOnTouchListener(null)
            b.indicator.contentDescription = null
            val sortingItem = node.reordering
            val indicatorPadding = if (sortingItem) (4 * dpi).toInt() else 0
            b.indicator.setPadding(indicatorPadding, indicatorPadding, indicatorPadding, indicatorPadding)
            val indicatorWidth = ((if (sortingItem) 32 else 16) * dpi).toInt()
            val indicatorHeight = if (sortingItem) ViewGroup.LayoutParams.MATCH_PARENT else (16 * dpi).toInt()
            val indicatorLayout = b.indicator.layoutParams
            if (indicatorLayout.width != indicatorWidth || indicatorLayout.height != indicatorHeight) {
                indicatorLayout.width = indicatorWidth
                indicatorLayout.height = indicatorHeight
                b.indicator.layoutParams = indicatorLayout
            }

            when (node) {
                is PaneViewModel.FileNode -> bindFile(node)
                is PaneViewModel.GroupNode -> bindGroup(node)
                is PaneViewModel.ServerNode -> bindServer(node)
                is PaneViewModel.ActionNode -> bindAction(node)
                is PaneViewModel.WebsiteNode -> bindWebsite(node)
                is PaneViewModel.ResticNode -> bindRestic(node)
                is PaneViewModel.FavoriteNode -> bindFavorite(node)
                is PaneViewModel.CompareNode -> bindCompareFavorite(node)
                is PaneViewModel.SearchNode -> bindSearch(node)
                is PaneViewModel.InfoNode -> Unit // routes to InfoVH, never reaches here
            }
            guardButtonClicks(b.root, b.infoToggle, b.check)
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
            val localDir = when (fav.kind) {
                "local" -> XFile("file", fav.path, isDir = true)
                "saf" -> XFile(com.twig.app.SafFileSystem.SCHEME, fav.path, isDir = true,
                    displayName = fav.pathName.takeIf { it.isNotEmpty() })
                else -> null
            }
            b.name.text = favoriteDisplayName(fav, node.conn)
            setMeta(if (node.connecting) ctx.getString(R.string.ftp_connecting) else favoriteFullPath(fav, node.conn))
            b.icon.setImageResource(
                when (fav.kind) {
                    "restic" -> R.drawable.ic_restic
                    // The type table lives only in FileIcons (the path bar, recents, and copy-target rows all use this same set)
                    "conn" -> node.conn?.type?.let { FileIcons.sourceIconOfType(it) }
                        ?: R.drawable.ic_star // connection was deleted, type unknown
                    "local" -> if (fav.path == com.twig.app.PrivateSpace.directory(ctx).path)
                        R.drawable.ic_private_space else FileIcons.storageRootIconRes(localDir!!)
                    else -> R.drawable.ic_folder
                },
            )
            localDir?.let { dir ->
                FileIcons.directoryIconRes(dir)?.let { b.icon.setImageResource(it) }
                val appPackage = com.twig.app.AppPackageFolders.packageOf(dir)
                    ?: if (com.twig.app.SafFileSystem.isTreeRoot(dir))
                        com.twig.app.SafFileSystem.providerApp(ctx, dir)?.pkg else null
                if (appPackage != null) FileIcons.bindApp(b.icon, appPackage)
            }
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
            bindItemActions(node)
        }

        private fun bindItemActions(node: PaneViewModel.Node) {
            if (node.reordering) {
                b.indicator.visibility = View.VISIBLE
                b.indicator.setImageResource(R.drawable.ic_drag_handle)
                b.indicator.contentDescription = b.root.context.getString(R.string.root_drag_to_reorder)
                b.indicator.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onItemDrag?.invoke(this)
                    false
                }
                b.root.setOnClickListener(null)
                b.root.setOnLongClickListener(null)
            } else {
                b.root.setOnClickListener { onClick(node) }
                b.root.setOnLongClickListener { onLongClick(node); true }
            }
        }

        /** Compare-favorite row: not expandable, click jumps straight to the compare page; no selection state (not an object that can be copied or deleted).
         *  The dedicated icon is reserved for the "compare favorites" root group above ([bindGroup]);
         *  this row reuses [R.drawable.ic_compare], tinted with [R.color.primary] like other tree icons. */
        private fun bindCompareFavorite(node: PaneViewModel.CompareNode) {
            val ctx = b.root.context
            b.name.text = node.session.label
            setMeta(null)
            b.icon.setImageResource(R.drawable.ic_compare)
            b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
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
            b.infoToggle.setOnClickListener { onInfo(node) }
            b.infoToggle.setOnLongClickListener { onInfoLongClick(node); true }

            b.name.text = node.label ?: file.name
            if (file.scheme == com.twig.app.AppsFileSystem.SCHEME) bindApp(file)
            if (file.isSymbolicLink) LinkTargets.bind(b.sub, file)
            file.unixMode?.takeIf { Prefs.unixPermissions(ctx) }?.let { mode ->
                b.permissions.text = UnixPermissions.symbolic(mode)
                b.permissions.visibility = View.VISIBLE
                // Reserve both count slots for files and pending/empty directory counts too.
                if (Prefs.folderCounts(ctx)) b.folderCounts.visibility = View.INVISIBLE
            }
            setMeta(
                node.capacity ?: when {
                    file.isDir -> dateMeta(file)
                    else -> fileMeta(file)
                },
            )
            val storageRoot = file.scheme == "file" && node.depth == 0 && node.label != null
            // A source that cannot report a size in its listing (media server photos) is asked
            // for this row's, in the background; the answer rewrites this row's meta line only.
            if (node.capacity == null) {
                SizeProbes.request(b.meta, file) { bytes -> setMeta(fileMeta(file, bytes)) }
            }
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
                // Labeled roots distinguish internal storage, / and removable media.
                // A picker locked to a server still uses that server's source icon.
                node.label != null -> b.icon.setImageResource(
                    if (file.scheme == "file" && file.path == com.twig.app.PrivateSpace.directory(b.root.context).path)
                        R.drawable.ic_private_space else FileIcons.storageRootIconRes(file),
                )
                file.scheme.startsWith("git") && file.path == "/" ->
                    b.icon.setImageResource(R.drawable.ic_git)
                file.isDir -> {
                    // Directories on media servers are all virtual (albums / artists / libraries…), give them a distinguishable icon
                    val mediaIcon = FileIcons.mediaDirIcon(file)
                    b.icon.setImageResource(mediaIcon ?: FileIcons.directoryIconRes(file) ?: R.drawable.ic_folder)
                    // Known package directories reuse the installed-app icon cache; missing apps keep the folder icon.
                    val appPackage = com.twig.app.AppPackageFolders.packageOf(file)
                    if (appPackage != null) {
                        FileIcons.bindApp(b.icon, appPackage)
                    } else if (com.twig.app.SafFileSystem.isTreeRoot(file)) {
                        // Other providers' granted roots represent the provider app's data.
                        com.twig.app.SafFileSystem.providerApp(ctx, file)?.let {
                            FileIcons.bindApp(b.icon, it.pkg)
                        }
                    }
                    // If there's a cover, swap to it — **but only stretch to thumbnail size once the image arrives** (growPx):
                    // most directories don't have covers, and stretching to thumbnail size at bind time would make every
                    // folder icon on screen look huge ("they look big" was reported before).
                    // Movie / series posters are 2:3 portraits, so once stretched the row also grows accordingly.
                    if (appPackage == null && thumbs && Thumbs.canThumb(file)) {
                        // ★ Vertical margins must match the file branch: skip them and adjacent covers touch directly.
                        // Series / collections / playlists / media libraries **are all directories** going through this
                        // branch — movies are files, taking the branch below, hence the symptom "only the movie library has spacing".
                        iconVMargin(b.icon, dpi.toInt())
                        Thumbs.bind(b.icon, file, (thumbDp * dpi).toInt())
                    }
                }
                else -> {
                    FileIcons.bind(b.icon, file, onTyped(this, file))
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
            bindPlaybackProgress(node)
            setIndicator(if (node.expandable) node.expanded else null)
            if (node.loading) {
                b.progress.visibility = View.VISIBLE
                setIndicator(null)
            }

            val showCounts = !storageRoot && Prefs.folderCounts(ctx) && DirectoryCounts.supports(file)
            val localDirectory = file.scheme == "file" && file.isDir
            if (showCounts || localDirectory) {
                fun applyCount(count: DirectoryCounts.Count) {
                    if (showCounts) setCounts(count)
                    // Keep the row clickable as a destination even when there is nothing to expand.
                    // Synthetic children (search, Git, access hints) must keep their indicator.
                    if (localDirectory && !node.loading && !node.hasVirtualChildren) {
                        setIndicator(if (node.expandable && (count.directories > 0 || count.files > 0))
                            node.expanded else null)
                    }
                }
                (node.directoryCount ?: DirectoryCounts.known(file, Prefs.showHidden(ctx)))?.let(::applyCount)
                if (!node.loading) DirectoryCounts.request(
                    if (showCounts) b.folderCounts else b.indicator,
                    file, Prefs.showHidden(ctx), ::applyCount)
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
                    "websites" -> R.drawable.ic_website
                    "fav" -> R.drawable.ic_star
                    "cmp" -> R.drawable.ic_compare_fav
                    "lan" -> R.drawable.ic_lan
                    "ftp" -> R.drawable.ic_ftp
                    "dav" -> R.drawable.ic_webdav
                    "s3" -> R.drawable.ic_s3
                    "saf" -> R.drawable.ic_document_tree
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
            b.root.setOnLongClickListener {
                if (node.id == "websites" || node.id == "fav") { onLongClick(node); true } else false
            }
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

        private fun bindWebsite(node: PaneViewModel.WebsiteNode) {
            b.name.text = node.site.name
            setMeta(node.site.url)
            WebsiteIcons.bind(b.icon, node.site)
            setIndicator(null)
            b.check.visibility = View.GONE
            bindItemActions(node)
        }

        private fun bindAction(node: PaneViewModel.ActionNode) {
            b.name.text = node.label
            b.name.alpha = 0.6f
            setMeta(null)
            b.icon.setImageResource(if (node.informational) R.drawable.ic_info else R.drawable.ic_add)
            setIndicator(null)
            b.check.visibility = View.GONE
            b.root.setOnClickListener(if (node.informational) null else View.OnClickListener { onClick(node) })
            b.root.isClickable = !node.informational
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

        /**
         * A file row's "size  time" line, with the **size** coloured [R.color.row_meta] and the
         * timestamp muted to match the idle selection check.
         *
         * ★ The colour is a span rather than the TextView's textColor: `meta` is one view shared by
         * every row kind — a directory's timestamp, a favourite's full path, a server's address,
         * restic's lock state, a search pattern — so tinting the view brightens all of those too.
         * Size is the one thing this file manager is built around, and it is the only part that
         * earns the extra contrast.
         *
         * Returns null when the entry has neither (virtual git entries etc.), which hides the line.
         */
        private fun fileMeta(file: XFile, bytes: Long = SizeProbes.known(file)): CharSequence? {
            if (file.scheme == "trash") return dateMeta(file)
            // When size is unknown (media servers don't give byte counts for photos) don't show the whole segment, don't write "0 B"
            val known = if (bytes > 0) file.copy(size = bytes) else file
            val size = Format.sizeOrNull(known)?.takeIf { known.size > 0 || known.lastModified > 0 }
            val time = dateMeta(file)
            if (size == null) return time
            val sb = SpannableStringBuilder(size)
            sb.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(b.root.context, R.color.row_meta)),
                0,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (time != null) sb.append("  ").append(time)
            return sb
        }

        private fun dateMeta(file: XFile): CharSequence? {
            val time = if (file.scheme == "trash")
                (FsRegistry.of(file) as? com.twig.app.TrashFileSystem)?.deletedAt(file) ?: 0L
                else file.lastModified
            if (time <= 0) return null
            val color = b.meta.currentTextColor
            val muted = androidx.core.graphics.ColorUtils.setAlphaComponent(color,
                (android.graphics.Color.alpha(color) * IDLE_CHECK_ALPHA + 0.5f).toInt())
            return SpannableStringBuilder(Format.time(time, b.root.context)).apply {
                setSpan(ForegroundColorSpan(muted), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        private fun setCounts(count: DirectoryCounts.Count) {
            fun bindCount(view: android.widget.TextView, res: Int, number: Int) {
                val drawable = ContextCompat.getDrawable(b.root.context, res)!!.mutate()
                drawable.setTint(b.meta.currentTextColor)
                val edge = (view.textSize * 1.2f).toInt().coerceAtLeast(1)
                drawable.setBounds(0, 0, edge, edge)
                view.setCompoundDrawablesRelative(drawable, null, null, null)
                view.compoundDrawablePadding = (view.textSize * 0.25f).toInt()
                view.text = number.toString()
                view.visibility = if (number == 0) View.INVISIBLE else View.VISIBLE
            }
            bindCount(b.directoryCount, R.drawable.ic_count_folder, count.directories)
            bindCount(b.fileCount, R.drawable.ic_count_file, count.files)
            b.folderCounts.contentDescription = b.root.context.getString(R.string.folder_counts_description,
                count.directories, count.files)
            b.folderCounts.visibility = View.VISIBLE
            b.metaRow.visibility = View.VISIBLE
        }

        private fun setMeta(text: CharSequence?) {
            if (text.isNullOrBlank()) {
                b.meta.visibility = View.GONE
            } else {
                b.meta.visibility = View.VISIBLE
                b.meta.text = text
            }
            b.metaRow.visibility = if (b.meta.visibility == View.VISIBLE || b.folderCounts.visibility == View.VISIBLE ||
                b.permissions.visibility == View.VISIBLE)
                View.VISIBLE else View.GONE
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

        private const val IDLE_CHECK_ALPHA = 0.45f

        /** Partial-refresh marker: only the selection state has changed, the rest of the row (especially icon / thumbnail) doesn't need rebinding. */
        private val PAYLOAD_SELECTION = Any()
        private val PAYLOAD_APP_ICON = Any()
        private val PAYLOAD_PLAYBACK_PROGRESS = Any()

        private val DIFF = object : DiffUtil.ItemCallback<PaneViewModel.Node>() {
            override fun areItemsTheSame(a: PaneViewModel.Node, b: PaneViewModel.Node) = a.key == b.key
            override fun areContentsTheSame(a: PaneViewModel.Node, b: PaneViewModel.Node) = a == b
        }
    }
}
