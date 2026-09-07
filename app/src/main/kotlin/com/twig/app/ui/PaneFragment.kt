package com.twig.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.AppsFileSystem
import com.twig.app.CompareStore
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
import com.twig.app.SafFileSystem
import com.twig.app.SavedConnection
import com.twig.app.Format
import com.twig.app.favoriteDisplayName
import com.twig.app.isMovableSource
import com.twig.app.favoriteFullPath
import com.twig.app.formatLocationPath
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
import com.twig.core.isMutable
import com.twig.core.isWritableDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A single pane: path bar + the whole tree. The action buttons live in MainActivity's side action
 * column and are invoked through the public action* methods.
 * Horizontal quick swipe → notifies the host to switch panes (portrait); touch → notifies the host
 * that this pane is the active one (landscape).
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
        /** Make a pane the currently-shown one (e.g. the treemap's "Show on the other side" then focus the target pane). */
        fun focusPane(pane: PaneFragment)
        /** This pane's current directory (= the clipboard bar's paste target) changed; let the host refresh that line on the bar.
         *  Only MainActivity has this bar; hosts with embedded panes like the share-target page don't need to react. */
        fun onClipTargetChanged() {}

        /** File-picker mode: if the host takes the click (returns true), don't proceed to open / viewer. */
        fun onPickFile(file: XFile): Boolean = false

        /** Notify the host when a transfer finishes (the share-target page should close itself after copying); the main UI ignores this. */
        fun onTransferFinished(session: Transfers.Session) {}

        /** Open the SAF folder-grant picker (jumps straight there if [initial] is non-null); only the main UI accepts this. */
        fun openSafPicker(initial: android.net.Uri?) {}
    }


    private var _b: FragmentPaneBinding? = null
    private val b get() = _b!!

    val viewModel: PaneViewModel by viewModels()
    private lateinit var adapter: FileAdapter
    private var pendingScrollToCurrent = false // Scroll once to the current directory after restoring the previous position.

    // inotify watches on already-expanded local directories (real-time sync when external apps add/remove files).
    private val observers = HashMap<String, android.os.FileObserver>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var refreshQueued = false

    /** Pane index: 0=left, 1=right. */
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
            textSize = Prefs.textSize(requireContext()),
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
                        node.connecting -> Unit // Unlocking in progress — don't show the password dialog again / don't stack another unlock.
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
            onSelectionChanged = { /* Reserved: selection count */ },
            onInfoTab = { node, idx -> viewModel.selectInfoTab(node, idx) },
            onInfoHash = { node -> viewModel.computeHash(node) },
        )
        // One GridLayoutManager handles both: ordinary rows take a full row, thumbnail grid cells take 1 column;
        // the column count adapts to the pane's actual width (dual-pane / portrait-vs-landscape widths differ).
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
        b.list.itemAnimator = null // The tree changes instantly on tap; item animations would make the highlight frame drift through the transition.
        if (Prefs.rowDivider(requireContext())) b.list.addItemDecoration(RowDivider(requireContext()))
        b.list.addItemDecoration(CurrentDirFrame(requireContext()))

        installGestures()
        // The map's swipes / touches share the same interaction as the tree list: horizontal swipe switches panes, touch reports the active pane.
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
            // Lock to a single source (picker mode): don't restore position — that would expand a different root.
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
            // recreate triggered by a settings change: the VM survives; just re-sort by the new settings (e.g. media-first).
            viewModel.resortAll()
        } else if (Prefs.rememberLocation(requireContext())) {
            val cur = Prefs.locationCurrent(requireContext(), paneIndex)
            pendingScrollToCurrent = cur != null // Scroll to the previous directory once restoration finishes.
            viewModel.bootstrap(Prefs.locationExpanded(requireContext(), paneIndex), cur)
        } else {
            viewModel.bootstrap()
        }
    }

    /** Grid column count ≈ pane width / 96dp; the cell edge length is fed back to the adapter so cells stay square.
     * The deduction is the 2dp padding on `item_thumb_cell` itself (one on each side = 4dp total), which is the measured
     * width of the thumbnail frame — earlier we deducted 8dp, which made the frame 4dp shorter than the width and
     * CENTER_CROP chopped a strip off the top and bottom of square app icons. */
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
     * Touch reports the active pane; horizontal drag switches panes — judged by the net horizontal
     * displacement at lift; slow / diagonal motions also work (low threshold, only requires horizontal
     * > vertical), without breaking the list's vertical scrolling and tap.
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
                        // While restoring (a network location might take seconds to connect) the moment the user touches the list,
                        // give control back — don't keep yanking the list to the previous position while they're scrolling / expanding.
                        pendingScrollToCurrent = false
                        downX = e.x; downY = e.y; swiped = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!swiped) {
                            val dx = e.x - downX
                            val dy = e.y - downY
                            if (abs(dx) > threshold && abs(dx) > abs(dy)) {
                                swiped = true
                                host?.onPaneSwipe(dx) // dx>0 swipe right → left pane; dx<0 swipe left → right pane.
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
        // The user may have just picked "Always" in the system resolver: clear the file-association icon cache and redraw so the icon follows.
        FileIcons.clearAppDefaults()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        // External apps may have changed local files: re-list already-expanded local directories and restore inotify watches.
        viewModel.refreshLocal()
        syncObservers(viewModel.state.value.rows)
    }

    /**
     * The active pane's path bar is highlighted, inactive ones dim. In dark theme just lowering alpha blends into the
     * pane background, so the two states swap background color instead ([R.color.path_bar_active] / [R.color.path_bar]).
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
            bb.list.invalidate() // The highlight frame redraws as currentKey changes.
            if (pendingScrollToCurrent) {
                // Restoration expands directories asynchronously, so the row count keeps shifting: re-anchor to the target row on every
                // version until restoring drops — only anchoring once stops at the half-built version's position.
                // The file row requested by "jump to containing directory" wins; if that's not (yet) there, fall back to the current directory.
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
        host?.onClipTargetChanged() // Paste target = currentDir, refresh that line on the bar to follow.
        s.error?.let { toast(it) }
        s.passwordFor?.let { askArchivePassword(it) }
        syncObservers(s.rows)
    }

    /** Keep the inotify watch set in sync with "expanded local directories". */
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

    /** Events fire on the observer thread; debounce 400ms before going back to the main thread to refresh local dirs. */
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
     * Path-bar text: local is the absolute path; everything else is `type:/path`; network sources insert the server
     * name after the type (custom label preferred, see [SavedConnection.shortLabel]), matching the "Recent" entries' style.
     */
    private fun pathLabel(f: XFile): String = when {
        f.scheme == "file" -> f.path
        f.scheme == "saf" -> f.name
        else -> {
            // This pane's reverse lookup wins; otherwise ask the global (copy / compress target directories belong to the
            // *other* pane — this pane hasn't expanded that server, so looking only at the VM would lose the server name; see [Connections.ofScheme]).
            val conn = viewModel.connOf(f.scheme) ?: Connections.ofScheme(f.scheme)
            val server = conn?.shortLabel().orEmpty()
            val path = if (f.path.startsWith("/")) f.path else "/${f.path}"
            val head = Format.schemeLabel(f.scheme)
            // Non-server sources (zip/git/restic…) have no server name; don't insert an extra slash.
            if (server.isEmpty()) "$head:$path" else "$head:/$server$path"
        }
    }

    /** For the host (share-target page) to display a path in the same format. */
    fun displayPath(f: XFile): String = pathLabel(f)

    /** Path-bar source-type icon (same set as Recent / copy / compress target rows; see [FileIcons.sourceIconRes]). */
    private fun pathIcon(f: XFile): Int = FileIcons.sourceIconRes(f.scheme)

    // ---- Open file ----

    private fun open(file: XFile) {
        if (host?.onPickFile(file) == true) return // Picker mode: tap selects, doesn't open.
        if (file.scheme.startsWith("git")) return openGitEntry(file)
        viewModel.noteOpenedIn(file) // Recent records the containing directory, not the file itself.
        when {
            // App entry: tapping launches the app (tapping "install yourself" is meaningless — the system would only say the same version is installed);
            // entries without a launch entry (most system apps) fall back to the app info page so a tap never does nothing.
            // App info / uninstall lives in the long-press menu.
            file.scheme == AppsFileSystem.SCHEME -> launchApp(file, fallbackToInfo = true)
            OpenFiles.canViewPdf(file) -> PdfViewerActivity.start(requireContext(), file)
            OpenFiles.isText(file) -> TextViewerActivity.start(requireContext(), file)
            OpenFiles.isImage(file) -> {
                val (images, index) = viewModel.imageSiblings(file)
                awaitingImageResult = true
                ImageViewerActivity.start(requireContext(), images, index)
            }
            // xapk/apks/apkm: base + splits in one zip, which the system installer cannot
            // take. Streamed into a PackageInstaller session instead, see ApkBundleInstall.
            OpenFiles.isApkBundle(file) -> ApkBundleInstall.start(requireContext(), file)
            OpenFiles.isAudio(file) -> openAudio(file)
            OpenFiles.isPlaylist(file) -> openM3u(file)
            OpenFiles.isVideo(file) ->
                MediaPlayerActivity.start(requireContext(), file)
            // No built-in viewer: pop the system resolver directly (with "Just once / Always"); if nothing can open it, fall back to the open-with dialog.
            else -> if (!OpenFiles.openWith(requireContext(), file)) chooseOpen(file)
        }
    }

    /**
     * Open audio: same-directory audio files join "Now playing" (ordered by pane), start from the tapped track, launch the background service + main UI.
     * Non-persistent sources (audio inside zip/restic/saf) fall back to the legacy MediaPlayerActivity for direct playback.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openAudio(file: XFile) {
        val self = viewModel.trackFrom(file)
        if (self == null) { MediaPlayerActivity.start(requireContext(), file); return }
        val (siblings, _) = viewModel.audioSiblings(file)
        val tracks = siblings.mapNotNull { viewModel.trackFrom(it) }
        val startIndex = tracks.indexOfFirst { it.id == self.id }.coerceAtLeast(0)
        val dirName = viewModel.parentLabel(file)
        val ctx = requireContext()
        val now = com.twig.app.PlaylistStore.setNow(ctx, dirName, tracks.ifEmpty { listOf(self) })
        MusicEngine.play(ctx, now, startIndex, autoPlay = true)
        startActivity(android.content.Intent(ctx, MusicPlayerActivity::class.java))
    }

    /**
     * Open m3u/m3u8 playlist: parse in the background (relative paths resolve against the m3u's directory and same source),
     * load its tracks into "Now playing" and play from the start. Only persistent sources (local / connected servers) join the queue; entries from other sources are skipped.
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

    /** git virtual entries: files inside a change/commit → two-column diff; everything else (commit info, etc.) → text viewer. */
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
        // preview = false: this entry means "show me the text", even for markdown/html,
        // which TextViewerActivity otherwise opens rendered.
        actions.item(getString(R.string.open_text), R.drawable.ic_file_doc) {
            TextViewerActivity.start(requireContext(), file, preview = false)
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
     * System share: via [com.twig.app.StreamProvider]'s streaming grant, files inside SMB / archives / S3 can be sent to other apps
     * **without being materialized to a temp file**. This is a different thing from the per-directory "WiFi sharing" item
     * (which is for letting other devices connect to this one).
     */
    private fun shareFile(file: XFile) {
        val r = runCatching { OpenFiles.share(requireContext(), file) }
        if (r.isFailure) toast(getString(R.string.open_no_app))
    }

    // ---- Actions (called by MainActivity's side action column) ----

    fun actionUp() {
        if (mapMode) { handleBack(); return }
        viewModel.up()
    }

    fun actionRefresh() = viewModel.refresh()

    /**
     * Expand in the tree and scroll to [target] (triggered by the other pane's "Show on the other side").
     * If [focus] is non-null, scroll to this file's row inside the directory ("Jump to containing directory").
     */
    fun reveal(target: XFile, focus: XFile? = null) {
        if (mapMode) exitTreemap()
        pendingScrollToCurrent = true
        viewModel.revealPath(target, focus)
    }

    /** Archive from external App "Open with Twig": mount at the top of the tree and expand (see [PaneViewModel.mountExternal]). */
    fun mountExternal(archive: XFile) {
        if (mapMode) exitTreemap()
        pendingScrollToCurrent = true
        viewModel.mountExternal(archive)
    }

    /**
     * Recent: directories where files have been opened / Git views entered (most recent first, up to
     * [HistoryStore.MAX] entries). Tap one to jump there. Long-press to delete a single entry.
     */
    fun actionHistory() {
        val ctx = requireContext()
        val list = HistoryStore.all(ctx)
        if (list.isEmpty()) { toast(getString(R.string.history_empty)); return }
        // Look up connections in one pass: entries only store the label, but display wants the type / custom name — both come from here.
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
        // The inner ListView has no long-click callback entry point; once we have it, hook one ourselves: long-press deletes that entry and reopens the dialog.
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
            pendingScrollToCurrent = false // Didn't jump — don't leave the pending-scroll state messing up subsequent actions.
            toast(getString(R.string.history_conn_missing))
        }
    }

    /**
     * History entry display name: network locations are `type:/server/path` (e.g. `smb:/pi/docs/photos`, same format as the path bar),
     * local is the absolute path; git items get a "Git" prefix too. [conn] == null means the connection has been deleted —
     * fall back to the label frozen in the entry, which is at least still recognizable (same as the Favorites row).
     */
    private fun historyLabel(e: HistoryEntry, conn: SavedConnection?): String {
        val head = formatLocationPath(e.connLabel, e.path, conn)
        return if (e.kind == "git") getString(R.string.history_git_prefix, head) else head
    }

    /**
     * Source-type icon, same set as the path bar / copy-target row ([FileIcons.sourceIconRes]); git items use the git icon.
     * Entries only store the connection label (`smb://host`), not the scheme; if the connection has been deleted, fall back to the type from the label's head.
     */
    private fun historyIcon(e: HistoryEntry, conn: SavedConnection?): Int = when {
        e.kind == "git" -> R.drawable.ic_git
        e.connLabel.isEmpty() -> FileIcons.sourceIconOfType("file")
        else -> FileIcons.sourceIconOfType(conn?.type ?: e.connLabel.substringBefore("://"))
    }

    // ---- Space-usage treemap (replaces the tree view in place) ----

    private var mapMode = false
    private var mapJob: kotlinx.coroutines.Job? = null
    private var mapScanner: TreemapScanner? = null
    private val mapStack = ArrayList<TreemapEntry>()
    /** Multi-selection inside the treemap (toggled via the menu's "Select"); side-bar copy/move/delete/rename prefer it. */
    private val mapSelected = LinkedHashSet<TreemapEntry>()

    /** Action bar "Treemap" toggle: for the green-highlighted directory / archive (current directory when nothing is highlighted). */
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
        render(viewModel.state.value) // Restore path bar / empty state.
    }

    /** Back-key routing: inside the treemap, go up a level first, then exit treemap at the root; outside the treemap, don't consume it. */
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

    /** Tile menu = Select + tree's directory/file long-press menu (shared) + Delete. */
    private fun treemapMenu(e: TreemapEntry) {
        val actions = ArrayList<MenuAct>()
        actions.item(getString(R.string.action_select), R.drawable.ic_sel_check) { toggleMapSelection(e) }
        actions += commonFileActions(e.file, includeDelete = false)
        // The tile menu's own Delete (with the size confirmation dialog) also has to check whether the source is mutable — the common
        // delete in commonFileActions has already checked it; this one is a separate path, missing the check leaves a delete entry on read-only sources.
        if (e.file.isMutable()) {
            actions.item(getString(R.string.strip_delete), R.drawable.ic_delete, DANGER) { confirmDeleteEntry(e) }
        }
        showActionMenu(requireContext(), e.name, actions)
    }

    private fun confirmDeleteEntry(e: TreemapEntry) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.treemap_confirm_delete, e.name, Format.size(e.size)))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                runIo({ FsRegistry.of(e.file).delete(e.file) }) {
                    // Pull it out of the map and decrement size upward; refresh in place; the tree side re-lists as usual.
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

    // ---- File search (recursive glob, results attached as a virtual directory on the tree) ----

    /** Last-entered glob; pre-filled in the dialog so consecutive searches don't re-type. */
    private var lastSearchPattern = ""

    /** Action bar "Search": recursive search starting at the green-highlighted directory (current directory when nothing is highlighted). */
    fun actionSearch() {
        val t = viewModel.currentSelection() ?: viewModel.currentDir
        if (t == null || !t.isDir) {
            toast(getString(R.string.msg_pick_dir_first)); return
        }
        promptSearch(t)
    }

    /** Dialog inputs the glob (e.g. `*.jpg`; without wildcards it degrades to a substring search); on confirm, recursive search starts at [dir]. */
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

    /**
     * "Go to path": type a path under this root and jump straight there — every level
     * on the way is expanded, a trailing file scrolls the list to its row. Accepts a
     * pasted absolute path too (see [PaneViewModel.revealUnder]).
     *
     * [root] is null only for a server that has not been connected yet — the jump
     * connects it on the way.
     */
    private fun promptGoto(rootKey: String, root: XFile?) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.goto_hint)
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_goto_path)
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val path = input.text.toString().trim()
                if (path.isEmpty()) return@setPositiveButton
                if (mapMode) exitTreemap()
                pendingScrollToCurrent = true
                viewModel.revealUnder(rootKey, root, path)
            }
            .show()
    }

    /** Search virtual directory's long-press menu: show on other side (the directory being searched) + Properties (stats; only shows on tap, no longer pops on long-press). */
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

    /** Stats dialog: matching files / directories are counted separately; an extra note is appended while the scan is still running. */
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

    /** Create a folder directly inside [dir], independent of currentDir — used by the directory long-press menu; works even if the directory hasn't been expanded or selected. */
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
     * Create an empty text file inside [dir], then jump straight into editing — creating an empty file is itself useless,
     * what the user wants is to start writing immediately. The default name's extension is not auto-completed: .md/.sh/.json are all common,
     * and pre-selecting just the stem part lets the user replace it directly.
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
                    // For most implementations createFile is just path concatenation, but SAF really does create a document,
                    // so call it once and use the resulting target all the way down.
                    val target = fs.createFile(dir, name)
                    if (fs.exists(target)) throw FsException(getString(R.string.new_text_exists, name))
                    fs.openOutput(target).use { } // Create 0-byte file.
                    created = target
                }) {
                    viewModel.invalidate(dir); viewModel.refresh()
                    created?.let { TextViewerActivity.start(requireContext(), it, edit = true) }
                }
            }
            .show()
    }

    /** Currently-checked files (for picker hosts to grab multi-selection results); empty when nothing is checked. */
    fun checkedFiles(): List<XFile> = if (::adapter.isInitialized) adapter.selectedItems() else emptyList()

    /**
     * Whether this side's view has been built yet.
     *
     * ★ The host ([MainActivity]) will come asking this side for state when the *other* pane refreshes
     * (`render → onClipTargetChanged → syncStripEnabled`), and the two panes' `onViewCreated` runs **one at a time** —
     * when the first one built emits its first frame, the other's [adapter] hasn't been assigned yet. The
     * old setup didn't hit this because the panes were committed in `Activity.onCreate`, so by the time there was
     * state to render both sides were ready; the master-password unlock dialog pushes initialization past RESUMED,
     * and that's when this race surfaces (symptom: crash the moment you finish typing the master password, `lateinit property adapter`).
     */
    fun isReady(): Boolean = view != null && ::adapter.isInitialized

    /** Checked items (in treemap mode, prefer the selected tiles on the map); when nothing is checked, fall back to the green-highlighted current node. */
    private fun selectionOrCurrent(): List<XFile> = when {
        mapMode && mapSelected.isNotEmpty() -> mapSelected.map { it.file }
        // When the view isn't built yet we can only ask the VM — the check state lives in the adapter, and there can't be any checks at that point.
        !::adapter.isInitialized -> listOfNotNull(viewModel.currentSelection())
        else -> adapter.selectedItems().ifEmpty { listOfNotNull(viewModel.currentSelection()) }
    }

    /**
     * Whether the action bar's **write actions** should be tappable — on read-only sources (media servers, restic, 7z/RAR,
     * git view, "Apps") they're all greyed out, instead of letting a tap hit them and pop up an error.
     *
     * Two questions, matching [com.twig.core.isMutable] / [isWritableDir] one-to-one:
     * - [canModify]: whether the rename/move/delete **sources** are all mutable
     * - [canCreateHere]: whether the **target directory** for new folder / new text file / paste is writable
     *
     * Copy / compress / share are pure read-source operations, never disabled on any source.
     */
    fun canModify(): Boolean = selectionOrCurrent().let { it.isNotEmpty() && it.all { f -> f.isMutable() } }

    fun canCreateHere(): Boolean = viewModel.currentDir?.isWritableDir() == true

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
     * Delete. For app entries, "delete" means **uninstall** and is delegated to the system uninstall UI (which has its own confirmation, so we don't layer ours on top); for a mixed selection across sources, apps go to uninstall and the rest are deleted as usual.
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

    // ---- App management (scheme=apps) ----

    /** Pending uninstall package-name queue: the system uninstall UI only accepts one at a time, batches must line up (see [uninstallNext]). */
    private val uninstallQueue = ArrayDeque<String>()

    /** Returned from uninstall (whether completed or user-cancelled): if the queue still has items, start the next one; only when it's empty do we refresh the tree. */
    private val uninstallLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            if (!uninstallNext()) {
                adapter.clearSelection(); clearMapSelection(); viewModel.refresh()
            }
        }

    private fun appsFs(): AppsFileSystem? =
        runCatching { FsRegistry.of(AppsFileSystem.SCHEME) }.getOrNull() as? AppsFileSystem

    /**
     * Launch the system uninstall UI (one per app; the system itself shows the confirmation). System apps can't be uninstalled — we only
     * expose the uninstall entry in the "Installed" category, and this catch-all here is a safety net so paths like the treemap / multi-select don't sneak one through.
     *
     * Multi-select **must queue**: launching several uninstall intents in a row, the system only displays the last one and silently drops the earlier ones.
     * After queueing, [uninstallNext] launches them one at a time — only the previous one returning triggers the next.
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
     * Launch the system uninstall UI for the head of the queue; returns false when the queue is empty (caller finishes up by refreshing the tree).
     * ★ This intent requires the manifest to declare `REQUEST_DELETE_PACKAGES` (Android 8+); otherwise the system uninstall UI just calls finish,
     * which looks like "tap does nothing".
     */
    private fun uninstallNext(): Boolean {
        while (true) {
            val pkg = uninstallQueue.firstOrNull() ?: return false
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, android.net.Uri.parse("package:$pkg"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            val ok = runCatching { uninstallLauncher.launch(intent) }
                .onFailure { toast(it.message ?: "") }
                .isSuccess
            // If launching fails (device has no uninstall UI, etc.) just skip it and continue — don't deadlock the whole queue.
            uninstallQueue.removeFirst()
            if (ok) return true
        }
    }

    /** The system Settings "App info" page (permissions / storage / disable; system apps' "Uninstall updates" lives there too). */
    private fun openAppInfo(file: XFile) {
        val pkg = appsFs()?.packageOf(file) ?: return
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$pkg"),
        )
        runCatching { startActivity(intent) }.onFailure { toast(it.message ?: "") }
    }

    /** [fallbackToInfo]: when there's no launch entry, open the app info page instead (a tap walks this path; the menu item only hints at it). */
    private fun launchApp(file: XFile, fallbackToInfo: Boolean = false) {
        val pkg = appsFs()?.packageOf(file) ?: return
        val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            if (fallbackToInfo) openAppInfo(file) else toast(getString(R.string.apps_launch_failed))
            return
        }
        runCatching { startActivity(intent) }.onFailure { toast(it.message ?: "") }
    }

    /** Whether the app entry is in the "System" category (path shape: apps:/system/<package>). */
    private fun isSystemApp(file: XFile): Boolean =
        file.scheme == AppsFileSystem.SCHEME && file.path.startsWith("/system/")

    // ---- Clipboard (cross-pane stash + paste) ----
    // The bar itself is in MainActivity (spanning the whole window); here we only provide the "target directory" and the actual move.

    /** Replace-mode put into the clipboard (no append); the bar refreshes itself via [FileClipboard]'s flow. */
    private fun addToClipboard(files: List<XFile>) {
        if (files.isEmpty()) return
        FileClipboard.put(files)
        toast(getString(R.string.msg_clip_added, files.size))
    }

    /**
     * This pane's paste target = the green-highlighted current directory. In treemap mode the green frame is invisible,
     * so return null and let the bar prompt to pick a directory — otherwise we'd paste to somewhere the user can't see right now.
     */
    fun clipTarget(): XFile? = if (mapMode) null else viewModel.currentDir

    /** Paste-target display text (the line on the bar); null when there is no target. */
    fun clipTargetLabel(): String? = clipTarget()?.let { pathLabel(it) }

    /**
     * Paste into this pane's current directory: **no confirmation dialog** — go straight to the move. The target and copy/move mode are both written on the clipboard bar
     * and visible before pressing paste, so confirming again would be pure overhead. The progress dialog and same-name-conflict handling are unchanged.
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
        // The bar's button is already greyed out for this reason; this is the safety net: if the green frame just moved and the bar hasn't redrawn yet, the tap shouldn't take effect either.
        FileClipboard.pasteBlockReason(dest)?.let { toast(getString(it)); return }
        // "move" was already determined when the clipboard was filled (see FileClipboard.put), so just reuse it.
        startTransfer(items, dest, FileClipboard.move, plan0 = null, fromClipboard = true)
    }

    /** Copy/move the checked items (or the green-highlighted current node when nothing is checked) to the other pane's current directory. */
    fun actionCopy(move: Boolean) {
        val sel = selectionOrCurrent()
        if (sel.isEmpty()) {
            toast(getString(R.string.msg_no_selection)); return
        }
        performCopy(sel, move)
    }

    private fun performCopy(sel: List<XFile>, move: Boolean) {
        // Move = copy + delete source. Apps can't be deleted (uninstall is a different thing), and the document tree root
        // can't be deleted (it's a grant, see [isMovableSource]); the action bar's "Move" and batch-after-check bypass the menu,
        // so we catch the failure here rather than failing after the copy completes.
        val movable = sel.all { it.isMovableSource() }
        if (move && !movable) {
            toast(
                getString(
                    if (sel.any { it.scheme == AppsFileSystem.SCHEME }) R.string.apps_read_only
                    else R.string.saf_root_no_move,
                ),
            )
            return
        }
        val sibling = host?.siblingOf(this)
        val dest = sibling?.viewModel?.currentDir
        if (dest == null) {
            toast(getString(R.string.msg_pick_dir_first)); return
        }
        if (!dest.isWritableDir()) {
            toast(getString(R.string.msg_dest_not_writable)); return
        }

        // ---- Confirmation dialog: source + size (background tally) + destination + move mode ----
        val cb = DialogCopyConfirmBinding.inflate(layoutInflater)
        cb.tvSrc.text = if (sel.size == 1) sel[0].name else getString(R.string.copy_items, sel.size)
        cb.tvSize.text = "…"
        cb.tvDest.text = pathLabel(dest)
        cb.ivDest.setImageResource(pathIcon(dest))
        cb.cbMove.isChecked = move
        // When the sources can't be moved there's no "move mode" to speak of — better to hide the row entirely than show a checkbox that would error on click.
        cb.cbMove.visibility = if (movable) View.VISIBLE else View.GONE
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
                startTransfer(sel, dest, movable && cb.cbMove.isChecked, plan)
            }
            .show()
    }

    /** Hand the move to the background session, and attach the progress dialog (see [Transfers]). */
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

    /** Start the session + pop the progress dialog; if a transfer is already running, don't steal the session (it's a singleton, see [Transfers.start]). */
    private fun launchSession(session: Transfers.Session) {
        val started = runCatching { Transfers.start(requireContext().applicationContext, session) }
            .getOrElse { toast(it.message ?: getString(R.string.err_failed)); return }
        if (!started) { toast(getString(R.string.transfer_busy)); return }
        showTransferBox()
    }

    /**
     * Attach the progress dialog to the running session (starting a new task, or returning from the notification).
     * If the session is gone / the dialog is already up, do nothing.
     */
    fun showTransferBox() {
        // `active` is cleared by the transfer thread (right at completion); copy it locally before checking, don't crash on `!!`.
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
     * Transfer finished: clear selection, refresh both panes, surface the result. The session can finish while the UI is away;
     * [MainActivity] calls back into here after returning to the foreground to cover that case (so it can't live only in the progress dialog).
     */
    fun finishTransfer(s: Transfers.Session) {
        adapter.clearSelection()
        clearMapSelection()
        // Once paste finishes, we're done: moved sources no longer exist, and after a copy this round is also over (re-paste = re-stash).
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

    /** Android 13+ requires runtime permission for notifications; only ask the moment we go to background — before that the notification is just a side effect. */
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

    // ---- Compress (pack into the other pane's current directory) ----

    /** Action bar "Compress": pack the checked items (or the green-highlighted current node when nothing is checked) into the other pane's current directory. */
    fun actionCompress() {
        val sel = selectionOrCurrent()
        if (sel.isEmpty()) {
            toast(getString(R.string.msg_no_selection)); return
        }
        performCompress(sel)
    }

    /**
     * Compress confirmation dialog: file name (default below) + format (zip/7z) + move mode; the destination is fixed to the other pane's current directory,
     * same as the action bar's copy/move.
     *
     * Default name: with a single item (the case when the file/directory menu enters) use that item's name — files strip the extension;
     * for multiple items use their containing directory's name. The extension follows the format and is rewritten whenever the format changes.
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
            cb.etName.setSelection(stem.length) // setText sends the cursor back to the start; move it back to the end of the name to keep typing.
        }
        // "Encrypted archives are read-only" — only mention it when actually encrypting; don't take up space otherwise.
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
        // Rename as soon as the dialog opens: keyboard pops up directly (ALWAYS_ ignores the "user manually hid the keyboard last time" state, must be set before show),
        // focus lands on the name field and **selects only the stem, not the extension** — typing replaces the name straight away while .zip/.7z is preserved.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        cb.etName.requestFocus()
        cb.etName.setSelection(0, stripArchiveExt(cb.etName.text.toString()).length)
    }

    private fun formatOf(checkedId: Int): ArchiveWriter.Format =
        if (checkedId == R.id.rb_7z) ArchiveWriter.Format.SEVEN_Z else ArchiveWriter.Format.ZIP

    /** Strip any existing archive extension (used when switching format); other extensions are preserved. */
    private fun stripArchiveExt(name: String): String {
        val lower = name.lowercase()
        for (f in ArchiveWriter.Format.entries) {
            if (lower.endsWith(".${f.ext}")) return name.dropLast(f.ext.length + 1)
        }
        return name
    }

    /** Single item → that item's name (file strips extension); multiple items → containing directory name. */
    private fun defaultArchiveName(sel: List<XFile>): String {
        if (sel.size == 1) {
            val f = sel[0]
            val n = f.name
            return if (f.isDir) n else n.substringBeforeLast('.', n).ifEmpty { n }
        }
        val parent = sel[0].parentPath.trimEnd('/').substringAfterLast('/')
        return parent.ifEmpty { viewModel.currentDir?.name?.trimEnd('/') ?: "" }.ifEmpty { "archive" }
    }

    /** If the destination name already exists, ask about overwriting first, then go to [runCompress]. */
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

    /** Packing also goes through the background session (copy / compress share one progress dialog and one foreground service). */
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
     * Password dialog for encrypted archives. Triggered by [PaneViewModel.State.passwordFor] when expanding an encrypted archive,
     * interaction mirrors the restic unlock (optional save). Wrong password just prompts again in place — no need to re-open the archive.
     *
     * Only one dialog for the same archive: state is a StateFlow, every render during the dialog's lifetime sees the same passwordFor again.
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
                    askArchivePassword(archive) // Ask again in place; no need to re-open this archive.
                }
            }
            .setOnDismissListener { pwDialogFor = null }
            .show()
    }

    /** Unlock a restic repository: if there's a saved password, use it directly; otherwise show a password dialog (with optional save). */
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

    // ---- Long-press context menu ----

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
        if (node.label != null) { rootNodeMenu(node); return } // Top-level storage nodes take the "non-mutable directory" path.
        if (SafFileSystem.isTreeRoot(node.file)) { safRootMenu(node); return }
        // Long-press hit one of the already-checked multi-selection items (and not just itself) → batch-specific menu;
        // otherwise (not checked, or only it is selected) treat as a normal single-file menu.
        val selItems = adapter.selectedItems()
        if (adapter.isSelected(node) && selItems.size > 1) {
            showBatchMenu(selItems)
            return
        }
        // Tree-node-only items (Select / Properties / Refresh / Open as archive) + the generic items shared with the treemap.
        val actions = ArrayList<MenuAct>()
        actions.item(getString(R.string.action_select), R.drawable.ic_sel_check) { adapter.toggleSelection(node) }
        actions.item(getString(R.string.file_info), R.drawable.ic_info) { viewModel.toggleInfo(node) }
        if (node.expandable) {
            actions.item(getString(R.string.action_refresh), R.drawable.ic_refresh) { viewModel.refreshNode(node) }
        }
        if (!node.file.isDir && OpenFiles.isInstallable(node.file)) {
            actions.item(getString(R.string.open_as_archive), R.drawable.ic_file_archive) {
                viewModel.openAsArchive(node)
            }
        }
        if (node.file.isDir) {
            // Operate directly on this directory, independent of currentDir — works even if not yet expanded / not yet selected (so we can create / run treemap analysis here).
            // ★ Both "New" entries have to check writability: on read-only sources (media server / restic / archive / git view …) we currently
            // still expose the entry and a tap only errors out. Previously only "New text file" had the check.
            if (node.file.isWritableDir()) {
                actions.item(getString(R.string.action_new_folder), R.drawable.ic_new_folder) {
                    performNewFolder(node.file)
                }
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
     * Multi-selection (> 1 checked) exclusive menu: keep only operations that make sense in bulk (refresh thumbnails / copy / move / delete);
     * single-file-only items like Select / Properties / Open as archive / Open with / Rename / Favorite are meaningless in batch and hidden.
     * The title uses the check count instead of a file name.
     */
    private fun showBatchMenu(targets: List<XFile>) {
        val actions = ArrayList<MenuAct>()
        if (targets.any { it.isDir || Thumbs.canThumb(it) }) {
            actions.item(getString(R.string.action_refresh_thumb), R.drawable.ic_file_image) {
                refreshThumb(targets)
            }
        }
        actions.item(getString(R.string.action_clip_add), R.drawable.ic_clipboard) { addToClipboard(targets) }
        // Same as commonFileActions: copy / compress are pure read-source and always allowed; move / delete require *every* source to be mutable.
        val mutable = targets.all { it.isMutable() }
        actions.item(getString(R.string.strip_copy), R.drawable.ic_copy) { performCopy(targets, move = false) }
        if (targets.all { it.isMovableSource() }) {
            actions.item(getString(R.string.strip_move), R.drawable.ic_move) { performCopy(targets, move = true) }
        }
        actions.item(getString(R.string.strip_compress), R.drawable.ic_compress) { performCompress(targets) }
        if (mutable) {
            actions.item(getString(R.string.strip_delete), R.drawable.ic_delete, DANGER) { performDelete(targets) }
        }
        showActionMenu(requireContext(), getString(R.string.title_selected_count, targets.size), actions)
    }

    /**
     * Menu items shared between the tree and the treemap: directory (show on other side / slideshow / Git / terminal / desktop shortcut),
     * file (open), favorite + the action bar's copy/move/rename (+ delete; the treemap tile menu already has its own dedicated delete,
     * so [includeDelete] turns off the generic one here to avoid duplication). Always operates on a single [file] — batch operations
     * go through [showBatchMenu], this is not reused.
     * [includeEdit] turns off the whole copy/move/rename/delete group ([includeCopy] can bring just "copy" back,
     * used by the document tree root), [includeFavorite] turns off "add to favorite" — the favorite row's own menu uses
     * this ([favoriteMenu]: rename/delete target the real directory the favorite points to, which is semantically too easy
     * to confuse with "unfavorite"; and it already is a favorite anyway). [includeShell] turns off the terminal/command shortcut
     * entries for SFTP directories — those entries are given by the server row itself and land in $HOME rather than the root path, see [serverMenu].
     */
    private fun commonFileActions(
        file: XFile,
        includeDelete: Boolean = true,
        includeEdit: Boolean = true,
        includeFavorite: Boolean = true,
        includeShell: Boolean = true,
        includeCopy: Boolean = includeEdit,
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
            // Local directory: open a local shell at this working directory (system-provided mksh + toybox).
            if (includeShell && file.scheme == "file") {
                actions.item(getString(R.string.terminal_here), R.drawable.ic_terminal) {
                    TerminalActivity.startLocal(requireContext(), file.path)
                }
                // When privileged access is on, **add a separate entry** instead of changing the existing one's behavior — silently
                // swapping a plain shell for root lets the user think they're trying commands under the app's uid, when a single
                // `rm` would be wiping the whole disk. The identity is in the menu text, so the user knows where they're going before tapping.
                privTerminalLabel()?.let { (label, mode) ->
                    actions.item(label, R.drawable.ic_terminal) {
                        TerminalActivity.startLocal(requireContext(), file.path, mode)
                    }
                }
            }
            // SFTP directory: open a terminal with this working directory.
            if (includeShell &&
                runCatching { FsRegistry.of(file) }.getOrNull() is com.twig.fs.network.SftpFileSystem
            ) {
                // The shell needs the server's own path, which differs from the one on
                // screen when the connection is rooted at a sub-directory (Connections.shellPath).
                val shellDir = Connections.shellPath(file.scheme, file.path)
                actions.item(getString(R.string.terminal_here), R.drawable.ic_terminal) {
                    TerminalActivity.start(requireContext(), file.scheme, file.name, shellDir)
                }
                viewModel.connOf(file.scheme)?.let { conn ->
                    actions.item(getString(R.string.cmd_shortcut), R.drawable.ic_play) {
                        showCommandDialog(conn, file.scheme, shellDir, getString(R.string.cmd_title_dir, file.name))
                    }
                }
            }
            actions.item(getString(R.string.action_pin_shortcut), R.drawable.ic_shortcut) { pinFileShortcut(file) }
            // WiFi sharing this directory: scope is passed straight through, no need to pick it in the main menu.
            actions.item(getString(R.string.share_dir_menu), R.drawable.ic_share_wifi) {
                (activity as? androidx.appcompat.app.AppCompatActivity)
                    ?.let { ShareDialogs.show(it, file) }
            }
        } else if (file.scheme == AppsFileSystem.SCHEME) {
            // App entries: open/preview makes no sense (a tap would just reinstall itself), so swap in the app's own three actions.
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
            actions.item(getString(R.string.action_pin_shortcut), R.drawable.ic_shortcut) { pinFileOpenShortcut(file) }
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
        // Just stash, don't touch the source — the favorite row set (includeEdit=false) is included too, so a favorite directory can be pasted elsewhere too.
        actions.item(getString(R.string.action_clip_add), R.drawable.ic_clipboard) { addToClipboard(listOf(file)) }
        // ★ Copy / pack are **pure read-source** and always allowed; move (the "delete source" step) / rename / delete require the source
        // to be mutable — media servers, restic, 7z/RAR, git view, "Apps" all have writable() == false. (App entries'
        // "delete" semantics is uninstall, already covered by the "Uninstall" entry above.)
        // Move has another rule: the document tree root can't be deleted (see [isMovableSource]), but copy is still allowed.
        if (includeCopy) {
            actions.item(getString(R.string.strip_copy), R.drawable.ic_copy) {
                performCopy(listOf(file), move = false)
            }
        }
        if (includeEdit) {
            val mutable = file.isMutable()
            if (file.isMovableSource()) {
                actions.item(getString(R.string.strip_move), R.drawable.ic_move) {
                    performCopy(listOf(file), move = true)
                }
            }
            actions.item(getString(R.string.strip_compress), R.drawable.ic_compress) {
                performCompress(listOf(file))
            }
            if (mutable) {
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

    /** Force-regenerate thumbnails (batchable): clear each item's in-memory / disk cache and failure marker (directories also recurse into all sub-items),
     * then rebind the whole screen so the list fetches fresh images. Directory traversal can hit network I/O,
     * so it runs asynchronously and refreshes the list once everything completes.
     *
     * ★ **The selected directories themselves need clearing too**: media-server shows/seasons/albums/photo-albums/collections/libraries are all
     * directories, and their covers hang on the directory row. First clear them synchronously (purely local cache removal, no network),
     * then refresh the list immediately so that row's cover swaps right away — otherwise the big library recursion takes dozens of seconds and
     * it looks like "tapping did nothing" in the meantime. */
    private fun refreshThumb(targets: List<XFile>) {
        val ctx = requireContext()
        val dirs = targets.filter { it.isDir }
        targets.forEach { Thumbs.invalidate(ctx, it) }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        if (dirs.isEmpty()) return
        toast(getString(R.string.msg_thumbs_refreshing))
        var remaining = dirs.size
        dirs.forEach { dir ->
            Thumbs.invalidateDir(ctx, dir) {
                if (--remaining == 0 && isAdded && ::adapter.isInitialized) adapter.notifyDataSetChanged()
            }
        }
    }

    /** Standalone home-screen shortcut: directory → expand to itself (and its contents); file → expand parent and scroll to that file's row. */
    private fun pinFileShortcut(file: XFile) {
        val ctx = requireContext()
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            toast(getString(R.string.pin_shortcut_unsupported)); return
        }
        val revealPath = if (file.isDir) file.path else file.parentPath
        val focus = if (file.isDir) null else file.path
        val intent = MainActivity.revealIntent(ctx, file.scheme, revealPath, focus)
            .setAction(Intent.ACTION_VIEW)
        // id is computed from the file itself: two files under the same directory now land on their own rows; sharing the parent id would overwrite each other.
        val id = "shortcut_" + (file.scheme + ":" + file.path).hashCode()
        val iconRes = if (file.isDir) R.drawable.ic_folder else FileIcons.baseIconRes(file)
        val shortcut = ShortcutInfoCompat.Builder(ctx, id)
            .setShortLabel(file.name.ifEmpty { revealPath })
            .setIcon(ShortcutIcons.of(ctx, iconRes))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(ctx, shortcut, null)
    }

    /**
     * Desktop shortcut for a single file: unlike [pinFileShortcut] (which just reveals a
     * directory in the tree), tapping this one opens the file directly — so first ask how:
     * automatic dispatch (the default), as text, as hex, or with one specific app chosen
     * right now ([pickAppForShortcut] — not the in-app "Open with" menu's resolver, which
     * can re-ask on every tap; a shortcut can't do that, so the choice is made once here).
     */
    private fun pinFileOpenShortcut(file: XFile) {
        val ctx = requireContext()
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            toast(getString(R.string.pin_shortcut_unsupported)); return
        }
        val actions = ArrayList<MenuAct>()
        actions.item(getString(R.string.shortcut_open_auto), R.drawable.ic_shortcut) {
            pinFileShortcutWithMode(file, OpenShortcutActivity.MODE_AUTO)
        }
        actions.item(getString(R.string.open_text), R.drawable.ic_file_doc) {
            pinFileShortcutWithMode(file, OpenShortcutActivity.MODE_TEXT)
        }
        actions.item(getString(R.string.open_hex), R.drawable.ic_file) {
            pinFileShortcutWithMode(file, OpenShortcutActivity.MODE_HEX)
        }
        actions.item(getString(R.string.open_with_app), R.drawable.ic_open_with) {
            pickAppForShortcut(file)
        }
        showActionMenu(ctx, getString(R.string.shortcut_open_mode_title), actions)
    }

    /**
     * "With this app" mode: a pinned shortcut has no chance to show the system resolver's
     * "just once / always" dialog on every tap the way the in-app "Open with" menu does —
     * the app has to be picked once, now, and baked into the shortcut ([OpenFiles.resolveViewers]
     * / [OpenFiles.openWithComponent]). Skips straight to pinning when there's only one candidate.
     */
    private fun pickAppForShortcut(file: XFile) {
        val ctx = requireContext()
        val candidates = OpenFiles.resolveViewers(ctx, file)
        if (candidates.isEmpty()) { toast(getString(R.string.open_no_app)); return }
        if (candidates.size == 1) {
            pinFileShortcutWithMode(file, OpenShortcutActivity.MODE_EXTERNAL, candidates[0].componentOf())
            return
        }
        val pm = ctx.packageManager
        val adapter = object : ArrayAdapter<ResolveInfo>(
            ctx, R.layout.item_menu_action, R.id.label, candidates,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val ri = getItem(position) ?: return v
                v.findViewById<TextView>(R.id.label).text = ri.loadLabel(pm)
                v.findViewById<ImageView>(R.id.icon).apply {
                    imageTintList = null // real app icons, don't tint them like our own vectors
                    setImageDrawable(ri.loadIcon(pm))
                }
                return v
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.open_with_app))
            .setAdapter(adapter) { _, w -> pinFileShortcutWithMode(file, OpenShortcutActivity.MODE_EXTERNAL, candidates[w].componentOf()) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun ResolveInfo.componentOf() = ComponentName(activityInfo.packageName, activityInfo.name)

    /** Icon: the row's thumbnail if thumbnails are on and one is already cached (no fresh
     * generation triggered — see [Thumbs.cached]), otherwise the same type icon the row itself
     * falls back to. */
    private fun pinFileShortcutWithMode(file: XFile, mode: String, component: ComponentName? = null) {
        val ctx = requireContext()
        val id = "shortcut_" + (file.scheme + ":" + file.path).hashCode()
        val thumb = if (Prefs.thumbs(ctx) && Thumbs.canThumb(file)) Thumbs.cached(file) else null
        val icon = if (thumb != null) ShortcutIcons.of(ctx, thumb) else ShortcutIcons.of(ctx, FileIcons.baseIconRes(file))
        val shortcut = ShortcutInfoCompat.Builder(ctx, id)
            .setShortLabel(file.name.ifEmpty { file.path })
            .setIcon(icon)
            .setIntent(OpenShortcutActivity.intent(ctx, file, mode, component))
            .build()
        ShortcutManagerCompat.requestPinShortcut(ctx, shortcut, null)
    }

    /** Show on other side: expand level by level in the sibling pane to locate and focus this directory. */
    private fun revealInSibling(dir: XFile) {
        val sib = host?.siblingOf(this) ?: return
        sib.reveal(dir)
        host?.focusPane(sib)
    }

    /** Slideshow: the viewer itself recursively scans this directory (including subdirectories) for images and plays as it scans — see
     * [ImageViewerActivity.startSlideshow]; we don't pre-collect the whole tree here, to avoid stalling on large directories / deep network paths before the first image shows. */
    private fun startSlideshow(dir: XFile) {
        awaitingImageResult = true
        ImageViewerActivity.startSlideshow(requireContext(), dir)
    }

    /** This pane just launched an image viewer; pick up its result on return (both panes hit onResume, don't steal the other pane's). */
    private var awaitingImageResult = false

    /**
     * Pick up the image viewer's result: the checked images there sync to multi-selection on the tree; if any were deleted, refresh. The viewer's
     * selection can cross directories (slideshow scans recursively), we don't filter it here — multi-selection on the tree already allows cross-directory (see mapSelected).
     */
    private fun takeImageViewerResult() {
        if (!awaitingImageResult || !::adapter.isInitialized) return
        awaitingImageResult = false
        val r = ImageViewerActivity.takeResult() ?: return
        if (r.selection.isNotEmpty()) adapter.setSelection(r.selection)
        if (r.changed) viewModel.refresh()
    }

    /**
     * Shared menu body for "directory rows that shouldn't be renamed/deleted" (favorites / server roots / top-level storage nodes / document tree roots):
     * select all children / properties / refresh / new / treemap / search + [commonFileActions] (minus copy/move/rename/delete —
     * acting on the real directory the favorite points to, the server root, or the storage root is either semantically confusing or pointless).
     * [includeCopy] brings "copy" back alone: copy is **read-source**, copying an entire document tree is a real need,
     * but move/rename/delete act on the real directory the grant points to (see [safRootMenu]).
     * "Select" doesn't pick this row itself (it isn't a copy/delete target); instead it's a two-state toggle: select-all / deselect-all direct children,
     * same as the search-result row.
     */
    private fun dirRowActions(
        node: PaneViewModel.Node,
        target: XFile,
        refresh: () -> Unit,
        includeFavorite: Boolean = true,
        includeShell: Boolean = true,
        includeCopy: Boolean = false,
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
        if (supportsGoto(target, viewModel.connOf(target.scheme))) {
            actions.item(getString(R.string.action_goto_path), R.drawable.ic_goto) {
                promptGoto(node.key, target)
            }
        }
        actions += commonFileActions(
            target,
            includeEdit = false,
            includeFavorite = includeFavorite,
            includeShell = includeShell,
            includeCopy = includeCopy,
        )
        return actions
    }

    /**
     * Long-press menu for top-level storage nodes (internal storage / root / Apps / locked root): uses the [dirRowActions] set.
     * Previously this just `return`-ed with zero entries — but properties/search/treemap/new apply just as well to storage roots.
     */
    private fun rootNodeMenu(node: PaneViewModel.FileNode) {
        val actions = dirRowActions(node, node.file, refresh = { viewModel.refreshNode(node) })
        // Removable volume: some ROMs don't let ordinary APIs read USB drives (MANAGE_EXTERNAL_STORAGE doesn't help either),
        // then SAF is the only path — the system picker jumps straight to this card / this USB drive.
        com.twig.app.StorageVolumes.of(node.file.path)?.initialUri?.let { uri ->
            actions.item(getString(R.string.volume_grant_saf), R.drawable.ic_folder) {
                host?.openSafPicker(uri)
            }
        }
        showActionMenu(requireContext(), node.label ?: node.file.name, actions)
    }

    /**
     * Long-press menu for the document-tree (SAF) root row = [dirRowActions] + "Remove document tree".
     *
     * This row looks like a directory but is actually **a grant**: rename/delete/move act on the real directory the grant points to,
     * while what the user wants when long-pressing it is "remove this tree from the sidebar" — these two collide, and the consequence is the directory getting deleted.
     * So it goes through the "non-mutable directory" path, with **copy** brought back alone (pure read-source; copying an entire tree is a real use case).
     *
     * There's also no per-grant revocation entry in system settings — without this entry the only way out is clearing the app's data.
     */
    private fun safRootMenu(node: PaneViewModel.FileNode) {
        val actions = dirRowActions(
            node, node.file,
            refresh = { viewModel.refreshNode(node) },
            includeCopy = true,
        )
        actions.item(getString(R.string.action_remove_saf), R.drawable.ic_close, DANGER) {
            confirmRemoveSaf(node.file)
        }
        showActionMenu(requireContext(), node.file.name, actions)
    }

    private fun confirmRemoveSaf(file: XFile) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.confirm_remove_saf, file.name))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                if (!SafFileSystem.release(requireContext(), file)) {
                    toast(getString(R.string.saf_remove_failed)); return@setPositiveButton
                }
                viewModel.forgetSaf(file)
                host?.refreshTrees() // The grant is global, so the row in the other pane has to vanish too.
            }
            .show()
    }

    /**
     * Long-press menu for the favorite row = [dirRowActions] + "Remove favorite", minus "Add to favorite" (it already is one).
     * Directory-related items only make sense once we have the real directory; if the favorite hasn't been expanded yet (not connected / not unlocked), only "Remove favorite" remains.
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

    /** Rename dialog: favorite / saved compare share the same interaction, writing to their respective Stores. */
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
     * Long-press menu for a saved compare row: unlike favorites it can't be "expanded to see content" (it's two locations, not
     * a single browsable directory), so it gets "Show both paths" instead — rename / delete share the favorite row's interaction.
     */
    private fun compareFavMenu(node: PaneViewModel.CompareNode) {
        val s = node.session
        val actions = ArrayList<MenuAct>()
        actions.item(getString(R.string.action_show_compare_paths), R.drawable.ic_compare) { showComparePaths(s) }
        // Sync doesn't need to enter the compare page first: the direction is fixed in the menu, the background scans and goes straight to the confirmation dialog
        // (see [syncFromSaved]). If the incremental switch is toggled in the dialog it's saved back to **this** favorite, not the global default.
        for (to in 0..1) {
            val toName = getString(if (to == 0) R.string.compare_side_left else R.string.compare_side_right)
            actions.item(getString(R.string.compare_sync_to, toName), R.drawable.ic_sync, DANGER) {
                syncFromSaved(
                    requireContext(), viewLifecycleOwner.lifecycleScope, s, to,
                    onOptions = { o -> CompareStore.updateOptions(requireContext(), s, o) },
                    // Progress dialog pops up as usual, same set as paste/compress; the user can switch to background by tapping "Hide" themselves.
                    onStarted = { showTransferBox() },
                )
            }
        }
        actions.item(getString(R.string.action_rename_favorite), R.drawable.ic_rename) {
            promptRenameLabel(R.string.action_rename_favorite, s.label) { name -> viewModel.renameCompare(s, name) }
        }
        actions.item(getString(R.string.compare_remove), R.drawable.ic_delete, DANGER) {
            confirmRemoveCompare(s)
        }
        showActionMenu(requireContext(), s.label, actions)
    }

    private fun showComparePaths(s: com.twig.app.CompareSession) {
        val conns = ConnectionStore.all(requireContext()).associateBy { it.label() }
        // Same as the line under the favorite row: use favoriteFullPath, so the SAF side doesn't spread out an entire document URI.
        val left = favoriteFullPath(s.left, conns[s.left.connLabel])
        val right = favoriteFullPath(s.right, conns[s.right.connLabel])
        AlertDialog.Builder(requireContext())
            .setTitle(s.label)
            .setMessage(getString(R.string.compare_paths_msg, left, right))
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun confirmRemoveCompare(s: com.twig.app.CompareSession) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.compare_remove_confirm, s.label))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> viewModel.removeCompare(s) }
            .show()
    }

    /** Favorite tap: local / network → expand directly; restic uses the saved password — only when there's no saved password does it prompt; failure shows the actual error. */
    private fun onFavoriteClick(node: PaneViewModel.FavoriteNode) {
        val saved = if (node.fav.kind == "restic") {
            Prefs.resticPassword(requireContext(), node.fav.repoPath)
        } else null
        viewModel.toggleFavorite(node, saved) { ok, err ->
            if (!ok) {
                // Restic without a saved password → prompt; otherwise (network / saved password) just show the actual error.
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
     * Server long-press menu = the directory set when connected ([dirRowActions], aimed at the server root)
     * + the server's own Edit / Remove (SFTP additionally gets an SSH terminal and command shortcut).
     * The directory group only exists once the server has been connected at least once (so we have its root); otherwise it's just the original entries.
     * [dirRowActions]' terminal/command entries are turned off: the ones here land in the post-login default directory (home),
     * which is more useful than opening at root "/", and offering both would just duplicate.
     */
    private fun serverMenu(node: PaneViewModel.ServerNode) {
        val conn = node.conn
        val actions = ArrayList<MenuAct>()
        val target = viewModel.serverTarget(node)
        if (target != null) {
            actions += dirRowActions(
                node, target,
                refresh = { viewModel.refreshServer(node) },
                includeShell = false,
            )
        } else if (!conn.isMediaServer()) {
            // Never connected in this session, so there is no root XFile yet — but the
            // jump still holds: revealUnder connects the server on its way down.
            actions.item(getString(R.string.action_goto_path), R.drawable.ic_goto) {
                promptGoto(node.key, null)
            }
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

    // ---- Remote command / command shortcut ----

    /** "Choose script…" backfill target input field (valid for the lifetime of the dialog, same approach as picking a private key). */
    private var scriptTarget: android.widget.EditText? = null

    private val scriptPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
            val path = r.data?.getStringExtra(PickerActivity.EXTRA_PICKED_PATH) ?: return@registerForActivityResult
            val target = scriptTarget ?: return@registerForActivityResult
            // Add an interpreter based on extension: scripts don't necessarily have +x permission, running the path directly is often Permission denied.
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
     * Configure a remote command: can either "Run now" or "Add to home screen" as a shortcut (see [RemoteCmd]).
     *
     * [scheme] is the scheme registered for that server **in this session**; used only by "Choose script" to expand the pane to that server.
     * If null (entered from the server node, never connected), picking a script has to connect first.
     * The shortcut itself stores the connection label, not the scheme — the scheme changes across launches.
     */
    private fun showCommandDialog(
        conn: SavedConnection,
        scheme: String?,
        workdir: String,
        title: String,
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        // maxLines > 1 fields grow with content: default is still one line tall, long commands grow themselves, then scroll internally once the cap is hit.
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
        // Login shell only makes sense for silent execution: the terminal is already an interactive login shell.
        val cbLogin = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.cmd_login_shell)
            textSize = 14f
            visibility = View.GONE
        }
        val cbTerminal = android.widget.CheckBox(ctx).apply {
            text = getString(R.string.cmd_in_terminal)
            isChecked = true // Default is to run visibly; silent is an explicit choice.
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
                    // Haven't connected to this server yet: connect first, get the scheme, then open the picker.
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

    /** Run once immediately (no shortcut): terminal mode needs the scheme, silent mode goes to the foreground service. */
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

    /** Open an SSH terminal (connect first if needed, on the IO thread). */
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
     * Which privileged terminal can currently be opened → (menu text, mode); null if none can.
     *
     * The criterion is **privileged access has connected** ([Privileged.active]), not "is there su on the device" —
     * showing this entry when it isn't connected just leads to a tap that errors out, better to not show it. The extra
     * [PrivShell.available] layer is because "file access can elevate" doesn't equal "a terminal can launch":
     * the Shizuku route also needs to fetch the rish dex from its APK.
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

    /**
     * Forget the remembered host key (TOFU reset). After a server reinstall / hardware swap the fingerprint changes and
     * the connection gets refused, prompting to come here; after clearing, a new one is recorded on the next connect.
     * Also forgetServer: the SftpFileSystem in the registry is still holding the old knownHostKey, and unless it's unregistered,
     * the next expand will reuse it and still fail to connect.
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

    // ---- Utilities ----

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
        progressBox?.detach() // The transfer itself isn't affected — just no one's watching anymore (the notification is still there).
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
         * Locked to a single source (for picker use): the tree has only [scheme] as its root,
         * and opens already expanded to [startPath].
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
