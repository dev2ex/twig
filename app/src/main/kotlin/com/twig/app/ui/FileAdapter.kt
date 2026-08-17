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
 * 整棵树的适配器(文件/目录/存储/分组/服务器/动作项),X-plore 式紧凑行 + 右侧灰勾多选。
 * 行高随 [density] 三档变化。当前目录以边框高亮。
 *
 * 网格视图([gridMode],独立于缩略图开关):
 * - 0:树式列表;1/2:符合条件的文件渲染为网格格子(仅媒体 / 全部文件),
 *   目录与可展开项(压缩包)仍占整行,树的展开逻辑不变。
 * 缩略图([thumbs] 开启时):树式行的图标换成更大的缩略图;网格格子回填缩略图,
 * 关闭时格子只显示类型图标。
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
) : ListAdapter<PaneViewModel.Node, RecyclerView.ViewHolder>(DIFF) {

    private val rowH = intArrayOf(36, 44, 54)[density]
    private val iconDp = rowIconDp(density)
    private val thumbDp = intArrayOf(40, 48, 56)[density]
    private val nameSp = floatArrayOf(12.5f, 14f, 15.5f)[density]
    private val metaSp = floatArrayOf(10f, 11f, 12f)[density]

    /** 网格格子的图片边长(px),由 PaneFragment 按面板宽度/列数回填。 */
    var cellPx = 0

    /** 该位置是否渲染为网格格子(GridLayoutManager 的 spanSize 判定)。 */
    fun isCellAt(position: Int): Boolean =
        position in currentList.indices && isCell(currentList[position])

    /** 该位置是否是普通整行(既非网格格子、也非属性卡片),供分割线判定。 */
    fun isPlainRowAt(position: Int): Boolean =
        position in currentList.indices &&
            currentList[position] !is PaneViewModel.InfoNode && !isCell(currentList[position])

    private fun isCell(n: PaneViewModel.Node): Boolean =
        gridMode > 0 && n is PaneViewModel.FileNode &&
            n.label == null && !n.file.isDir && !n.expandable &&
            !n.file.scheme.startsWith("git") &&
            (gridMode == 2 || Thumbs.canThumb(n.file))

    /** 最近选中的节点 key(由 PaneFragment 随 State 同步),供高亮框定位。 */
    var currentKey: String? = null

    private val selected = HashSet<String>()
    /** 当前选择所在的目录标识(scheme:parentPath);跨目录选择时清空旧选择。 */
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
     * 勾选按钮的三态循环(仅对"已展开的目录"):
     * ① 选中父目录本身 → ② 改选展开的直接子项(不含父) → ③ 清空所有选择。
     * 未展开目录/文件保持普通勾选切换。
     */
    fun cycleSelection(node: PaneViewModel.FileNode) {
        if (!(node.file.isDir && node.expanded)) return toggleSelection(node)
        val kids = directChildren(node)
        if (kids.isEmpty()) return toggleSelection(node)
        val parentSelected = selected.contains(node.key)
        val kidsAllSelected = kids.all { selected.contains(it.key) }
        when {
            parentSelected -> { // ② 父 → 子项
                selected.clear()
                selectParent = "${kids[0].file.scheme}:${kids[0].file.parentPath}"
                kids.forEach { selected.add(it.key) }
            }
            kidsAllSelected -> { // ③ 子项 → 清空
                selected.clear()
                selectParent = null
            }
            else -> { // ① 无 → 父目录
                selected.clear()
                selectParent = "${node.file.scheme}:${node.file.parentPath}"
                selected.add(node.key)
            }
        }
        refreshSelection()
        onSelectionChanged()
    }

    /** 该节点下方的直接子级(depth+1)文件节点——不限于 [PaneViewModel.FileNode] 自身,
     *  也用来取搜索虚拟目录下方的结果列表([PaneViewModel.SearchNode] 同样按 key/depth 定位)。 */
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

    /** 该节点下方是否有可勾选的直接子项(搜索结果/收藏的"全选"入口用它决定要不要给)。 */
    fun hasChildren(node: PaneViewModel.Node): Boolean = directChildren(node).isNotEmpty()

    /** 虚拟目录(搜索结果 / 收藏)的勾选:全选/取消全选它下方的直接子项——两态,
     *  没有"选中父目录本身"这一态(虚拟行本身不是可复制/删除的对象)。结果可能跨多个
     *  真实目录,不套用"同目录多选"限制——复制/删除等操作本就按任意 XFile 列表处理,
     *  树上/占用图多选早已允许跨目录,见 [PaneFragment] 的 mapSelected。 */
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

    /** 用一批文件整体替换当前勾选(图片查看器里勾的图回到树上同步高亮)。后续在树上继续
     *  勾选按第一张所在目录算"同目录",跨目录时照旧先清空。 */
    fun setSelection(files: List<XFile>) {
        selected.clear()
        files.forEach { selected.add(PaneViewModel.fileKey(it)) }
        selectParent = files.firstOrNull()?.let { "${it.scheme}:${it.parentPath}" }
        if (selected.isEmpty()) selectParent = null
        refreshSelection()
        onSelectionChanged()
    }

    /** 切换选中;只允许同目录多选,换目录则先清空旧选择。 */
    fun toggleSelection(node: PaneViewModel.FileNode) {
        val parent = "${node.file.scheme}:${node.file.parentPath}"
        if (selected.isNotEmpty() && parent != selectParent) {
            selected.clear() // 不同目录 → 取消之前的选择
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
     * 勾选态变化只走局部刷新([PAYLOAD_SELECTION]),不整行重绑 —— 树式缩略图行重绑会先
     * 把图标框复位成方形、图片退回类型图标,再等下一帧([Thumbs.fillAspect] 的 post)按
     * 原图比例把高度撑回来,肉眼就是勾一下整列表闪一下。
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
                else -> Unit // 属性卡片没有勾选态
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun refreshSelection() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    /** 勾的选中态配色:选中用品牌色实心,未选中灰且半透明(格子里的勾底色更深,单独给 alpha)。 */
    private fun tintCheck(v: ImageView, on: Boolean, offAlpha: Float = 0.45f) {
        v.setColorFilter(
            ContextCompat.getColor(v.context, if (on) R.color.accent else R.color.text_secondary),
        )
        v.alpha = if (on) 1f else offAlpha
    }

    /** 网格格子:方形缩略图 + 角落灰勾,文件名可关。缩进省略,归属看上方的目录行。 */
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
            // 占位:普通类型图标居中内缩;缩略图回填后自动切满格 centerCrop
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
     * 属性卡片:每个信息分组一个 tab + 文件末尾的"哈希"tab,✕ 关闭。
     * tab 标题栏是随树缩进的小条,左缘与所属文件行的图标对齐(行首缩进 2+depth*14 +
     * 箭头 16 + 图标 margin 2,扣除卡片外边距 4),看起来像树上的一个节点;
     * 内容盒不缩进、左右填满。
     */
    inner class InfoVH(private val b: ItemInfoCardBinding) : RecyclerView.ViewHolder(b.root) {

        init {
            // 名称/路径行末尾的复制图标是 ClickableSpan,要靠它派发点击
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
            // 目录递归统计还在跑:转圈(常驻 View,刷新时只改可见性,动画不断)。
            // 它叠在内容盒右下角,转的时候给文字让出一点右边距,免得压住最后一行的值
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
                    else -> { // 未计算(网络文件/尚未触发):手动点按钮
                        b.infoBody.visibility = View.GONE
                        b.btnHash.visibility = View.VISIBLE
                        b.btnHash.setOnClickListener { onInfoHash(node) }
                    }
                }
            } else {
                // 目录的递归统计追加在"基本"分组末尾,随扫描进度实时变
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

        /** 标签灰、值正常,一行一条;名称/路径行末尾挂一个复制图标。 */
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
         * 值后面追加「复制」图标:`ImageSpan` 画图标 + `ClickableSpan` 收点击。
         * 图标本身才十几个 dp,点击区间连前面那个空格一起算进去,好点一些。
         */
        private fun appendCopyIcon(sb: SpannableStringBuilder, value: String) {
            val ctx = b.root.context
            val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_copy)?.mutate() ?: return
            val size = (b.infoBody.textSize * 1.15f).toInt()
            icon.setBounds(0, 0, size, size)
            icon.setTint(ContextCompat.getColor(ctx, R.color.primary))
            val start = sb.length
            sb.append("  \u200B") // 末位占位字符被 ImageSpan 整个替换成图标
            sb.setSpan(
                ImageSpan(icon, ImageSpan.ALIGN_BOTTOM),
                sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = copyValue(value)
                    // 图标底下不要链接下划线,颜色也已由 setTint 定死
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
            // Android 13 起系统自己弹复制确认条,再 toast 就重了
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
            // 复位可复用状态
            b.icon.tag = null // 取消未回填的异步图标
            b.icon.scaleType = ImageView.ScaleType.FIT_CENTER // 缩略图行回填时改 centerCrop
            b.icon.setPadding(0, 0, 0, 0)
            b.icon.clearColorFilter() // 对比收藏项行会临时染色,复用前先清掉不然串到别的行
            iconVMargin(b.icon, 0)
            b.name.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            b.name.alpha = 1f
            b.appVersion.visibility = View.GONE // 只有应用条目会打开(见 bindApp)
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
                is PaneViewModel.InfoNode -> Unit // 走 InfoVH,不会到这里
            }
        }

        /**
         * 只刷勾选态,不碰图标/文字/监听器。整行选中底色([View.isActivated])只有真正
         * 可选的文件行有;收藏/搜索这类虚拟行的勾表示"下方子项是否全选",行本身不高亮。
         */
        fun bindSelection(node: PaneViewModel.Node) {
            when (node) {
                is PaneViewModel.FileNode -> {
                    if (node.label != null) return // 顶级存储节点不参与多选
                    val isSel = selected.contains(node.key)
                    b.root.isActivated = isSel
                    tintCheck(b.check, isSel)
                }
                is PaneViewModel.FavoriteNode, is PaneViewModel.SearchNode -> {
                    val kids = directChildren(node)
                    tintCheck(b.check, kids.isNotEmpty() && kids.all { selected.contains(it.key) })
                }
                else -> Unit // 分组/服务器/restic/操作行没有勾
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
                    "conn" -> when (node.conn?.type) {
                        "smb" -> R.drawable.ic_lan
                        "sftp" -> R.drawable.ic_server
                        "ftp", "webdav", "s3" -> R.drawable.ic_cloud
                        else -> R.drawable.ic_star // 连接已删除,类型未知
                    }
                    else -> R.drawable.ic_folder // local
                },
            )
            setIndicator(node.expanded)
            if (node.connecting) { b.progress.visibility = View.VISIBLE; setIndicator(null) }
            // 勾选:全选/取消全选下方子项两态,与搜索结果行一致(收藏行本身不是可操作对象);
            // 没展开出子项时没什么可选,不显示这个勾
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

        /** 对比收藏行:不可展开,点了直接跳对比页;没有勾选态(不是可复制/删除的对象)。
         *  专属图标留给上面的"对比收藏"根分组([bindGroup]),这里的行图标沿用操作条同款
         *  黑色 [R.drawable.ic_compare],但按行标题同一个 [R.color.text_primary] 着色——
         *  深浅色主题都跟着字色走,不会再固定死黑色在深色主题里糊成一片。 */
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
            // 解锁要 scrypt + 读快照列表,网络仓库可能几十秒;同服务器/收藏行,转圈顶掉箭头
            if (node.connecting) { b.progress.visibility = View.VISIBLE; setIndicator(null) }
            b.check.visibility = View.GONE
            b.root.setOnClickListener { onClick(node) }
            b.root.setOnLongClickListener { false }
        }

        /**
         * 搜索结果虚拟目录:名称带实时匹配数,扫描中显示转圈。视觉上与普通目录一致——箭头
         * 常显"已展开"(▼,结果行始终跟着渲染,不像普通目录有独立折叠态);但点击这一行的
         * 语义不是常规折叠,而是连同结果一起从树上移出(见 [PaneViewModel.toggle]),
         * 折叠/展开语义由 [onClick] 落到 ViewModel 侧处理,这里只负责渲染。
         */
        private fun bindSearch(node: PaneViewModel.SearchNode) {
            val ctx = b.root.context
            b.name.text = ctx.getString(R.string.search_result_title, node.matchedFiles + node.matchedDirs)
            setMeta(node.pattern)
            b.icon.setImageResource(R.drawable.ic_search)
            setIndicator(true)
            if (node.scanning) b.progress.visibility = View.VISIBLE

            // 勾选:全选/取消全选下方结果(不是普通行的三态循环,搜索结果没有"父目录本身"可选)
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
                        Format.size(file.size).takeIf { file.size > 0 || file.lastModified > 0 },
                        Format.time(file.lastModified).takeIf { file.lastModified > 0 },
                    ).joinToString("  ").ifEmpty { null } // 虚拟条目(git 等)无大小/时间则不显示
                },
            )
            when {
                // 带标签的根行:内部存储那种是 ic_storage,但选择器锁定到某台服务器时
                // 这一行代表的就是那台服务器,该用它的来源类型图标(与路径栏/最近位置/
                // 复制目标行同一套,见 FileIcons.sourceIconRes)
                node.label != null -> b.icon.setImageResource(FileIcons.sourceIconRes(file.scheme))
                file.scheme.startsWith("git") && file.path == "/" ->
                    b.icon.setImageResource(R.drawable.ic_git)
                file.isDir -> b.icon.setImageResource(R.drawable.ic_folder)
                else -> {
                    FileIcons.bind(b.icon, file)
                    // 树式缩略图(网格未接管的行):图标位换成更大的缩略图;
                    // 加上下最小间距,免得单行文件名时相邻缩略图贴在一起
                    if (thumbs && Thumbs.canThumb(file)) {
                        val dpi2 = ctx.resources.displayMetrics.density
                        size(b.icon, thumbDp, dpi2)
                        iconVMargin(b.icon, dpi2.toInt())
                        Thumbs.bind(b.icon, file)
                    }
                }
            }
            setIndicator(if (node.expandable) node.expanded else null)
            if (node.loading) {
                b.progress.visibility = View.VISIBLE
                setIndicator(null)
            }

            // 顶级存储节点不参与多选;普通条目显示灰勾
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
                    "ftp", "dav", "s3" -> R.drawable.ic_cloud
                    "sftp" -> R.drawable.ic_server
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
                    // 设了自定义名时副标题给完整地址;再附上连接信息(如 SMB 版本)
                    val addr = if (node.conn.name.isNotEmpty()) node.conn.label() else node.conn.host
                    listOfNotNull(addr, node.info).joinToString("  ")
                },
            )
            b.icon.setImageResource(R.drawable.ic_server)
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
         * 应用条目的三段式行:主标题只留应用名(文件名里的版本号与 .apk/.xapk 后缀是
         * 复制出去时用的,列表里挤在名字后面反而难读),版本号挪到主标题最右端,
         * 包名作副标题排在下面一行。分类目录("已安装"/"系统")没有包名,不受影响。
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

        /** expanded=null 隐藏箭头(不可展开),否则显示 ▶/▼。 */
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
        /** 行图标边长(dp),随行高档位;操作列图标也取它,两边尺寸保持一致。 */
        fun rowIconDp(density: Int): Int = intArrayOf(22, 26, 30)[density]

        private const val TYPE_ROW = 0
        private const val TYPE_INFO = 1
        private const val TYPE_CELL = 2

        /** 局部刷新标记:只有勾选态变了,行的其它部分(尤其图标/缩略图)不必重绑。 */
        private val PAYLOAD_SELECTION = Any()

        private val DIFF = object : DiffUtil.ItemCallback<PaneViewModel.Node>() {
            override fun areItemsTheSame(a: PaneViewModel.Node, b: PaneViewModel.Node) = a.key == b.key
            override fun areContentsTheSame(a: PaneViewModel.Node, b: PaneViewModel.Node) = a == b
        }
    }
}
