package com.twig.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.AppsFileSystem
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.HistoryEntry
import com.twig.app.HistoryStore
import com.twig.app.PrivShell
import com.twig.app.Privileged
import com.twig.app.MainActivity
import com.twig.app.OpenFiles
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.app.Format
import com.twig.app.favoriteDisplayName
import com.twig.app.formatLocationPath
import com.twig.app.formatRawLocationPath
import com.twig.app.databinding.DialogCompressBinding
import com.twig.app.databinding.DialogConflictBinding
import com.twig.app.databinding.DialogCopyConfirmBinding
import com.twig.app.databinding.DialogCopyProgressBinding
import com.twig.app.databinding.DialogProgressTitleBinding
import com.twig.app.databinding.FragmentPaneBinding
import com.twig.app.databinding.ItemHistoryBinding
import com.twig.core.CopyEngine
import com.twig.fs.archive.ArchiveWriter
import com.twig.core.FsException
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isWritableDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 单个面板:路径栏 + 整棵树。操作按钮在 MainActivity 的侧边操作列,通过公开的 action* 方法调用。
 * 横向快速滑动 → 通知宿主切换面板(竖屏);触摸 → 通知宿主本面板为活动面板(横屏)。
 */
class PaneFragment : Fragment() {

    interface Host {
        fun siblingOf(self: PaneFragment): PaneFragment?
        fun onAddServer(type: String)
        fun onEditServer(conn: com.twig.app.SavedConnection)
        fun onPaneTouched(self: PaneFragment)
        fun onPaneSwipe(velocityX: Float)
        fun refreshTrees()
        fun isPaneActive(self: PaneFragment): Boolean
        /** 把某面板切为当前显示(矩形树图"在另一面板显示"后聚焦目标面板)。 */
        fun focusPane(pane: PaneFragment)
        /** 本面板的当前目录(= 剪贴板栏的粘贴目标)变了,让宿主刷新栏上那行。
         *  只有 MainActivity 有这条栏,分享目标页那种嵌面板的宿主不用管。 */
        fun onClipTargetChanged() {}

        /** 文件选择器模式:宿主拿走这次点击(返回 true)则不再走打开/查看器。 */
        fun onPickFile(file: XFile): Boolean = false

        /** 传输收尾后通知宿主(分享目标页复制完就该关掉自己);主界面不用管。 */
        fun onTransferFinished(session: Transfers.Session) {}
    }


    private var _b: FragmentPaneBinding? = null
    private val b get() = _b!!

    val viewModel: PaneViewModel by viewModels()
    private lateinit var adapter: FileAdapter
    private var pendingScrollToCurrent = false // 恢复上次位置后一次性滚动到当前目录

    // 已展开本地目录的 inotify 监听(外部应用增删文件时实时同步)
    private val observers = HashMap<String, android.os.FileObserver>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var refreshQueued = false

    /** 面板序号:0=左,1=右。 */
    val paneIndex: Int get() = arguments?.getInt(ARG_INDEX, 0) ?: 0

    private val host get() = activity as? Host

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _b = FragmentPaneBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FileAdapter(
            density = Prefs.density(requireContext()),
            thumbs = Prefs.thumbs(requireContext()),
            gridMode = Prefs.thumbsGrid(requireContext()),
            gridNames = Prefs.thumbsGridNames(requireContext()),
            onClick = { node ->
                when (node) {
                    is PaneViewModel.FileNode ->
                        if (node.expandable) viewModel.toggle(node) else open(node.file)
                    is PaneViewModel.ActionNode ->
                        if (node.id.startsWith("add_")) host?.onAddServer(node.id.removePrefix("add_"))
                    is PaneViewModel.ResticNode -> when {
                        node.connecting -> Unit // 正在解锁,别再弹一次密码框/再叠一次解锁
                        node.unlocked -> viewModel.toggleRestic(node)
                        else -> unlockRestic(node)
                    }
                    is PaneViewModel.FavoriteNode -> onFavoriteClick(node)
                    is PaneViewModel.CompareNode ->
                        CompareActivity.startSaved(requireContext(), node.session.id)
                    else -> viewModel.toggle(node)
                }
            },
            onLongClick = { node -> onLongClick(node) },
            onSelectionChanged = { /* 预留:选中计数 */ },
            onInfoTab = { node, idx -> viewModel.selectInfoTab(node, idx) },
            onInfoHash = { node -> viewModel.computeHash(node) },
        )
        // GridLayoutManager 统一承载:普通行占满整行,缩略图网格格子占 1 列;
        // 列数按面板实际宽度自适应(双面板/横竖屏宽度不同)
        val glm = GridLayoutManager(requireContext(), 4)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isCellAt(position)) 1 else glm.spanCount
        }
        b.list.layoutManager = glm
        b.list.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0) updateSpan(glm, w)
        }
        b.list.adapter = adapter
        b.list.itemAnimator = null // 树即点即变;条目动画会让高亮框跟着过渡位置乱跳
        if (Prefs.rowDivider(requireContext())) b.list.addItemDecoration(RowDivider(requireContext()))
        b.list.addItemDecoration(CurrentDirFrame(requireContext()))

        installGestures()
        // 占用图上的滑动/触摸与树列表同一交互:横滑切面板、触摸上报活动面板
        b.map.onTouchDown = { host?.onPaneTouched(this) }
        b.map.onSwipe = { dx -> host?.onPaneSwipe(dx) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }

        setActive(host?.isPaneActive(this) ?: (paneIndex == 0))
        val lockScheme = arguments?.getString(ARG_LOCK_SCHEME)
        if (lockScheme != null) {
            // 锁定单一来源(选择器):不做位置恢复——那会把别的根展开出来
            if (viewModel.state.value.rows.isEmpty()) {
                viewModel.lockLabel = arguments?.getString(ARG_LOCK_LABEL)
                viewModel.lockRoot = XFile(lockScheme, "/", isDir = true)
                viewModel.refreshTree()
                val start = arguments?.getString(ARG_LOCK_PATH)
                if (!start.isNullOrEmpty() && start != "/") {
                    pendingScrollToCurrent = true
                    viewModel.revealPath(XFile(lockScheme, start, isDir = true))
                } else {
                    viewModel.state.value.rows.firstOrNull { it is PaneViewModel.FileNode }
                        ?.let { viewModel.toggle(it) }
                }
            }
        } else if (viewModel.state.value.rows.isNotEmpty()) {
            // 设置变更触发的 recreate:VM 仍在,按新设置(媒体前置等)重排即可
            viewModel.resortAll()
        } else if (Prefs.rememberLocation(requireContext())) {
            val cur = Prefs.locationCurrent(requireContext(), paneIndex)
            pendingScrollToCurrent = cur != null // 恢复完成后滚动到上次的目录
            viewModel.bootstrap(Prefs.locationExpanded(requireContext(), paneIndex), cur)
        } else {
            viewModel.bootstrap()
        }
    }

    /** 网格列数 ≈ 面板宽 / 96dp;格子边长回填给适配器保持方形。
     * 扣的是 `item_thumb_cell` 自己那圈 2dp padding(左右各一份 = 4dp),缩略图框的
     * 实测宽度正好是它——原来扣 8dp,框就比宽度矮 4dp,方形的应用图标被 CENTER_CROP
     * 削掉上下两条。 */
    private fun updateSpan(glm: GridLayoutManager, width: Int) {
        val cell = (96 * resources.displayMetrics.density).toInt()
        val n = (width / cell).coerceIn(2, 8)
        adapter.cellPx = width / n - (4 * resources.displayMetrics.density).toInt()
        if (glm.spanCount != n) {
            glm.spanCount = n
            adapter.notifyDataSetChanged()
        }
    }

    /**
     * 触摸上报活动面板;横向拖动切换面板——按"抬起时的净水平位移"判定,
     * 慢速/斜向也能触发(阈值较小、只要水平位移大于竖直即可),不影响列表纵向滚动与点击。
     */
    private fun installGestures() {
        val threshold = (48 * resources.displayMetrics.density) // 48dp
        var downX = 0f
        var downY = 0f
        var swiped = false
        b.list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        host?.onPaneTouched(this@PaneFragment)
                        // 恢复期间(网络位置可能要连好几秒)用户一碰列表就交还控制权,
                        // 别在他自己滚动/展开时再把列表拽回上次的位置
                        pendingScrollToCurrent = false
                        downX = e.x; downY = e.y; swiped = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!swiped) {
                            val dx = e.x - downX
                            val dy = e.y - downY
                            if (abs(dx) > threshold && abs(dx) > abs(dy)) {
                                swiped = true
                                host?.onPaneSwipe(dx) // dx>0 右滑→左面板;dx<0 左滑→右面板
                            }
                        }
                    }
                }
                return false
            }
        })
    }

    override fun onResume() {
        super.onResume()
        takeImageViewerResult()
        // 用户可能刚在系统解析器里选了"始终":清关联图标缓存并重绘,让文件图标跟上
        FileIcons.clearAppDefaults()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        // 外部应用可能改动了本地文件:重列已展开的本地目录,并恢复 inotify 监听
        viewModel.refreshLocal()
        syncObservers(viewModel.state.value.rows)
    }

    /**
     * 活动面板路径栏高亮,非活动变暗。深色主题下只降 alpha 会跟面板底色糊成一片,
     * 所以两态换的是背景色([R.color.path_bar_active] / [R.color.path_bar])。
     */
    fun setActive(active: Boolean) {
        val v = _b?.pathBar ?: return
        v.alpha = if (active) 1f else 0.5f
        v.setBackgroundResource(if (active) R.color.path_bar_active else R.color.path_bar)
    }

    private fun render(s: PaneViewModel.State) {
        b.tvPath.text = s.currentDir?.let { pathLabel(it) } ?: ""
        b.ivPathIcon.setImageResource(
            s.currentDir?.let { pathIcon(it) } ?: R.drawable.ic_folder,
        )
        adapter.currentKey = s.currentKey
        adapter.submitList(s.rows) {
            val bb = _b ?: return@submitList
            bb.list.invalidate() // 高亮框随 currentKey 变化重绘
            if (pendingScrollToCurrent) {
                // 恢复是逐个目录异步展开的,行数一路在变:每版都重新锚定到目标行,
                // 直到 restoring 落下来才收手——只锚一次会停在半成品那一版的位置上。
                // "跳转到所在目录"指定的文件行优先;没有(或那一行不在)就定位当前目录
                val idx = listOfNotNull(s.scrollKey, s.currentKey)
                    .firstNotNullOfOrNull { k ->
                        s.rows.indexOfFirst { it.key == k }.takeIf { it >= 0 }
                    } ?: -1
                if (idx >= 0) {
                    (bb.list.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(idx, bb.list.height / 4)
                }
                if (!s.restoring) pendingScrollToCurrent = false
            }
        }
        b.tvEmpty.visibility = if (s.rows.isEmpty()) View.VISIBLE else View.GONE
        host?.onClipTargetChanged() // 粘贴目标 = currentDir,跟着状态刷新栏上那行
        s.error?.let { toast(it) }
        s.passwordFor?.let { askArchivePassword(it) }
        syncObservers(s.rows)
    }

    /** 让 inotify 监听集合与"已展开的本地目录"保持一致。 */
    private fun syncObservers(rows: List<PaneViewModel.Node>) {
        if (!isResumed) return
        val wanted = rows.filterIsInstance<PaneViewModel.FileNode>()
            .filter { it.expanded && it.file.isDir && it.file.scheme == "file" }
            .map { it.file.path }.toSet()
        observers.keys.toList().forEach { p ->
            if (p !in wanted) observers.remove(p)?.stopWatching()
        }
        val mask = android.os.FileObserver.CREATE or android.os.FileObserver.DELETE or
            android.os.FileObserver.MOVED_FROM or android.os.FileObserver.MOVED_TO or
            android.os.FileObserver.CLOSE_WRITE
        for (p in wanted) {
            if (p in observers) continue
            @Suppress("DEPRECATION")
            val o = object : android.os.FileObserver(p, mask) {
                override fun onEvent(event: Int, path: String?) = scheduleLocalRefresh()
            }
            runCatching { o.startWatching(); observers[p] = o }
        }
    }

    /** 事件在 observer 线程触发,去抖 400ms 后回主线程刷新本地目录。 */
    private fun scheduleLocalRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        mainHandler.postDelayed({
            refreshQueued = false
            if (isResumed && _b != null) viewModel.refreshLocal()
        }, 400)
    }

    private fun stopObservers() {
        observers.values.forEach { runCatching { it.stopWatching() } }
        observers.clear()
    }

    /**
     * 路径栏文本:本地是绝对路径,其余是 `类型:/路径`;网络来源在类型后插服务器名
     * (自定义名优先,见 [SavedConnection.shortLabel]),与最近位置里的写法一致。
     */
    private fun pathLabel(f: XFile): String = when {
        f.scheme == "file" -> f.path
        f.scheme == "saf" -> f.name
        else -> {
            // 本面板的反查表优先;查不到再问全局(复制/压缩的目标目录属于**对侧**面板,
            // 本面板没展开过那台服务器,只查 VM 那份就丢了服务器名,见 [Connections.ofScheme])
            val conn = viewModel.connOf(f.scheme) ?: Connections.ofScheme(f.scheme)
            val server = conn?.shortLabel().orEmpty()
            val path = if (f.path.startsWith("/")) f.path else "/${f.path}"
            val head = Format.schemeLabel(f.scheme)
            // 非服务器来源(zip/git/restic…)没有服务器名,别多插一道斜杠
            if (server.isEmpty()) "$head:$path" else "$head:/$server$path"
        }
    }

    /** 供宿主(分享目标页)显示同样格式的路径。 */
    fun displayPath(f: XFile): String = pathLabel(f)

    /** 路径栏的来源类型图标(与最近位置、复制/压缩目标行同一套,见 [FileIcons.sourceIconRes])。 */
    private fun pathIcon(f: XFile): Int = FileIcons.sourceIconRes(f.scheme)

    // ---- 打开文件 ----

    private fun open(file: XFile) {
        if (host?.onPickFile(file) == true) return // 选择器模式:点击即选中,不打开
        if (file.scheme.startsWith("git")) return openGitEntry(file)
        viewModel.noteOpenedIn(file) // 最近位置记的是所在目录,不是文件本身
        when {
            // 应用条目:点开=启动这个应用(点开"安装自己"没有意义,系统只会说已装同版本);
            // 没有启动入口的(多数系统应用)退回应用信息页,总不至于点了没反应。
            // 应用信息/卸载在长按菜单里
            file.scheme == AppsFileSystem.SCHEME -> launchApp(file, fallbackToInfo = true)
            OpenFiles.isText(file) -> TextViewerActivity.start(requireContext(), file)
            OpenFiles.isImage(file) -> {
                val (images, index) = viewModel.imageSiblings(file)
                awaitingImageResult = true
                ImageViewerActivity.start(requireContext(), images, index)
            }
            OpenFiles.isAudio(file) -> openAudio(file)
            OpenFiles.isPlaylist(file) -> openM3u(file)
            OpenFiles.isVideo(file) ->
                MediaPlayerActivity.start(requireContext(), file)
            // 无内置查看器:直接弹系统解析器(带"仅此一次/始终");无应用可开时回退打开方式
            else -> if (!OpenFiles.openWith(requireContext(), file)) chooseOpen(file)
        }
    }

    /**
     * 打开音频:同目录音频入"当前播放"(按面板排序),从点击曲目播放,启动后台服务 + 主界面。
     * 不可持久化来源(zip/restic/saf 内的音频)退回旧的 MediaPlayerActivity 直接播。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openAudio(file: XFile) {
        val self = viewModel.trackFrom(file)
        if (self == null) { MediaPlayerActivity.start(requireContext(), file); return }
        val (siblings, _) = viewModel.audioSiblings(file)
        val tracks = siblings.mapNotNull { viewModel.trackFrom(it) }
        val startIndex = tracks.indexOfFirst { it.id == self.id }.coerceAtLeast(0)
        val dirName = file.parentPath.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        val ctx = requireContext()
        val now = com.twig.app.PlaylistStore.setNow(ctx, dirName, tracks.ifEmpty { listOf(self) })
        MusicEngine.play(ctx, now, startIndex, autoPlay = true)
        startActivity(android.content.Intent(ctx, MusicPlayerActivity::class.java))
    }

    /**
     * 打开 m3u/m3u8 播放列表:后台解析(相对路径按 m3u 所在目录、同来源解析),把其中曲目
     * 载入"当前播放"并从头播。可持久化的来源(本地/已连接服务器)才入列,其它来源的条目跳过。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openM3u(file: XFile) {
        val ctx = requireContext()
        val name = file.name.substringBeforeLast('.').ifEmpty { file.name }
        viewLifecycleOwner.lifecycleScope.launch {
            val tracks = runCatching {
                withContext(Dispatchers.IO) {
                    com.twig.app.M3uPlaylist.parse(file).mapNotNull { viewModel.trackFrom(it) }
                }
            }.getOrDefault(emptyList())
            if (tracks.isEmpty()) { toast(getString(R.string.music_no_playable)); return@launch }
            val now = com.twig.app.PlaylistStore.setNow(ctx, name, tracks)
            MusicEngine.play(ctx, now, 0, autoPlay = true)
            startActivity(android.content.Intent(ctx, MusicPlayerActivity::class.java))
        }
    }

    /** git 虚拟条目:变更/提交内文件 → 双栏 diff;提交信息等其余 → 文本查看。 */
    private fun openGitEntry(file: XFile) {
        val p = file.path
        val diffable = !p.endsWith("/") && !p.endsWith("#info") &&
            (
                (p.startsWith("/changes/") && p.removePrefix("/changes/").contains('/')) ||
                    (p.startsWith("/history/") && p.removePrefix("/history/").contains('/'))
                )
        if (diffable) {
            DiffActivity.start(requireContext(), file.scheme, p, file.name)
        } else {
            TextViewerActivity.start(requireContext(), file)
        }
    }

    private fun chooseOpen(file: XFile) {
        val actions = ArrayList<MenuAct>()
        actions.item(getString(R.string.open_with_app), R.drawable.ic_open_with) { openExternal(file) }
        actions.item(getString(R.string.open_text), R.drawable.ic_file_doc) {
            TextViewerActivity.start(requireContext(), file)
        }
        actions.item(getString(R.string.open_hex), R.drawable.ic_file) {
            HexViewerActivity.start(requireContext(), file)
        }
        showActionMenu(requireContext(), getString(R.string.open_how), actions)
    }

    private fun openExternal(file: XFile) {
        if (!OpenFiles.openWith(requireContext(), file, forceChooser = true)) {
            toast(getString(R.string.open_no_app))
        }
    }

    /**
     * 系统分享:经 [com.twig.app.StreamProvider] 流式授权,SMB/压缩包内/S3 的文件
     * **不落地成临时文件**也能直接发给别的应用。与目录那项「WiFi 共享」是两回事
     * (那是让别人来连这台设备)。
     */
    private fun shareFile(file: XFile) {
        val r = runCatching { OpenFiles.share(requireContext(), file) }
        if (r.isFailure) toast(getString(R.string.open_no_app))
    }

    // ---- 操作(由 MainActivity 侧边操作列调用) ----

    fun actionUp() {
        if (mapMode) { handleBack(); return }
        viewModel.up()
    }

    fun actionRefresh() = viewModel.refresh()

    /**
     * 在树中展开并滚动定位到 [target](由对侧面板"对侧显示"触发)。
     * [focus] 非空时滚到目录里的这个文件行("跳转到所在目录")。
     */
    fun reveal(target: XFile, focus: XFile? = null) {
        if (mapMode) exitTreemap()
        pendingScrollToCurrent = true
        viewModel.revealPath(target, focus)
    }

    /** 外部 App「用 Twig 打开」的压缩包:挂到树顶并展开(见 [PaneViewModel.mountExternal])。 */
    fun mountExternal(archive: XFile) {
        if (mapMode) exitTreemap()
        pendingScrollToCurrent = true
        viewModel.mountExternal(archive)
    }

    /**
     * 最近位置:列出打开过文件/进过 Git 视图的目录(最近的在前,最多
     * [HistoryStore.MAX] 条),点一条跳过去。长按删除单条。
     */
    fun actionHistory() {
        val ctx = requireContext()
        val list = HistoryStore.all(ctx)
        if (list.isEmpty()) { toast(getString(R.string.history_empty)); return }
        // 连接一次性查出来:条目只存标签,显示要的类型/自定义名都从这里取
        val conns = ConnectionStore.all(ctx).associateBy { it.label() }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(position: Int) = list[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val vb = convertView?.let { ItemHistoryBinding.bind(it) }
                    ?: ItemHistoryBinding.inflate(layoutInflater, parent, false)
                val e = list[position]
                vb.name.text = historyLabel(e, conns[e.connLabel])
                vb.icon.setImageResource(historyIcon(e, conns[e.connLabel]))
                return vb.root
            }
        }
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(R.string.history_title)
            .setAdapter(adapter) { _, w -> jumpToHistory(list[w]) }
            .setNeutralButton(R.string.history_clear) { _, _ -> HistoryStore.clear(ctx) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // 内部 ListView 没有长按回调入口,拿到后自己挂:长按删这一条并重开
        dlg.listView?.setOnItemLongClickListener { _, _, pos, _ ->
            HistoryStore.remove(ctx, list[pos])
            dlg.dismiss()
            actionHistory()
            true
        }
    }

    private fun jumpToHistory(e: HistoryEntry) {
        if (mapMode) exitTreemap()
        pendingScrollToCurrent = true
        if (!viewModel.revealHistory(e)) {
            pendingScrollToCurrent = false // 没跳成,别留着待滚状态干扰后续操作
            toast(getString(R.string.history_conn_missing))
        }
    }

    /**
     * 历史条目显示名:网络位置为 `类型:/服务器/路径`(如 `smb:/pi/docs/photos`,与路径栏
     * 同一写法),本地就是绝对路径;git 项再加 Git 前缀。[conn] 为 null 表示该连接已被
     * 删除,退回条目里冻结的标签,至少还认得出来(与收藏行一致)。
     */
    private fun historyLabel(e: HistoryEntry, conn: SavedConnection?): String {
        val head = formatLocationPath(e.connLabel, e.path, conn)
        return if (e.kind == "git") getString(R.string.history_git_prefix, head) else head
    }

    /**
     * 来源类型图标,与路径栏/复制目标行同一套([FileIcons.sourceIconRes]);git 项用 git 图标。
     * 条目里只存了连接标签(`smb://host`)不存 scheme,连接被删时从标签头部退回类型。
     */
    private fun historyIcon(e: HistoryEntry, conn: SavedConnection?): Int = when {
        e.kind == "git" -> R.drawable.ic_git
        e.connLabel.isEmpty() -> FileIcons.sourceIconOfType("file")
        else -> FileIcons.sourceIconOfType(conn?.type ?: e.connLabel.substringBefore("://"))
    }

    // ---- 空间占用矩形树图(就地替换树显示) ----

    private var mapMode = false
    private var mapJob: kotlinx.coroutines.Job? = null
    private var mapScanner: TreemapScanner? = null
    private val mapStack = ArrayList<TreemapEntry>()
    /** 占用图内的多选(菜单"选择"切换);侧栏复制/移动/删除/重命名优先取它。 */
    private val mapSelected = LinkedHashSet<TreemapEntry>()

    /** 操作栏「占用」开关:对绿色框选的目录/压缩包(未框选时当前目录)。 */
    fun actionTreemap() {
        if (mapMode) { exitTreemap(); return }
        val t = viewModel.currentSelection() ?: viewModel.currentDir
        if (t == null || (!t.isDir && !com.twig.fs.archive.Archives.isArchive(t))) {
            toast(getString(R.string.msg_pick_dir_first)); return
        }
        enterTreemap(t)
    }

    private fun enterTreemap(target: XFile) {
        mapMode = true
        b.list.visibility = View.GONE
        b.tvEmpty.visibility = View.GONE
        host?.onClipTargetChanged()
        b.map.visibility = View.VISIBLE
        b.mapStatus.visibility = View.VISIBLE
        b.map.clear()
        b.tvPath.text = pathLabel(target)
        b.map.onTapTile = { e ->
            if (e.isDir) {
                if (!e.children.isNullOrEmpty()) { mapStack.add(e); renderMap() }
            } else {
                open(e.file)
            }
        }
        b.map.onLongTile = { e -> treemapMenu(e) }
        val scanner = TreemapScanner(requireContext().cacheDir)
        mapScanner = scanner
        mapJob = viewLifecycleOwner.lifecycleScope.launch {
            val ticker = launch {
                while (true) {
                    b.mapStatus.text = getString(
                        R.string.treemap_scanning, scanner.scanned, Format.size(scanner.bytes),
                    )
                    kotlinx.coroutines.delay(200)
                }
            }
            val root = withContext(Dispatchers.IO) {
                runCatching { scanner.scanRoot(target) }.getOrNull()
            }
            ticker.cancel()
            if (_b == null || !mapMode) return@launch
            if (root == null || root.children.isNullOrEmpty()) {
                b.mapStatus.text = getString(
                    if (root == null) R.string.treemap_failed else R.string.treemap_empty,
                )
                return@launch
            }
            b.mapStatus.visibility = View.GONE
            mapStack.clear()
            mapStack.add(root)
            renderMap()
        }
    }

    private fun renderMap() {
        val cur = mapStack.last()
        b.map.show(cur)
        b.tvPath.text =
            getString(
                R.string.treemap_path_summary,
                pathLabel(cur.file), Format.size(cur.size), cur.children?.size ?: 0,
            )
    }

    private fun exitTreemap() {
        mapMode = false
        mapScanner?.stop = true
        mapScanner = null
        mapJob?.cancel()
        mapJob = null
        mapStack.clear()
        clearMapSelection()
        val bb = _b ?: return
        bb.map.clear()
        bb.map.visibility = View.GONE
        bb.mapStatus.visibility = View.GONE
        bb.list.visibility = View.VISIBLE
        host?.onClipTargetChanged()
        render(viewModel.state.value) // 恢复路径栏/空态
    }

    /** 返回键路由:占用图内先回上级,到根再退出占用图;非占用图不消费。 */
    fun handleBack(): Boolean {
        if (!mapMode) return false
        if (mapStack.size > 1) {
            mapStack.removeAt(mapStack.size - 1)
            renderMap()
        } else {
            exitTreemap()
        }
        return true
    }

    private fun toggleMapSelection(e: TreemapEntry) {
        if (!mapSelected.remove(e)) mapSelected.add(e)
        b.map.selectedKeys = mapSelected.mapTo(HashSet()) { TreemapView.keyOf(it) }
    }

    private fun clearMapSelection() {
        if (mapSelected.isEmpty()) return
        mapSelected.clear()
        _b?.map?.selectedKeys = emptySet()
    }

    /** 块菜单 = 选择 + 树的目录/文件长按菜单(共用)+ 删除。 */
    private fun treemapMenu(e: TreemapEntry) {
        val actions = ArrayList<MenuAct>()
        actions.item(getString(R.string.action_select), R.drawable.ic_sel_check) { toggleMapSelection(e) }
        actions += commonFileActions(e.file, includeDelete = false)
        actions.item(getString(R.string.strip_delete), R.drawable.ic_delete, DANGER) { confirmDeleteEntry(e) }
        showActionMenu(requireContext(), e.name, actions)
    }

    private fun confirmDeleteEntry(e: TreemapEntry) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.treemap_confirm_delete, e.name, Format.size(e.size)))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                runIo({ FsRegistry.of(e.file).delete(e.file) }) {
                    // 从图里摘掉并向上扣减大小,原地刷新;树那边照常重列
                    e.parent?.children?.remove(e)
                    var p = e.parent
                    while (p != null) { p.size -= e.size; p = p.parent }
                    while (mapStack.size > 1 && mapStack.last() === e) {
                        mapStack.removeAt(mapStack.size - 1)
                    }
                    if (mapMode && mapStack.isNotEmpty()) renderMap()
                    viewModel.refresh()
                }
            }
            .show()
    }

    // ---- 文件搜索(递归通配符,结果挂成树上的虚拟目录) ----

    /** 上次输入的通配符,弹框预填,连续多次搜索不用重新打字。 */
    private var lastSearchPattern = ""

    /** 操作栏「搜索」:对绿色框选的目录(未框选时当前目录)发起递归搜索。 */
    fun actionSearch() {
        val t = viewModel.currentSelection() ?: viewModel.currentDir
        if (t == null || !t.isDir) {
            toast(getString(R.string.msg_pick_dir_first)); return
        }
        promptSearch(t)
    }

    /** 弹框输入通配符(如 `*.jpg`;不含通配符则退化为子串搜索),确认后对 [dir] 递归发起搜索。 */
    private fun promptSearch(dir: XFile) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.search_hint)
            setText(lastSearchPattern)
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.strip_search))
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val pattern = input.text.toString().trim()
                if (pattern.isEmpty()) return@setPositiveButton
                lastSearchPattern = pattern
                viewModel.startSearch(dir, pattern)
            }
            .show()
    }

    /** 搜索虚拟目录长按菜单:对侧显示(被搜索的目录)+ 属性(统计,点了才弹,不再长按直接弹)。 */
    private fun searchNodeMenu(node: PaneViewModel.SearchNode) {
        val actions = ArrayList<MenuAct>()
        actions.item(getString(R.string.treemap_reveal), R.drawable.ic_pane_to_right) {
            revealInSibling(node.root)
        }
        actions.item(getString(R.string.file_info), R.drawable.ic_info) { showSearchStats(node) }
        showActionMenu(
            requireContext(),
            getString(R.string.search_result_title, node.matchedFiles + node.matchedDirs),
            actions,
        )
    }

    /** 统计弹框:匹配文件/目录分别计数,扫描未完成时附加提示。 */
    private fun showSearchStats(node: PaneViewModel.SearchNode) {
        val msg = getString(
            R.string.search_stats, pathLabel(node.root), node.pattern, node.matchedFiles, node.matchedDirs,
        ) + if (node.scanning) getString(R.string.search_scanning_suffix) else ""
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.file_info)
            .setMessage(msg)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    fun actionNewFolder() {
        val dir = viewModel.currentDir ?: return toast(getString(R.string.msg_pick_dir_first))
        performNewFolder(dir)
    }

    /** 直接对 [dir] 建文件夹,不依赖 currentDir——目录长按菜单用,未展开/未点选过也能建。 */
    private fun performNewFolder(dir: XFile) {
        val input = EditText(requireContext()).apply { hint = getString(R.string.hint_name) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_new_folder)
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                runIo({ FsRegistry.of(dir).mkdir(dir, name) }) {
                    viewModel.invalidate(dir); viewModel.refresh()
                }
            }
            .show()
    }

    /**
     * 在 [dir] 下建一个空文本文件,建完直接进编辑态——新建一个空文件本身没什么用,
     * 用户要的是马上开始写。默认名的扩展名不自动补:.md/.sh/.json 都常见,预选中
     * 主名部分让用户直接改掉更省事。
     */
    private fun performNewTextFile(dir: XFile) {
        val preset = getString(R.string.new_text_default)
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.hint_name)
            setText(preset)
            setSelection(0, preset.lastIndexOf('.').let { if (it <= 0) preset.length else it })
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_new_text)
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                var created: XFile? = null
                runIo({
                    val fs = FsRegistry.of(dir)
                    // createFile 对多数实现只是拼路径,但 SAF 那种会真的建文档,
                    // 所以只调一次,拿到的 target 一路用到底。
                    val target = fs.createFile(dir, name)
                    if (fs.exists(target)) throw FsException(getString(R.string.new_text_exists, name))
                    fs.openOutput(target).use { } // 建成 0 字节
                    created = target
                }) {
                    viewModel.invalidate(dir); viewModel.refresh()
                    created?.let { TextViewerActivity.start(requireContext(), it, edit = true) }
                }
            }
            .show()
    }

    /** 当前勾选的文件(供选择器宿主取多选结果);没勾选则为空。 */
    fun checkedFiles(): List<XFile> = adapter.selectedItems()

    /** 勾选项(占用图内优先取图上选中的块);未勾选时退回"绿色框选"的当前节点。 */
    private fun selectionOrCurrent(): List<XFile> = when {
        mapMode && mapSelected.isNotEmpty() -> mapSelected.map { it.file }
        else -> adapter.selectedItems().ifEmpty { listOfNotNull(viewModel.currentSelection()) }
    }

    fun actionRename() {
        val sel = selectionOrCurrent()
        if (sel.size != 1) {
            toast(getString(R.string.msg_pick_one_to_rename)); return
        }
        performRename(sel.first())
    }

    private fun performRename(target: XFile) {
        if (target.scheme == AppsFileSystem.SCHEME) {
            toast(getString(R.string.apps_read_only)); return
        }
        val input = EditText(requireContext()).apply { setText(target.name) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_rename)
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == target.name) return@setPositiveButton
                runIo({ FsRegistry.of(target).rename(target, name) }) {
                    adapter.clearSelection(); clearMapSelection(); viewModel.refresh()
                }
            }
            .show()
    }

    fun actionDelete() {
        val sel = selectionOrCurrent()
        if (sel.isEmpty()) {
            toast(getString(R.string.msg_no_selection)); return
        }
        performDelete(sel)
    }

    /**
     * 删除。应用条目的"删除"语义是**卸载**,交给系统卸载界面(自带确认框,所以不叠我们
     * 这一层确认);跨来源混选时应用走卸载、其余照常删。
     */
    private fun performDelete(sel: List<XFile>) {
        val apps = sel.filter { it.scheme == AppsFileSystem.SCHEME }
        if (apps.isNotEmpty()) uninstallApps(apps)
        val rest = sel.filterNot { it.scheme == AppsFileSystem.SCHEME }
        if (rest.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.confirm_delete, rest.size))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                runIo({ rest.forEach { FsRegistry.of(it).delete(it) } }) {
                    adapter.clearSelection(); clearMapSelection(); viewModel.refresh()
                }
            }
            .show()
    }

    // ---- 应用管理(scheme=apps) ----

    /** 待卸载的包名队列:系统卸载界面一次只接一个,批量得排队(见 [uninstallNext])。 */
    private val uninstallQueue = ArrayDeque<String>()

    /** 卸载完成(或用户取消)后回来:队列里还有就接着起下一个,空了才刷树。 */
    private val uninstallLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            if (!uninstallNext()) {
                adapter.clearSelection(); clearMapSelection(); viewModel.refresh()
            }
        }

    private fun appsFs(): AppsFileSystem? =
        runCatching { FsRegistry.of(AppsFileSystem.SCHEME) }.getOrNull() as? AppsFileSystem

    /**
     * 起系统卸载界面(每个应用一次,系统自己弹确认)。系统应用卸载不掉——只在"已安装"
     * 分类给卸载入口,这里再兜一次底,免得从占用图/多选等别的路径漏过来。
     *
     * 多选时**必须排队**:连着 launch 几个卸载 intent,系统只会显示最后一个,前面的
     * 静默丢掉。入队后由 [uninstallNext] 一个一个起,前一个回来了才起下一个。
     */
    private fun uninstallApps(files: List<XFile>) {
        val fs = appsFs() ?: return
        val busy = uninstallQueue.isNotEmpty()
        var skippedSystem = false
        for (f in files) {
            if (isSystemApp(f)) { skippedSystem = true; continue }
            fs.packageOf(f)?.let { uninstallQueue.addLast(it) }
        }
        if (skippedSystem) toast(getString(R.string.apps_uninstall_system))
        if (!busy) uninstallNext()
    }

    /**
     * 起队首那个包的系统卸载界面;队列空了返回 false(调用方据此收尾刷树)。
     * ★ 这个 intent 要求 manifest 声明 `REQUEST_DELETE_PACKAGES`(Android 8+),
     * 否则系统卸载界面直接 finish,表现为「点了没反应」。
     */
    private fun uninstallNext(): Boolean {
        while (true) {
            val pkg = uninstallQueue.firstOrNull() ?: return false
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, android.net.Uri.parse("package:$pkg"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            val ok = runCatching { uninstallLauncher.launch(intent) }
                .onFailure { toast(it.message ?: "") }
                .isSuccess
            // 起失败的(设备没有卸载界面等)直接跳过它继续下一个,别把整队卡死
            uninstallQueue.removeFirst()
            if (ok) return true
        }
    }

    /** 系统设置里的「应用信息」页(权限/存储/停用,系统应用的"卸载更新"也在那里)。 */
    private fun openAppInfo(file: XFile) {
        val pkg = appsFs()?.packageOf(file) ?: return
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$pkg"),
        )
        runCatching { startActivity(intent) }.onFailure { toast(it.message ?: "") }
    }

    /** [fallbackToInfo]:没有启动入口时改开应用信息页(点击行走这条,菜单项只提示)。 */
    private fun launchApp(file: XFile, fallbackToInfo: Boolean = false) {
        val pkg = appsFs()?.packageOf(file) ?: return
        val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            if (fallbackToInfo) openAppInfo(file) else toast(getString(R.string.apps_launch_failed))
            return
        }
        runCatching { startActivity(intent) }.onFailure { toast(it.message ?: "") }
    }

    /** 应用条目是否在「系统」分类下(路径形如 apps:/system/<包名>)。 */
    private fun isSystemApp(file: XFile): Boolean =
        file.scheme == AppsFileSystem.SCHEME && file.path.startsWith("/system/")

    // ---- 剪贴板(跨面板暂存 + 粘贴) ----
    // 栏本身在 MainActivity(横跨整个窗口),这里只提供"目标目录"和真正的搬运。

    /** 覆盖式放入剪贴板(不追加),栏由 [FileClipboard] 的 flow 自己刷新。 */
    private fun addToClipboard(files: List<XFile>) {
        if (files.isEmpty()) return
        FileClipboard.put(files)
        toast(getString(R.string.msg_clip_added, files.size))
    }

    /**
     * 本面板作为粘贴目标时的落点 = "绿色框选"的当前目录。占用图模式下绿框看不见,
     * 返回 null 让栏上提示先选目录,免得粘到一个用户此刻根本看不到的地方。
     */
    fun clipTarget(): XFile? = if (mapMode) null else viewModel.currentDir

    /** 粘贴目标的显示文案(栏上那行);无目标返回 null。 */
    fun clipTargetLabel(): String? = clipTarget()?.let { pathLabel(it) }

    /**
     * 粘贴到本面板的当前目录:**不弹确认框**直接开搬——目标和复制/移动模式都写在剪贴板栏
     * 上,点粘贴前就看得见,再确认一次纯属多余。进度框和同名冲突处理照旧。
     */
    fun pasteFromClipboard() {
        val items = FileClipboard.items
        if (items.isEmpty()) return
        val dest = clipTarget()
        if (dest == null) {
            toast(getString(R.string.msg_pick_dir_first)); return
        }
        if (!dest.isWritableDir()) {
            toast(getString(R.string.msg_dest_not_writable)); return
        }
        // 栏上的按钮已按这个理由置灰,这里兜底:绿框刚动、栏还没重绘的一瞬也点不成
        FileClipboard.pasteBlockReason(dest)?.let { toast(getString(it)); return }
        startTransfer(items, dest, FileClipboard.move, plan0 = null, fromClipboard = true)
    }

    /** 把勾选项(未勾选时为绿色框选的当前节点)复制/移动到另一面板的当前目录。 */
    fun actionCopy(move: Boolean) {
        val sel = selectionOrCurrent()
        if (sel.isEmpty()) {
            toast(getString(R.string.msg_no_selection)); return
        }
        performCopy(sel, move)
    }

    private fun performCopy(sel: List<XFile>, move: Boolean) {
        // 移动 = 复制 + 删源,而应用删不掉(卸载是另一回事);操作栏的「移动」绕过菜单,
        // 在这里兜底,免得复制完了才在删源那步失败
        if (move && sel.any { it.scheme == AppsFileSystem.SCHEME }) {
            toast(getString(R.string.apps_read_only)); return
        }
        val sibling = host?.siblingOf(this)
        val dest = sibling?.viewModel?.currentDir
        if (dest == null) {
            toast(getString(R.string.msg_pick_dir_first)); return
        }
        if (!dest.isWritableDir()) {
            toast(getString(R.string.msg_dest_not_writable)); return
        }

        // ---- 确认框:源 + 大小(后台统计)+ 目标 + 移动模式 ----
        val cb = DialogCopyConfirmBinding.inflate(layoutInflater)
        cb.tvSrc.text = if (sel.size == 1) sel[0].name else getString(R.string.copy_items, sel.size)
        cb.tvSize.text = "…"
        cb.tvDest.text = pathLabel(dest)
        cb.ivDest.setImageResource(pathIcon(dest))
        cb.cbMove.isChecked = move
        var plan: CopyEngine.Plan? = null
        viewLifecycleOwner.lifecycleScope.launch {
            val p = runCatching { withContext(Dispatchers.IO) { CopyEngine.plan(sel) } }.getOrNull()
            plan = p
            if (p != null && _b != null) cb.tvSize.text = Format.size(p.bytes)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.copy_title)
            .setView(cb.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                startTransfer(sel, dest, cb.cbMove.isChecked, plan)
            }
            .show()
    }

    /** 交给后台会话搬运,并挂上进度框(见 [Transfers])。 */
    private fun startTransfer(
        sel: List<XFile>,
        dest: XFile,
        move: Boolean,
        plan0: CopyEngine.Plan?,
        fromClipboard: Boolean = false,
    ) {
        launchSession(
            Transfers.Session(
                Transfers.Work.Copy(sel, dest, move, fromClipboard),
                if (move) R.string.progress_move else R.string.progress_copy,
                pathLabel(dest), pathIcon(dest), plan0,
            ),
        )
    }

    /** 起会话 + 弹进度框;已有传输在跑时不抢(会话是单例,见 [Transfers.start])。 */
    private fun launchSession(session: Transfers.Session) {
        val started = runCatching { Transfers.start(requireContext().applicationContext, session) }
            .getOrElse { toast(it.message ?: getString(R.string.err_failed)); return }
        if (!started) { toast(getString(R.string.transfer_busy)); return }
        showTransferBox()
    }

    /**
     * 把进度框挂到正在跑的会话上(新起任务、或从通知栏点回来时)。
     * 会话不在了/框已经开着就什么都不做。
     */
    fun showTransferBox() {
        // active 由传输线程置空(跑完那一刻),先取到本地再判,别在 !! 上撞空
        val session = Transfers.active ?: return
        if (_b == null || Transfers.ui != null) return
        progressBox = TransferBox(
            requireContext(), layoutInflater, session,
            alive = { _b != null },
            onBackground = {
                requestNotifPermission()
                toast(getString(R.string.transfer_background_hint))
            },
            onDetach = { progressBox = null },
            onFinished = { finishTransfer(it) },
        )
    }

    private var progressBox: TransferBox? = null

    /**
     * 传输收尾:清选中、刷两侧面板、提示结果。会话可能在界面不在场时跑完,那时由
     * [MainActivity] 回到前台后调这里补上(所以不能只写在进度框里)。
     */
    fun finishTransfer(s: Transfers.Session) {
        adapter.clearSelection()
        clearMapSelection()
        // 粘贴完就收工:移动过的源头已不在,复制完这一趟也算办完了(要再粘一次重新放)
        if ((s.work as? Transfers.Work.Copy)?.fromClipboard == true) FileClipboard.clear()
        viewModel.refresh()
        host?.siblingOf(this)?.viewModel?.refresh()
        val r = s.finished ?: return
        r.fold(
            onSuccess = {
                toast(getString(if (s.cancelled.get()) R.string.dialog_cancel else R.string.msg_done))
            },
            onFailure = {
                toast(
                    if (it is ArchiveWriter.Cancelled) getString(R.string.dialog_cancel)
                    else it.message ?: getString(R.string.err_failed),
                )
            },
        )
        host?.onTransferFinished(s)
    }

    /** Android 13+ 通知要运行时授权;转后台那一刻才问——之前通知只是个附带展示。 */
    private fun requestNotifPermission() {
        val act = activity ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    // ---- 压缩(打包到对侧当前目录) ----

    /** 操作栏「压缩」:勾选项(未勾选时为绿色框选的当前节点)打包到对侧当前目录。 */
    fun actionCompress() {
        val sel = selectionOrCurrent()
        if (sel.isEmpty()) {
            toast(getString(R.string.msg_no_selection)); return
        }
        performCompress(sel)
    }

    /**
     * 压缩确认框:文件名(默认见下)+ 格式(zip/7z)+ 移动模式;目标固定为对侧当前目录,
     * 与操作栏复制/移动一致。
     *
     * 默认名:只有一项(从文件/目录菜单点进来也是这种)时用该项的名字——文件去掉扩展名;
     * 多项时用它们所在目录的名字。扩展名跟着格式走,切格式即改写。
     */
    private fun performCompress(sel: List<XFile>) {
        val sibling = host?.siblingOf(this)
        val dest = sibling?.viewModel?.currentDir
        if (dest == null) {
            toast(getString(R.string.msg_pick_dir_first)); return
        }
        if (!dest.isWritableDir()) {
            toast(getString(R.string.msg_dest_not_writable)); return
        }
        val cb = DialogCompressBinding.inflate(layoutInflater)
        cb.tvSrc.text = if (sel.size == 1) sel[0].name else getString(R.string.copy_items, sel.size)
        cb.tvDest.text = pathLabel(dest)
        cb.ivDest.setImageResource(pathIcon(dest))
        cb.etName.setText("${defaultArchiveName(sel)}.${ArchiveWriter.Format.ZIP.ext}")
        cb.rgFormat.setOnCheckedChangeListener { _, id ->
            val stem = stripArchiveExt(cb.etName.text.toString())
            cb.etName.setText("$stem.${formatOf(id).ext}")
            cb.etName.setSelection(stem.length) // setText 会把光标弹回开头,放回名字末尾接着打
        }
        // 「加密包只能读不能改」这条只在真要加密时才提,平时不占版面
        cb.etPassword.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                cb.tvEncryptNote.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })
        var plan: CopyEngine.Plan? = null
        viewLifecycleOwner.lifecycleScope.launch {
            val p = runCatching { withContext(Dispatchers.IO) { CopyEngine.plan(sel) } }.getOrNull()
            plan = p
            if (p != null && _b != null) {
                cb.tvSrc.text = "${cb.tvSrc.text}  ${Format.size(p.bytes)}"
            }
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.compress_title)
            .setView(cb.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = cb.etName.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                startCompress(
                    sel, dest, name, formatOf(cb.rgFormat.checkedRadioButtonId),
                    cb.cbMove.isChecked, plan, cb.etPassword.text.toString(),
                )
            }
            .create()
        // 开框即改名:键盘直接弹出(ALWAYS_ 无视"用户上次手动收起键盘"的状态,必须 show 前设),
        // 焦点落在名字上并**只选中扩展名之前的部分**——直接打字换掉名字,.zip/.7z 留着
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        cb.etName.requestFocus()
        cb.etName.setSelection(0, stripArchiveExt(cb.etName.text.toString()).length)
    }

    private fun formatOf(checkedId: Int): ArchiveWriter.Format =
        if (checkedId == R.id.rb_7z) ArchiveWriter.Format.SEVEN_Z else ArchiveWriter.Format.ZIP

    /** 去掉已有的归档扩展名(切换格式时换扩展名用),其他扩展名原样保留。 */
    private fun stripArchiveExt(name: String): String {
        val lower = name.lowercase()
        for (f in ArchiveWriter.Format.entries) {
            if (lower.endsWith(".${f.ext}")) return name.dropLast(f.ext.length + 1)
        }
        return name
    }

    /** 单项 → 该项名字(文件去扩展名);多项 → 所在目录名。 */
    private fun defaultArchiveName(sel: List<XFile>): String {
        if (sel.size == 1) {
            val f = sel[0]
            val n = f.name
            return if (f.isDir) n else n.substringBeforeLast('.', n).ifEmpty { n }
        }
        val parent = sel[0].parentPath.trimEnd('/').substringAfterLast('/')
        return parent.ifEmpty { viewModel.currentDir?.name?.trimEnd('/') ?: "" }.ifEmpty { "archive" }
    }

    /** 目标同名先问覆盖,再进 [runCompress]。 */
    private fun startCompress(
        sel: List<XFile>,
        dest: XFile,
        name: String,
        format: ArchiveWriter.Format,
        move: Boolean,
        plan0: CopyEngine.Plan?,
        password: String,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val destFs = FsRegistry.of(dest)
            val target = runCatching { withContext(Dispatchers.IO) { destFs.createFile(dest, name) } }
                .getOrElse { toast(it.message ?: getString(R.string.err_failed)); return@launch }
            val exists = withContext(Dispatchers.IO) {
                runCatching { destFs.exists(target) }.getOrDefault(false)
            }
            if (_b == null) return@launch
            if (exists) {
                AlertDialog.Builder(requireContext())
                    .setMessage(getString(R.string.compress_overwrite, name))
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .setPositiveButton(R.string.dialog_ok) { _, _ ->
                        runCompress(sel, dest, target, format, move, plan0, password)
                    }
                    .show()
            } else {
                runCompress(sel, dest, target, format, move, plan0, password)
            }
        }
    }

    /** 打包同样交给后台会话(复制/压缩共用一个进度框与一条前台服务)。 */
    private fun runCompress(
        sel: List<XFile>,
        dest: XFile,
        target: XFile,
        format: ArchiveWriter.Format,
        move: Boolean,
        plan0: CopyEngine.Plan?,
        password: String,
    ) {
        launchSession(
            Transfers.Session(
                Transfers.Work.Compress(sel, dest, target, format, move, password.ifEmpty { null }),
                R.string.progress_compress,
                pathLabel(target), pathIcon(target), plan0,
            ),
        )
    }

    /**
     * 加密压缩包的密码框。展开一个加密包时由 [PaneViewModel.State.passwordFor] 触发,
     * 交互与 restic 解锁一致(可勾选保存)。密码不对就原地再问一次,不用重新点开包。
     *
     * 同一个包只弹一个框:state 是 StateFlow,弹框期间任何一次 render 都会再看到
     * 同一个 passwordFor。
     */
    private var pwDialogFor: String? = null

    private fun askArchivePassword(archive: XFile) {
        val key = "${archive.scheme}:${archive.path}"
        if (pwDialogFor == key) return
        val ctx = context ?: return
        pwDialogFor = key
        val pw = EditText(ctx).apply {
            hint = getString(R.string.archive_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = android.widget.CheckBox(ctx).apply { text = getString(R.string.archive_save_pw) }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(pw)
            addView(save)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.archive_locked, archive.name))
            .setView(box)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val text = pw.text.toString()
                if (text.isEmpty()) return@setPositiveButton
                viewModel.unlockArchive(archive, text, save.isChecked) { ok ->
                    if (ok || _b == null) return@unlockArchive
                    toast(getString(R.string.archive_wrong_pw))
                    askArchivePassword(archive) // 原地再问,不用重新点开这个包
                }
            }
            .setOnDismissListener { pwDialogFor = null }
            .show()
    }

    /** 解锁 restic 仓库:有保存的密码则直接用,否则弹密码框(可勾选保存)。 */
    private fun unlockRestic(node: PaneViewModel.ResticNode) {
        val ctx = requireContext()
        val saved = Prefs.resticPassword(ctx, node.repoDir.path)
        if (saved != null) {
            doUnlock(node, saved, save = false)
            return
        }
        val pw = EditText(ctx).apply {
            hint = getString(R.string.restic_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.restic_save_pw)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(pw)
            addView(save)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.restic_repo)
            .setView(box)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val text = pw.text.toString()
                if (text.isNotEmpty()) doUnlock(node, text, save.isChecked)
            }
            .show()
    }

    private fun doUnlock(node: PaneViewModel.ResticNode, password: String, save: Boolean) {
        toast(getString(R.string.restic_unlocking))
        viewModel.unlockRestic(node.repoDir, password) { ok, err ->
            if (ok) {
                if (save) Prefs.setResticPassword(requireContext(), node.repoDir.path, password)
            } else {
                toast(getString(R.string.restic_wrong_pw, err ?: ""))
            }
        }
    }

    // ---- 长按上下文菜单 ----

    private fun onLongClick(node: PaneViewModel.Node) {
        when (node) {
            is PaneViewModel.FileNode -> longClickFile(node)
            is PaneViewModel.ServerNode -> serverMenu(node)
            is PaneViewModel.FavoriteNode -> favoriteMenu(node)
            is PaneViewModel.CompareNode -> compareFavMenu(node)
            is PaneViewModel.SearchNode -> searchNodeMenu(node)
            else -> Unit
        }
    }

    private fun longClickFile(node: PaneViewModel.FileNode) {
        if (node.label != null) { rootNodeMenu(node); return } // 顶级存储节点走"不可改动的目录"那套
        // 长按的是已勾选的多选项之一(且不止它自己)→ 走批量专属菜单;
        // 否则(未勾选,或只有它自己被选中)按单文件正常菜单处理。
        val selItems = adapter.selectedItems()
        if (adapter.isSelected(node) && selItems.size > 1) {
            showBatchMenu(selItems)
            return
        }
        // 树节点专属项(选择/属性/刷新/以压缩包打开)+ 与占用图共用的通用项
        val actions = ArrayList<MenuAct>()
        actions.item(getString(R.string.action_select), R.drawable.ic_sel_check) { adapter.toggleSelection(node) }
        actions.item(getString(R.string.file_info), R.drawable.ic_info) { viewModel.toggleInfo(node) }
        if (node.expandable) {
            actions.item(getString(R.string.action_refresh), R.drawable.ic_refresh) { viewModel.refreshNode(node) }
        }
        if (!node.file.isDir && OpenFiles.isApk(node.file)) {
            actions.item(getString(R.string.open_as_archive), R.drawable.ic_file_archive) {
                viewModel.openAsArchive(node)
            }
        }
        if (node.file.isDir) {
            // 直接对该目录操作,不依赖 currentDir——未展开/未点选过也能建/能占用分析
            actions.item(getString(R.string.action_new_folder), R.drawable.ic_new_folder) {
                performNewFolder(node.file)
            }
            if (node.file.isWritableDir()) {
                actions.item(getString(R.string.action_new_text), R.drawable.ic_file_doc) {
                    performNewTextFile(node.file)
                }
            }
            actions.item(getString(R.string.strip_map), R.drawable.ic_treemap) { enterTreemap(node.file) }
            actions.item(getString(R.string.strip_search), R.drawable.ic_search) { promptSearch(node.file) }
        }
        actions += commonFileActions(node.file)
        showActionMenu(requireContext(), node.file.name, actions)
    }

    /**
     * 多选(勾选数 > 1)专属菜单:只保留批量语义明确的操作(刷新缩略图/复制/移动/删除);
     * 选择/属性/以压缩包打开/打开方式/重命名/收藏等单文件专属项在批量场景没有意义,不显示。
     * 标题用勾选个数取代文件名。
     */
    private fun showBatchMenu(targets: List<XFile>) {
        val actions = ArrayList<MenuAct>()
        if (targets.any { it.isDir || Thumbs.canThumb(it) }) {
            actions.item(getString(R.string.action_refresh_thumb), R.drawable.ic_file_image) {
                refreshThumb(targets)
            }
        }
        actions.item(getString(R.string.action_clip_add), R.drawable.ic_clipboard) { addToClipboard(targets) }
        actions.item(getString(R.string.strip_copy), R.drawable.ic_copy) { performCopy(targets, move = false) }
        actions.item(getString(R.string.strip_move), R.drawable.ic_move) { performCopy(targets, move = true) }
        actions.item(getString(R.string.strip_compress), R.drawable.ic_compress) { performCompress(targets) }
        actions.item(getString(R.string.strip_delete), R.drawable.ic_delete, DANGER) { performDelete(targets) }
        showActionMenu(requireContext(), getString(R.string.title_selected_count, targets.size), actions)
    }

    /**
     * 树与占用图共用的菜单项:目录(对侧显示/幻灯片/Git/终端/桌面快捷方式)、文件(打开)、
     * 收藏 + 中间操作栏同款 复制/移动/改名(+删除,占用图块菜单自带专属删除,由
     * [includeDelete] 关掉这里的通用版避免重复)。始终只针对 [file] 单个文件——批量操作
     * 走 [showBatchMenu],不复用这里。
     * [includeEdit] 关掉整组 复制/移动/改名/删除,[includeFavorite] 关掉"添加到收藏"
     * ——收藏行自己的菜单用([favoriteMenu]:改名/删除的是收藏所指的真实目录,语义上
     * 太容易和"取消收藏"混淆;而它本来就已经是收藏了)。[includeShell] 关掉 SFTP 目录的
     * 终端/命令快捷方式——服务器行自己给的那两项落在 home 而不是根路径,见 [serverMenu]。
     */
    private fun commonFileActions(
        file: XFile,
        includeDelete: Boolean = true,
        includeEdit: Boolean = true,
        includeFavorite: Boolean = true,
        includeShell: Boolean = true,
    ): ArrayList<MenuAct> {
        val actions = ArrayList<MenuAct>()
        if (file.isDir) {
            if (file.scheme == "file" || viewModel.isConnScheme(file.scheme)) {
                actions.item(getString(R.string.treemap_reveal), R.drawable.ic_pane_to_right) {
                    revealInSibling(file)
                }
            }
            actions.item(getString(R.string.slideshow), R.drawable.ic_play) { startSlideshow(file) }
            if (file.scheme == "file" && com.twig.git.GitRepo.isRepo(java.io.File(file.path))) {
                actions.item("Git", R.drawable.ic_git) { GitActivity.start(requireContext(), file.path) }
            }
            // 本地目录:以此为工作目录开一条本地 shell(系统自带 mksh + toybox)
            if (includeShell && file.scheme == "file") {
                actions.item(getString(R.string.terminal_here), R.drawable.ic_terminal) {
                    TerminalActivity.startLocal(requireContext(), file.path)
                }
                // 开着特权访问时**另起一项**,不改原来那条的行为 —— 把普通 shell 悄悄
                // 换成 root,用户以为自己在应用 uid 下试命令,实际一条 rm 就是全盘。
                // 身份写在菜单文案里,点之前就知道自己要进哪儿。
                privTerminalLabel()?.let { (label, mode) ->
                    actions.item(label, R.drawable.ic_terminal) {
                        TerminalActivity.startLocal(requireContext(), file.path, mode)
                    }
                }
            }
            // SFTP 目录:以此为工作目录开终端
            if (includeShell &&
                runCatching { FsRegistry.of(file) }.getOrNull() is com.twig.fs.network.SftpFileSystem
            ) {
                actions.item(getString(R.string.terminal_here), R.drawable.ic_terminal) {
                    TerminalActivity.start(requireContext(), file.scheme, file.name, file.path)
                }
                viewModel.connOf(file.scheme)?.let { conn ->
                    actions.item(getString(R.string.cmd_shortcut), R.drawable.ic_play) {
                        showCommandDialog(conn, file.scheme, file.path, getString(R.string.cmd_title_dir, file.name))
                    }
                }
            }
            actions.item(getString(R.string.action_pin_shortcut), R.drawable.ic_shortcut) { pinFileShortcut(file) }
            // WiFi 共享这一个目录:范围直接带过去,不用再去主菜单里选
            actions.item(getString(R.string.share_dir_menu), R.drawable.ic_share_wifi) {
                (activity as? androidx.appcompat.app.AppCompatActivity)
                    ?.let { ShareDialogs.show(it, file) }
            }
        } else if (file.scheme == AppsFileSystem.SCHEME) {
            // 应用条目:打开/预览没有意义(点开就是装一遍自己),换成应用自己的三件事
            actions.item(getString(R.string.apps_launch), R.drawable.ic_play) { launchApp(file) }
            actions.item(getString(R.string.apps_app_info), R.drawable.ic_info) { openAppInfo(file) }
            actions.item(getString(R.string.action_share), R.drawable.ic_share) { shareFile(file) }
            if (!isSystemApp(file)) {
                actions.item(getString(R.string.apps_uninstall), R.drawable.ic_delete, DANGER) {
                    uninstallApps(listOf(file))
                }
            }
        } else {
            actions.item(getString(R.string.open_with_external), R.drawable.ic_open_with) { openExternal(file) }
            actions.item(getString(R.string.open_how), R.drawable.ic_tune) { chooseOpen(file) }
            if (OpenFiles.isPreviewable(file)) {
                actions.item(getString(R.string.viewer_preview), R.drawable.ic_preview) {
                    TextViewerActivity.start(requireContext(), file, preview = true)
                }
            }
            actions.item(getString(R.string.action_share), R.drawable.ic_share) { shareFile(file) }
        }
        if (includeFavorite) {
            viewModel.favoriteFrom(file)?.let { fav ->
                actions.item(getString(R.string.action_add_favorite), R.drawable.ic_star) {
                    viewModel.addFavorite(fav); toast(getString(R.string.msg_added_favorite))
                }
            }
        }
        if (file.isDir || Thumbs.canThumb(file)) {
            actions.item(getString(R.string.action_refresh_thumb), R.drawable.ic_file_image) {
                refreshThumb(listOf(file))
            }
        }
        // 只是暂存,不动源文件——收藏行那套(includeEdit=false)也给,收藏目录同样能被粘贴到别处
        actions.item(getString(R.string.action_clip_add), R.drawable.ic_clipboard) { addToClipboard(listOf(file)) }
        if (includeEdit) {
            // 应用条目只读:复制/打包(纯读源)照给,移动/改名/删除去掉——移动的"删源"
            // 这一步在应用上不成立,删除的语义是卸载,已由上面的「卸载」项覆盖
            val appEntry = file.scheme == AppsFileSystem.SCHEME
            actions.item(getString(R.string.strip_copy), R.drawable.ic_copy) {
                performCopy(listOf(file), move = false)
            }
            if (!appEntry) {
                actions.item(getString(R.string.strip_move), R.drawable.ic_move) {
                    performCopy(listOf(file), move = true)
                }
            }
            actions.item(getString(R.string.strip_compress), R.drawable.ic_compress) {
                performCompress(listOf(file))
            }
            if (!appEntry) {
                actions.item(getString(R.string.strip_rename), R.drawable.ic_rename) { performRename(file) }
                if (includeDelete) {
                    actions.item(getString(R.string.strip_delete), R.drawable.ic_delete, DANGER) {
                        performDelete(listOf(file))
                    }
                }
            }
        }
        return actions
    }

    /** 强制重新生成缩略图(可批量):清掉每个文件(目录则递归其下所有文件)的内存/磁盘
     * 缓存及失败标记,再整屏 rebind 让列表拉取新图。目录遍历可能涉及网络 I/O,
     * 异步执行,全部完成后才刷新列表一次。 */
    private fun refreshThumb(targets: List<XFile>) {
        val ctx = requireContext()
        val dirs = targets.filter { it.isDir }
        targets.filter { !it.isDir }.forEach { Thumbs.invalidate(ctx, it) }
        if (dirs.isEmpty()) {
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
            return
        }
        toast(getString(R.string.msg_thumbs_refreshing))
        var remaining = dirs.size
        dirs.forEach { dir ->
            Thumbs.invalidateDir(ctx, dir) {
                if (--remaining == 0 && isAdded && ::adapter.isInitialized) adapter.notifyDataSetChanged()
            }
        }
    }

    /** 桌面单独图标:目录展开定位到自身(含内容);文件展开父目录并滚动到该文件那一行。 */
    private fun pinFileShortcut(file: XFile) {
        val ctx = requireContext()
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            toast(getString(R.string.pin_shortcut_unsupported)); return
        }
        val revealPath = if (file.isDir) file.path else file.parentPath
        val focus = if (file.isDir) null else file.path
        val intent = MainActivity.revealIntent(ctx, file.scheme, revealPath, focus)
            .setAction(Intent.ACTION_VIEW)
        // id 按文件自身算:同目录下的两个文件现在定位到各自那一行,共用父目录算 id 会互相覆盖
        val id = "shortcut_" + (file.scheme + ":" + file.path).hashCode()
        val iconRes = if (file.isDir) R.drawable.ic_folder else FileIcons.baseIconRes(file)
        val shortcut = ShortcutInfoCompat.Builder(ctx, id)
            .setShortLabel(file.name.ifEmpty { revealPath })
            .setIcon(IconCompat.createWithResource(ctx, iconRes))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(ctx, shortcut, null)
    }

    /** 对侧显示:兄弟面板逐级展开定位到该目录并聚焦。 */
    private fun revealInSibling(dir: XFile) {
        val sib = host?.siblingOf(this) ?: return
        sib.reveal(dir)
        host?.focusPane(sib)
    }

    /** 幻灯片:查看器自己后台递归扫描该目录(含子目录)的图片、边扫边播,见
     * [ImageViewerActivity.startSlideshow]——不在这里预先收集整棵树,避免大目录/深层
     * 网络路径卡住半天才显示第一张。 */
    private fun startSlideshow(dir: XFile) {
        awaitingImageResult = true
        ImageViewerActivity.startSlideshow(requireContext(), dir)
    }

    /** 本面板刚起过图片查看器,回来时该收它的结果(两个面板都会 onResume,别抢别人的)。 */
    private var awaitingImageResult = false

    /**
     * 收图片查看器的结果:里面勾的图同步成树上的多选,删过图则刷新。查看器里的勾选可能
     * 跨目录(幻灯片是递归扫的),不在这里过滤——树上的多选本就允许跨目录(见 mapSelected)。
     */
    private fun takeImageViewerResult() {
        if (!awaitingImageResult || !::adapter.isInitialized) return
        awaitingImageResult = false
        val r = ImageViewerActivity.takeResult() ?: return
        if (r.selection.isNotEmpty()) adapter.setSelection(r.selection)
        if (r.changed) viewModel.refresh()
    }

    /**
     * "不该被改名/删除的目录行"(收藏 / 服务器根 / 顶级存储节点)共用的菜单主体:
     * 全选子项/属性/刷新/新建/占用图/搜索 + [commonFileActions](去掉 复制/移动/改名/删除
     * ——对收藏所指的真实目录、服务器根、存储根动手要么语义混淆要么没有意义)。
     * "选择"不选这一行本身(它不是可复制/删除的对象),而是全选/取消全选它下面的直接子项
     * 两态,与搜索结果行一致。
     */
    private fun dirRowActions(
        node: PaneViewModel.Node,
        target: XFile,
        refresh: () -> Unit,
        includeFavorite: Boolean = true,
        includeShell: Boolean = true,
    ): ArrayList<MenuAct> {
        val actions = ArrayList<MenuAct>()
        if (adapter.hasChildren(node)) {
            actions.item(getString(R.string.action_select_all), R.drawable.ic_sel_check) {
                adapter.toggleChildrenSelection(node)
            }
        }
        actions.item(getString(R.string.file_info), R.drawable.ic_info) { viewModel.toggleInfo(target) }
        actions.item(getString(R.string.action_refresh), R.drawable.ic_refresh, run = refresh)
        if (target.isWritableDir()) {
            actions.item(getString(R.string.action_new_folder), R.drawable.ic_new_folder) {
                performNewFolder(target)
            }
            actions.item(getString(R.string.action_new_text), R.drawable.ic_file_doc) { performNewTextFile(target) }
        }
        actions.item(getString(R.string.strip_map), R.drawable.ic_treemap) { enterTreemap(target) }
        actions.item(getString(R.string.strip_search), R.drawable.ic_search) { promptSearch(target) }
        actions += commonFileActions(
            target,
            includeEdit = false,
            includeFavorite = includeFavorite,
            includeShell = includeShell,
        )
        return actions
    }

    /**
     * 顶级存储节点(内部存储 / 根目录 / 应用管理 / 锁定根)的长按菜单:走 [dirRowActions]
     * 那套。以前这里直接 return,一个菜单项都没有——但属性/搜索/占用图/新建对存储根一样成立。
     */
    private fun rootNodeMenu(node: PaneViewModel.FileNode) {
        val actions = dirRowActions(node, node.file, refresh = { viewModel.refreshNode(node) })
        showActionMenu(requireContext(), node.label ?: node.file.name, actions)
    }

    /**
     * 收藏行的长按菜单 = [dirRowActions] + "取消收藏",去掉"添加到收藏"(它本来就是)。
     * 目录相关项要拿到真实目录才有意义,收藏还没展开过(没连上/没解锁)时只剩"取消收藏"。
     */
    private fun favoriteMenu(node: PaneViewModel.FavoriteNode) {
        val actions = ArrayList<MenuAct>()
        val target = viewModel.favoriteTarget(node)
        if (target != null) {
            actions += dirRowActions(
                node, target,
                refresh = { viewModel.refreshFavorite(node) },
                includeFavorite = false,
            )
        }
        val label = favoriteDisplayName(node.fav, node.conn)
        actions.item(getString(R.string.action_rename_favorite), R.drawable.ic_rename) {
            promptRenameFavorite(node.fav, label)
        }
        actions.item(getString(R.string.action_remove_favorite), R.drawable.ic_star, DANGER) {
            confirmRemoveFavorite(node.fav, label)
        }
        showActionMenu(requireContext(), target?.name?.ifEmpty { null } ?: label, actions)
    }

    private fun confirmRemoveFavorite(fav: com.twig.app.Favorite, label: String) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.confirm_remove_favorite, label))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> viewModel.removeFavorite(fav) }
            .show()
    }

    /** 重命名对话框:收藏/对比收藏共用同一套交互,分别落到各自 Store。 */
    private fun promptRenameLabel(title: Int, current: String, onRenamed: (String) -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(current)
            setSingleLine()
            setSelection(text.length)
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onRenamed(name)
            }
            .show()
    }

    private fun promptRenameFavorite(fav: com.twig.app.Favorite, current: String) {
        promptRenameLabel(R.string.action_rename_favorite, current) { viewModel.renameFavorite(fav, it) }
    }

    /**
     * 对比收藏行的长按菜单:不像普通收藏那样能"展开看内容"(它是两个位置的引用,不是
     * 单一可浏览目录),给"看两侧路径"代替 —— 重命名/取消收藏与普通收藏同一套交互。
     */
    private fun compareFavMenu(node: PaneViewModel.CompareNode) {
        val s = node.session
        val actions = ArrayList<MenuAct>()
        actions.item(getString(R.string.action_show_compare_paths), R.drawable.ic_compare) { showComparePaths(s) }
        actions.item(getString(R.string.action_rename_favorite), R.drawable.ic_rename) {
            promptRenameLabel(R.string.action_rename_favorite, s.label) { name -> viewModel.renameCompare(s, name) }
        }
        actions.item(getString(R.string.action_remove_favorite), R.drawable.ic_star, DANGER) {
            confirmRemoveCompare(s)
        }
        showActionMenu(requireContext(), s.label, actions)
    }

    private fun showComparePaths(s: com.twig.app.CompareSession) {
        val conns = ConnectionStore.all(requireContext()).associateBy { it.label() }
        val left = formatRawLocationPath(s.left.connLabel, s.left.path, conns[s.left.connLabel])
        val right = formatRawLocationPath(s.right.connLabel, s.right.path, conns[s.right.connLabel])
        AlertDialog.Builder(requireContext())
            .setTitle(s.label)
            .setMessage(getString(R.string.compare_paths_msg, left, right))
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun confirmRemoveCompare(s: com.twig.app.CompareSession) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.confirm_remove_favorite, s.label))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> viewModel.removeCompare(s) }
            .show()
    }

    /** 收藏点击:local/网络直接展开;restic 用已存密码,仅"无已存密码"时才弹框,失败则显示真实错误。 */
    private fun onFavoriteClick(node: PaneViewModel.FavoriteNode) {
        val saved = if (node.fav.kind == "restic") {
            Prefs.resticPassword(requireContext(), node.fav.repoPath)
        } else null
        viewModel.toggleFavorite(node, saved) { ok, err ->
            if (!ok) {
                // 无已存密码的 restic → 弹框输入;否则(网络/已存密码)直接显示真实错误
                if (node.fav.kind == "restic" && saved == null) {
                    promptFavoriteRestic(node)
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.restic_repo)
                        .setMessage(err ?: getString(R.string.err_failed))
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show()
                }
            }
        }
    }

    private fun promptFavoriteRestic(node: PaneViewModel.FavoriteNode) {
        val ctx = requireContext()
        val pw = EditText(ctx).apply {
            hint = getString(R.string.restic_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = android.widget.CheckBox(ctx).apply { text = getString(R.string.restic_save_pw) }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0); addView(pw); addView(save)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.restic_repo)
            .setView(box)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val text = pw.text.toString()
                if (text.isEmpty()) return@setPositiveButton
                viewModel.toggleFavorite(node, text) { ok, err ->
                    if (ok) {
                        if (save.isChecked) Prefs.setResticPassword(ctx, node.fav.repoPath, text)
                    } else {
                        toast(getString(R.string.restic_wrong_pw, err ?: ""))
                    }
                }
            }
            .show()
    }

    /**
     * 服务器长按菜单 = 已连接时的目录那套([dirRowActions],针对服务器根目录)
     * + 服务器自身的 编辑 / 移出(SFTP 另有 SSH 终端与命令快捷方式)。
     * 目录组只在连接过一次(拿得到根目录)时才有;没连过就还是原来那几项。
     * [dirRowActions] 的终端/命令项关掉:这里自己那两项落在登录后的默认目录(home),
     * 比按根路径 "/" 开更合用,给两份只会重复。
     */
    private fun serverMenu(node: PaneViewModel.ServerNode) {
        val conn = node.conn
        val actions = ArrayList<MenuAct>()
        viewModel.serverTarget(node)?.let { target ->
            actions += dirRowActions(
                node, target,
                refresh = { viewModel.refreshServer(node) },
                includeShell = false,
            )
        }
        actions.item(getString(R.string.server_edit), R.drawable.ic_edit) { host?.onEditServer(conn) }
        actions.item(getString(R.string.server_remove), R.drawable.ic_delete, DANGER) { confirmDeleteServer(conn) }
        if (conn.type == "sftp") {
            if (conn.hostKey.isNotEmpty()) {
                actions.item(getString(R.string.server_forget_hostkey), R.drawable.ic_close, DANGER) {
                    confirmForgetHostKey(conn)
                }
            }
            actions.item(getString(R.string.terminal_menu), R.drawable.ic_terminal) { openTerminal(conn, dir = null) }
            actions.item(getString(R.string.cmd_shortcut), R.drawable.ic_play) {
                showCommandDialog(conn, null, "", getString(R.string.cmd_title_server, conn.shortLabel()))
            }
        }
        showActionMenu(requireContext(), conn.displayLabel(), actions)
    }

    // ---- 远程命令 / 命令快捷方式 ----

    /** 「选择脚本…」回填的目标输入框(对话框存活期间有效,同私钥选取的路子)。 */
    private var scriptTarget: android.widget.EditText? = null

    private val scriptPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
            val path = r.data?.getStringExtra(PickerActivity.EXTRA_PICKED_PATH) ?: return@registerForActivityResult
            val target = scriptTarget ?: return@registerForActivityResult
            // 按扩展名补上解释器:脚本未必有 +x 权限,直接跑路径经常 Permission denied
            val q = com.twig.app.RemoteCmd.sq(path)
            target.setText(
                when (path.substringAfterLast('.', "").lowercase()) {
                    "sh", "bash" -> "bash $q"
                    "py" -> "python3 $q"
                    else -> q
                },
            )
            target.setSelection(target.text.length)
        }

    /**
     * 配置一条远程命令:可「立即执行」,也可「添加到桌面」做成快捷方式(见 [RemoteCmd])。
     *
     * [scheme] 是该服务器**本次会话内**已注册的 scheme,只用于「选择脚本」时把面板
     * 展开到那台服务器;为 null(从服务器节点进来、还没连过)时选脚本要先连一下。
     * 快捷方式本身存的是连接标签而不是 scheme——scheme 跨启动会变。
     */
    private fun showCommandDialog(
        conn: SavedConnection,
        scheme: String?,
        workdir: String,
        title: String,
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        // maxLines > 1 的框按内容自适应:默认仍是一行高,长命令自己长,到上限后内部滚动
        fun field(hint: String, text: String, maxLines: Int = 1) = android.widget.EditText(ctx).apply {
            this.hint = hint
            setText(text)
            textSize = 14f
            if (maxLines == 1) {
                setSingleLine(true)
            } else {
                setSingleLine(false)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 1
                this.maxLines = maxLines
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
        }

        val etLabel = field(getString(R.string.cmd_label), conn.shortLabel())
        val etCommand = field(getString(R.string.cmd_command), "", maxLines = 4)
        val etWorkdir = field(getString(R.string.cmd_workdir), workdir)
        // 登录 shell 只对静默执行有意义:终端里本来就是交互式登录 shell
        val cbLogin = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.cmd_login_shell)
            textSize = 14f
            visibility = View.GONE
        }
        val cbTerminal = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.cmd_in_terminal)
            isChecked = true // 默认可见地跑,静默是明确选择
            textSize = 14f
            setOnCheckedChangeListener { _, on -> cbLogin.visibility = if (on) View.GONE else View.VISIBLE }
        }
        val btnScript = android.widget.Button(ctx).apply {
            text = getString(R.string.cmd_pick_script)
            isAllCaps = false
            setOnClickListener {
                scriptTarget = etCommand
                if (scheme != null) {
                    launchScriptPicker(scheme, etWorkdir.text.toString(), conn.shortLabel())
                } else {
                    // 还没连过这台服务器:先连,拿到 scheme 再开选择器
                    toast(getString(R.string.ftp_connecting))
                    viewLifecycleOwner.lifecycleScope.launch {
                        val s = withContext(Dispatchers.IO) { viewModel.connSchemeBlocking(conn) }
                        if (s == null) toast(getString(R.string.terminal_failed))
                        else launchScriptPicker(s, etWorkdir.text.toString(), conn.shortLabel())
                    }
                }
            }
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(etLabel)
            addView(etCommand)
            addView(btnScript)
            addView(etWorkdir)
            addView(cbTerminal)
            addView(cbLogin)
        }

        fun collect(): com.twig.app.RemoteCmd? {
            val command = etCommand.text.toString().trim()
            if (command.isEmpty()) { toast(getString(R.string.cmd_empty)); return null }
            return com.twig.app.RemoteCmd(
                connLabel = conn.label(),
                workdir = etWorkdir.text.toString().trim(),
                command = command,
                inTerminal = cbTerminal.isChecked,
                loginShell = cbLogin.isChecked,
                label = etLabel.text.toString().trim().ifEmpty { command.take(24) },
            )
        }

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(R.string.cmd_run_now) { _, _ -> collect()?.let { runCommandNow(conn, scheme, it) } }
            .setPositiveButton(R.string.cmd_create) { _, _ ->
                collect()?.let {
                    if (!com.twig.app.RemoteCmd.pin(ctx, it)) toast(getString(R.string.cmd_pin_unsupported))
                }
            }
            .show()
    }

    private fun launchScriptPicker(scheme: String, startPath: String, label: String?) {
        scriptPicker.launch(
            PickerActivity.pathIntent(
                requireContext(),
                getString(R.string.cmd_pick_script),
                scheme,
                startPath,
                label,
            ),
        )
    }

    /** 立即跑一次(不建快捷方式):终端模式要 scheme,静默模式交给前台服务。 */
    private fun runCommandNow(conn: SavedConnection, scheme: String?, cmd: com.twig.app.RemoteCmd) {
        val ctx = requireContext()
        if (!cmd.inTerminal) {
            CmdService.start(ctx, cmd)
            toast(getString(R.string.cmd_started, cmd.label))
            return
        }
        if (scheme != null) {
            TerminalActivity.start(ctx, scheme, conn.shortLabel(), cmd.workdir.ifEmpty { null }, cmd.command)
            return
        }
        toast(getString(R.string.ftp_connecting))
        viewLifecycleOwner.lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { viewModel.connSchemeBlocking(conn) }
            if (s == null) toast(getString(R.string.terminal_failed))
            else TerminalActivity.start(ctx, s, conn.shortLabel(), cmd.workdir.ifEmpty { null }, cmd.command)
        }
    }

    /** 打开 SSH 终端(必要时先建立连接,IO 线程)。 */
    /**
     * 当前能开哪种特权终端 → (菜单文案, 模式);开不了返回 null。
     *
     * 判据是**特权访问已经连上**([Privileged.active]),不是"设备上有没有 su" ——
     * 没连上就给出这一项,点了只会得到一个错误,不如不显示。再叠一层
     * [PrivShell.available] 是因为"文件访问能提权"不等于"终端能起来":
     * Shizuku 那条还需要从它的 APK 里取到 rish 的 dex。
     */
    private fun privTerminalLabel(): Pair<String, Int>? {
        val mode = Privileged.active
        if (mode == Privileged.OFF) return null
        if (!PrivShell.available(requireContext(), mode)) return null
        val label = when (mode) {
            Privileged.ROOT -> getString(R.string.terminal_here_root)
            else -> getString(R.string.terminal_here_shizuku)
        }
        return label to mode
    }

    private fun openTerminal(conn: SavedConnection, dir: String?) {
        toast(getString(R.string.ftp_connecting))
        viewLifecycleOwner.lifecycleScope.launch {
            val scheme = withContext(Dispatchers.IO) { viewModel.connSchemeBlocking(conn) }
            if (scheme == null) {
                toast(getString(R.string.terminal_failed))
            } else {
                TerminalActivity.start(requireContext(), scheme, conn.displayLabel(), dir)
            }
        }
    }

    /**
     * 忘记已记住的主机密钥(TOFU 重置)。服务器重装/换机后指纹会变,那时连接会被
     * 拒绝并提示走这里;清掉之后下次连上重新记一份。
     * 顺带 forgetServer:注册表里那份 SftpFileSystem 还揣着旧的 knownHostKey,
     * 不注销的话下次展开会直接复用它、照样连不上。
     */
    private fun confirmForgetHostKey(conn: SavedConnection) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.server_forget_hostkey)
            .setMessage(getString(R.string.server_forget_hostkey_msg, conn.hostKey))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                ConnectionStore.save(requireContext(), conn.copy(hostKey = ""))
                viewModel.forgetServer(conn.label())
                host?.siblingOf(this)?.viewModel?.forgetServer(conn.label())
                host?.refreshTrees()
                toast(getString(R.string.server_forget_hostkey_done))
            }
            .show()
    }

    private fun confirmDeleteServer(conn: SavedConnection) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.confirm_delete_server, conn.displayLabel()))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                ConnectionStore.remove(requireContext(), conn)
                host?.refreshTrees()
            }
            .show()
    }

    // ---- 工具 ----

    private fun runIo(block: () -> Unit, onOk: () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { block() } }
            r.fold(onSuccess = { onOk() }, onFailure = { toast(it.message ?: getString(R.string.err_failed)) })
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        stopObservers()
        if (Prefs.rememberLocation(requireContext())) {
            Prefs.saveLocation(
                requireContext(), paneIndex,
                viewModel.expandedDescriptors(), viewModel.currentDescriptor(),
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        progressBox?.detach() // 传输不受影响,只是没人看着了(通知栏还在)
        stopObservers()
        mainHandler.removeCallbacksAndMessages(null)
        _b = null
    }

    companion object {
        private const val ARG_INDEX = "index"
        private const val ARG_LOCK_SCHEME = "lock_scheme"
        private const val ARG_LOCK_PATH = "lock_path"
        private const val ARG_LOCK_LABEL = "lock_label"

        fun newInstance(index: Int) = PaneFragment().apply {
            arguments = Bundle().apply { putInt(ARG_INDEX, index) }
        }

        /**
         * 锁定到单一来源的面板(选择器用):树上只有 [scheme] 这一个根,
         * 打开即展开到 [startPath]。
         */
        fun locked(scheme: String, startPath: String, label: String?) = PaneFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_INDEX, 0)
                putString(ARG_LOCK_SCHEME, scheme)
                putString(ARG_LOCK_PATH, startPath)
                putString(ARG_LOCK_LABEL, label)
            }
        }
    }
}
