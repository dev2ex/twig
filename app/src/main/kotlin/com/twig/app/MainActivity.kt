package com.twig.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.twig.app.databinding.ActionStripBinding
import com.twig.app.databinding.ActivityMainBinding
import com.twig.app.databinding.DialogFtpBinding
import com.twig.app.databinding.DialogSmbBinding
import com.twig.app.databinding.DialogSortBinding
import com.twig.app.databinding.DialogWebdavBinding
import com.twig.app.ui.FileClipboard
import com.twig.app.share.WebShare
import com.twig.app.ui.PaneFragment
import com.twig.app.ui.ShareDialogs
import com.twig.app.ui.TermManager
import com.twig.app.ui.TerminalActivity
import com.twig.app.ui.sizeIconsLikeRows
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.launch

/**
 * X-plore 式双面板:
 * - 竖屏:单面板全屏,操作列在"对侧"(左面板→右列,右面板→左列),横向滑动切换面板;
 * - 横屏:双面板并排,操作列居中,触摸决定活动面板。
 */
class MainActivity : AppCompatActivity(), PaneFragment.Host {

    private lateinit var b: ActivityMainBinding
    private var activeIndex = 0
    /** 布局相关偏好快照;onResume 时变了(设置页改过)就 recreate。 */
    private var uiSig = ""

    companion object {
        /** 操作列单列宽(dp),与 activity_main.xml 里 include 的默认宽度一致 */
        private const val STRIP_COL_DP = 52

        private const val MENU_TERMINAL = 1
        private const val MENU_SWAP_PANE = 2
        private const val MENU_MUSIC = 3

        private const val EXTRA_REVEAL_SCHEME = "reveal_scheme"
        private const val EXTRA_REVEAL_PATH = "reveal_path"
        private const val EXTRA_REVEAL_FILE = "reveal_file"

        private const val EXTRA_MOUNT_SCHEME = "mount_scheme"
        private const val EXTRA_MOUNT_PATH = "mount_path"
        private const val EXTRA_MOUNT_NAME = "mount_name"
        private const val EXTRA_MOUNT_SIZE = "mount_size"
        private const val EXTRA_SHOW_TRANSFER = "show_transfer"
        private const val EXTRA_SHOW_SHARE = "show_share"

        /**
         * 「用 Twig 打开」一个压缩包([ui.ViewIntentActivity]):在当前面板顶部把它挂成
         * 一行就地展开。★ 不加 FLAG_ACTIVITY_NEW_TASK —— content:// 的临时读权限跟着
         * 接收方任务栈走,丢到新栈里读不到(见 ViewIntentActivity 类注释)。
         */
        fun mountIntent(ctx: Context, file: XFile): Intent =
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_MOUNT_SCHEME, file.scheme)
                putExtra(EXTRA_MOUNT_PATH, file.path)
                putExtra(EXTRA_MOUNT_NAME, file.name)
                putExtra(EXTRA_MOUNT_SIZE, file.size)
            }

        /**
         * 跳转到文件管理并在树中定位到某目录(音乐播放页/列表页"跳转到所在目录"用)。
         * [file] 给出时(与 [path] 同一 scheme 的文件全路径)展开后滚动到这一行,
         * 让"跳转到所在目录"能直接看到那个文件,而不是只停在目录上。
         */
        fun revealIntent(ctx: Context, scheme: String, path: String, file: String? = null): Intent =
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_REVEAL_SCHEME, scheme)
                putExtra(EXTRA_REVEAL_PATH, path)
                file?.let { putExtra(EXTRA_REVEAL_FILE, it) }
            }

        /**
         * 点通知栏那条传输进度条:回到文件管理并重新挂上进度框
         * (见 [ui.TransferService])。带 NEW_TASK 是因为从通知发起时没有任务栈。
         */
        fun transferIntent(ctx: Context): Intent =
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SHOW_TRANSFER, true)
            }

        /** 点通知栏那条「正在共享」:回到文件管理并弹出共享状态框(地址/停止)。 */
        fun shareIntent(ctx: Context): Intent =
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SHOW_SHARE, true)
            }
    }

    // 待定位目录:意图携带 scheme+path 时先记下,面板就绪(可能要等权限/异步初始化)后再展开定位
    private var pendingReveal: XFile? = null
    /** 待定位目录里要滚动到的文件(可选,见 [revealIntent])。 */
    private var pendingRevealFile: XFile? = null
    /** 待挂载的外部压缩包(见 [mountIntent]),同样等面板就绪后再挂。 */
    private var pendingMount: XFile? = null
    /** 从通知栏点回来:面板就绪后重新挂上传输进度框(见 [transferIntent])。 */
    private var pendingShowTransfer = false

    private val legacyPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) initPanesIfNeeded() else showPermissionRationale()
        }

    private val manageAllFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (hasStoragePermission()) initPanesIfNeeded() else showPermissionRationale()
        }

    /** SFTP 私钥选取:SAF 选文件后拷入应用私有目录(免跨应用权限失效),路径填回对话框。 */
    private var keyPathTarget: android.widget.EditText? = null
    private val keyPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val target = keyPathTarget ?: return@registerForActivityResult
            val uri = r.data?.data ?: return@registerForActivityResult
            runCatching {
                val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "key"
                val dir = java.io.File(filesDir, "keys").apply { mkdirs() }
                val out = java.io.File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                contentResolver.openInputStream(uri)!!.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out.path
            }.fold(
                onSuccess = { p ->
                    target.setText(p)
                    Toast.makeText(this, getString(R.string.sftp_key_copied, p), Toast.LENGTH_SHORT).show()
                },
                onFailure = {
                    Toast.makeText(this, getString(R.string.sftp_key_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                },
            )
        }

    private val safPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                refreshTrees()
                activePane()?.viewModel?.expandGroup("saf")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.rememberLocation(this)) activeIndex = Prefs.activePane(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        TwigApp.registerBaseFs(this) // 基础来源(本地/压缩包/SAF/应用/content://)

        uiSig = Prefs.uiSignature(this)
        wireStrip(b.stripLeft)
        wireStrip(b.stripMid)
        wireStrip(b.stripRight)
        syncShareIcon()
        installClipboardBar()
        applyLayoutMode()
        applyFullscreen()

        readRevealExtras(intent)
        readMountExtras(intent)
        readTransferExtra(intent)
        readShareExtra(intent)
        ensurePermissionThenInit()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRevealExtras(intent)
        readMountExtras(intent)
        readTransferExtra(intent)
        readShareExtra(intent)
        applyPendingReveal()
    }

    private fun readRevealExtras(intent: Intent?) {
        val scheme = intent?.getStringExtra(EXTRA_REVEAL_SCHEME) ?: return
        val path = intent.getStringExtra(EXTRA_REVEAL_PATH) ?: return
        pendingReveal = XFile(scheme, path, isDir = true)
        pendingRevealFile = intent.getStringExtra(EXTRA_REVEAL_FILE)
            ?.let { XFile(scheme, it, isDir = false) }
    }

    private fun readTransferExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_TRANSFER, false) == true) pendingShowTransfer = true
    }

    /**
     * 从「正在共享」通知点回来:直接弹状态框。不像传输进度框那样要等面板就绪——
     * 共享对话框不依赖任何面板状态,post 一下只是为了别在 onCreate 里就 show 窗口。
     */
    private fun readShareExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_SHARE, false) != true) return
        intent.removeExtra(EXTRA_SHOW_SHARE) // 别让配置变更后的重建再弹一次
        window.decorView.post { if (!isFinishing && !isDestroyed) ShareDialogs.show(this) }
    }

    private fun readMountExtras(intent: Intent?) {
        val scheme = intent?.getStringExtra(EXTRA_MOUNT_SCHEME) ?: return
        val path = intent.getStringExtra(EXTRA_MOUNT_PATH) ?: return
        pendingMount = XFile(
            scheme, path, isDir = false,
            size = intent.getLongExtra(EXTRA_MOUNT_SIZE, 0L),
            displayName = intent.getStringExtra(EXTRA_MOUNT_NAME),
        )
    }

    private fun applyPendingReveal() {
        if (activePane() == null) return // 面板还没就绪(权限/初始化未完成),initPanesIfNeeded 后再试
        if (pendingShowTransfer) {
            pendingShowTransfer = false
            activePane()?.showTransferBox()
        }
        pendingMount?.let {
            pendingMount = null
            activePane()?.mountExternal(it)
        }
        val target = pendingReveal ?: return
        val focus = pendingRevealFile
        pendingReveal = null
        pendingRevealFile = null
        activePane()?.reveal(target, focus)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (activePane()?.handleBack() == true) return // 占用图内:回上级/退出占用图
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen() // 系统可能在切换后恢复状态栏,重新应用
    }

    override fun onResume() {
        super.onResume()
        // 从设置页回来:行高/缩略图等布局相关偏好变了就重建生效
        if (uiSig.isNotEmpty() && uiSig != Prefs.uiSignature(this)) {
            uiSig = Prefs.uiSignature(this)
            recreate()
            return
        }
        // 传输在界面不在场时跑完了:回来补上收尾(清选中/刷两侧/提示结果)。
        // 面板还没就绪时先不取——取走就没人处理了,下次 onResume 再说
        activePane()?.let { p -> com.twig.app.ui.Transfers.consumeFinished()?.let { p.finishTransfer(it) } }
        invalidateOptionsMenu() // 终端会话可能在别的 Activity 里增删,回来刷新入口显隐
        val land = isLandscape()
        for (s in listOf(b.stripMid, b.stripLeft, b.stripRight)) applyStripTop(s, land)
        // 共享可能是从通知栏那个「停止」按钮关掉的(界面根本没参与),回前台对一次状态
        syncShareIcon()
        WebShare.onStateChanged = { runOnUiThread { syncShareIcon() } }
    }

    override fun onPause() {
        super.onPause()
        // 摘掉回调:它捕获了这个 Activity,留着就是一条通往已销毁界面的引用
        WebShare.onStateChanged = null
    }

    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (Prefs.fullscreen(this)) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars()) // 只隐藏状态栏
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    // ---- 侧边操作列 ----

    private fun wireStrip(s: ActionStripBinding) {
        s.sizeIconsLikeRows(this)
        s.asUp.setOnClickListener { activePane()?.actionUp() }
        s.asSort.setOnClickListener { showSortDialog() }
        s.asRefresh.setOnClickListener { activePane()?.actionRefresh() }
        s.asNewFolder.setOnClickListener { activePane()?.actionNewFolder() }
        s.asCopy.setOnClickListener { activePane()?.actionCopy(move = false) }
        s.asMove.setOnClickListener { activePane()?.actionCopy(move = true) }
        s.asCompress.setOnClickListener { activePane()?.actionCompress() }
        s.asRename.setOnClickListener { activePane()?.actionRename() }
        s.asDelete.setOnClickListener { activePane()?.actionDelete() }
        s.asCompare.setOnClickListener { startCompare() }
        s.asMap.setOnClickListener { activePane()?.actionTreemap() }
        s.asSearch.setOnClickListener { activePane()?.actionSearch() }
        s.asHistory.setOnClickListener { activePane()?.actionHistory() }
        // 共享范围默认就是绿框选中的那个目录([PaneViewModel.currentDir]),
        // 对话框里不再另做目录选择——入口本来就长在文件树旁边,位置早就指定过了
        s.asShare.setOnClickListener {
            ShareDialogs.show(this, activePane()?.viewModel?.currentDir)
        }

        // 顶部固定区(横屏没有 Toolbar 时才显示):原标题栏右侧那几个入口
        s.asTerm.setOnClickListener { TerminalActivity.resume(this) }
        s.asMusic.setOnClickListener {
            startActivity(Intent(this, com.twig.app.ui.MusicPlayerActivity::class.java))
        }
        s.asSwap.setOnClickListener { setActiveIndex(1 - activeIndex) }
        s.asMenu.setOnClickListener { showStripOverflow(it) }
    }

    /** 横屏没有 Toolbar,溢出菜单改成锚在操作列「菜单」按钮上的 PopupMenu(项与处理都复用 R.menu.main)。 */
    private fun showStripOverflow(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.inflate(R.menu.main)
        syncMenuChecks(popup.menu)
        popup.setOnMenuItemClickListener { onOptionsItemSelected(it) }
        popup.show()
    }

    /**
     * 顶部固定区的显隐与状态:横屏隐藏 Toolbar 后才出现;终端入口按会话有无显示,
     * 切面板图标跟着活动面板换方向(与 Toolbar 上的那颗一致)。
     */
    /**
     * 共享开着时把操作列那个图标换成实心扇形。
     *
     * 共享一开可能几小时没人管,只靠通知栏很容易忘了它还开着——主界面上得有个
     * 余光扫过就能看见的状态。三条操作列(横屏中列 + 竖屏左右列)都要刷。
     */
    private fun syncShareIcon() {
        val res = if (WebShare.isRunning) R.drawable.ic_share_wifi_on else R.drawable.ic_share_wifi
        for (s in listOf(b.stripMid, b.stripLeft, b.stripRight)) s.asShareIcon.setImageResource(res)
    }

    private fun applyStripTop(s: ActionStripBinding, land: Boolean) {
        s.asTop.visibility = if (land) View.VISIBLE else View.GONE
        s.asTopDivider.visibility = if (land) View.VISIBLE else View.GONE
        if (!land) return
        // GridLayout 会给 GONE 的子 view 留空单元格,所以终端入口是整个摘掉/插回,不是设 GONE
        val wantTerm = TermManager.list().isNotEmpty()
        val hasTerm = s.asTerm.parent != null
        if (wantTerm && !hasTerm) s.asTop.addView(s.asTerm, 0)
        else if (!wantTerm && hasTerm) s.asTop.removeView(s.asTerm)
        s.asSwapIcon.setImageResource(
            if (activeIndex == 0) R.drawable.ic_pane_to_right else R.drawable.ic_pane_to_left,
        )
    }

    // ---- 剪贴板栏(横跨整个窗口,不属于某一侧面板) ----

    /**
     * 内容与「移动」勾选来自全局 [FileClipboard],**粘贴目标是活动面板的当前目录**——
     * 栏放在两个面板下方通栏,切面板即换目标,不必在两侧各摆一条。
     */
    private fun installClipboardBar() {
        b.cbClipMove.setOnCheckedChangeListener { _, checked -> FileClipboard.setMove(checked) }
        b.btnPaste.setOnClickListener { activePane()?.pasteFromClipboard() }
        b.btnClipClear.setOnClickListener { FileClipboard.clear() }
        b.tvClip.setOnClickListener { showClipContents() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FileClipboard.state.collect { renderClipBar(it) }
            }
        }
    }

    private fun renderClipBar(s: FileClipboard.State = FileClipboard.state.value) {
        b.clipBar.visibility = if (s.items.isEmpty()) View.GONE else View.VISIBLE
        if (s.items.isEmpty()) return
        b.tvClip.text =
            if (s.items.size == 1) getString(R.string.clip_bar_one, s.items[0].name)
            else getString(R.string.clip_bar_n, s.items.size)
        // 粘贴不再有确认框,这行就是唯一的落点提示;落不下去时把原因写在这儿并置灰按钮
        val target = activePane()?.clipTarget()
        val block = FileClipboard.pasteBlockReason(target)
        b.tvClipDest.text = when {
            block != null -> getString(block)
            target == null -> getString(R.string.clip_dest_none)
            else -> getString(R.string.clip_dest, activePane()?.clipTargetLabel().orEmpty())
        }
        val canPaste = target != null && block == null
        b.btnPaste.isEnabled = canPaste
        b.btnPaste.alpha = if (canPaste) 1f else 0.4f
        // 值相同就不回写,避免和 setOnCheckedChangeListener 来回打转
        if (b.cbClipMove.isChecked != s.move) b.cbClipMove.isChecked = s.move
    }

    /** 点栏上的内容摘要:列出全部条目,确认放进去的到底是哪些。 */
    private fun showClipContents() {
        val items = FileClipboard.items
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.clip_title)
            .setItems(items.map { it.toUri() }.toTypedArray(), null)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    // ---- 布局模式 ----

    private fun isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyLayoutMode() {
        val land = isLandscape()
        b.paneA.visibility = if (land || activeIndex == 0) View.VISIBLE else View.GONE
        b.paneB.visibility = if (land || activeIndex == 1) View.VISIBLE else View.GONE
        b.stripMid.root.visibility = if (land) View.VISIBLE else View.GONE
        b.stripLeft.root.visibility = if (!land && activeIndex == 1) View.VISIBLE else View.GONE
        b.stripRight.root.visibility = if (!land && activeIndex == 0) View.VISIBLE else View.GONE
        // 横屏没有 Toolbar(纵向空间宝贵),它右侧的按钮挪进操作列顶部固定区
        b.toolbar.visibility = if (land) View.GONE else View.VISIBLE
        // 横屏高度不够放下一整列,操作列改双列(宽度跟着翻倍)
        for (s in listOf(b.stripMid, b.stripLeft, b.stripRight)) {
            applyStripColumns(s, land)
            applyStripTop(s, land)
        }
        paneAt(0)?.setActive(activeIndex == 0)
        paneAt(1)?.setActive(activeIndex == 1)
        renderClipBar() // 活动面板换了,粘贴目标跟着换
    }

    /** 操作列列数:竖屏 1 列(高度够),横屏 2 列;列宽固定 [STRIP_COL_DP],总宽跟着列数走。 */
    private fun applyStripColumns(s: ActionStripBinding, land: Boolean) {
        val on = Prefs.rowDivider(this) // 与列表行间分割线同一个开关
        s.asGrid.dividers = on
        s.asTop.dividers = on
        val cols = if (land) 2 else 1
        if (s.asGrid.columnCount != cols) s.asGrid.columnCount = cols
        val w = (STRIP_COL_DP * cols * resources.displayMetrics.density).toInt()
        val lp = s.root.layoutParams
        if (lp.width != w) {
            lp.width = w
            s.root.layoutParams = lp
        }
    }

    private fun setActiveIndex(i: Int) {
        if (activeIndex != i) {
            activeIndex = i
            Prefs.setActivePane(this, i)
            applyLayoutMode()
            invalidateOptionsMenu() // 切换图标方向跟着活动面板走(含滑动/触摸切换)
        }
    }

    /**
     * 用两个面板的当前目录开对比页。两侧都得先选中一个目录——刚启动还没点过任何目录时
     * `currentDir` 是 null,这时给提示而不是拿根目录硬凑。
     */
    private fun startCompare() {
        val l = paneAt(0)?.viewModel?.currentDir
        val r = paneAt(1)?.viewModel?.currentDir
        if (l == null || r == null) {
            android.widget.Toast.makeText(this, R.string.compare_need_two, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        com.twig.app.ui.CompareActivity.start(this, l, r)
    }

    private fun paneAt(i: Int): PaneFragment? =
        supportFragmentManager.findFragmentByTag("pane$i") as? PaneFragment

    private fun activePane(): PaneFragment? = paneAt(activeIndex)

    private fun initPanesIfNeeded() {
        if (supportFragmentManager.findFragmentByTag("pane0") == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.pane_a, PaneFragment.newInstance(0), "pane0")
                .replace(R.id.pane_b, PaneFragment.newInstance(1), "pane1")
                .commit()
        }
        applyLayoutMode()
        // 等 fragment 事务落地、面板就绪
        if (pendingReveal != null || pendingMount != null) b.root.post { applyPendingReveal() }
    }

    // ---- Host ----

    override fun siblingOf(self: PaneFragment): PaneFragment? = paneAt(1 - self.paneIndex)

    override fun focusPane(pane: PaneFragment) = setActiveIndex(pane.paneIndex)

    override fun isPaneActive(self: PaneFragment): Boolean = self.paneIndex == activeIndex

    override fun onPaneTouched(self: PaneFragment) {
        if (isLandscape()) setActiveIndex(self.paneIndex)
    }

    override fun onPaneSwipe(velocityX: Float) {
        // 向左滑(负速度)→ 显示右面板;向右滑 → 左面板
        if (!isLandscape()) setActiveIndex(if (velocityX < 0) 1 else 0)
    }

    override fun onClipTargetChanged() {
        if (b.clipBar.visibility == View.VISIBLE) renderClipBar()
    }

    override fun refreshTrees() {
        paneAt(0)?.viewModel?.refreshTree()
        paneAt(1)?.viewModel?.refreshTree()
    }

    override fun onAddServer(type: String) = when (type) {
        "smb" -> showSmbDialog()
        "ftp" -> showFtpDialog()
        "sftp" -> showSftpDialog()
        "webdav" -> showWebdavDialog()
        "s3" -> showS3Dialog()
        // 扫到的 Twig 共享存成 WebDAV 连接,所以入口放在 WebDAV 组里
        "scan_twig" -> ShareDialogs.scanAndAdd(this) {
            refreshTrees()
            activePane()?.viewModel?.expandGroup("dav")
        }
        "saf" -> runCatching { safPickerLauncher.launch(null) }
            .onFailure {
                Toast.makeText(this, getString(R.string.saf_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
            }.let { }
        else -> Unit
    }

    override fun onEditServer(conn: SavedConnection) = when (conn.type) {
        "smb" -> showSmbDialog(conn)
        "ftp" -> showFtpDialog(conn)
        "sftp" -> showSftpDialog(conn)
        "webdav" -> showWebdavDialog(conn)
        "s3" -> showS3Dialog(conn)
        else -> Unit
    }

    // ---- 添加/编辑服务器(仅保存配置,展开节点时才连接) ----

    /** 保存(编辑时先移除旧条目并丢弃已建立的连接缓存)。 */
    private fun saveServer(edit: SavedConnection?, conn: SavedConnection, group: String) {
        if (edit != null) {
            ConnectionStore.remove(this, edit)
            paneAt(0)?.viewModel?.forgetServer(edit.label())
            paneAt(1)?.viewModel?.forgetServer(edit.label())
        }
        ConnectionStore.save(this, conn)
        refreshTrees()
        activePane()?.viewModel?.expandGroup(group)
    }

    private fun showSmbDialog(edit: SavedConnection? = null) {
        val d = DialogSmbBinding.inflate(layoutInflater)
        edit?.let {
            d.etName.setText(it.name); d.etHost.setText(it.host); d.etShare.setText(it.share)
            d.etUser.setText(it.user); d.etPass.setText(it.password); d.etDomain.setText(it.domain)
        }
        AlertDialog.Builder(this)
            .setTitle(if (edit == null) getString(R.string.action_connect_smb) else getString(R.string.server_edit))
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val host = d.etHost.text.toString().trim()
                val share = d.etShare.text.toString().trim()
                if (host.isEmpty() || share.isEmpty()) return@setPositiveButton
                saveServer(
                    edit,
                    SavedConnection(
                        "smb", host, share = share,
                        user = d.etUser.text.toString().trim().ifEmpty { "guest" },
                        password = d.etPass.text.toString(),
                        domain = d.etDomain.text.toString().trim().ifEmpty { "WORKGROUP" },
                        name = d.etName.text.toString().trim(),
                    ),
                    "lan",
                )
            }
            .show()
    }

    private fun showFtpDialog(edit: SavedConnection? = null) {
        val d = DialogFtpBinding.inflate(layoutInflater)
        edit?.let {
            d.etName.setText(it.name); d.etHost.setText(it.host); d.etPort.setText(it.port.toString())
            d.etUser.setText(it.user); d.etPass.setText(it.password)
        }
        AlertDialog.Builder(this)
            .setTitle(if (edit == null) getString(R.string.action_connect_ftp) else getString(R.string.server_edit))
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val host = d.etHost.text.toString().trim()
                if (host.isEmpty()) return@setPositiveButton
                saveServer(
                    edit,
                    SavedConnection(
                        "ftp", host,
                        port = d.etPort.text.toString().toIntOrNull() ?: 21,
                        user = d.etUser.text.toString().trim().ifEmpty { "anonymous" },
                        password = d.etPass.text.toString(),
                        name = d.etName.text.toString().trim(),
                    ),
                    "ftp",
                )
            }
            .show()
    }

    private fun showSftpDialog(edit: SavedConnection? = null) {
        val d = com.twig.app.databinding.DialogSftpBinding.inflate(layoutInflater)
        edit?.let {
            d.etName.setText(it.name); d.etHost.setText(it.host); d.etPort.setText(it.port.toString())
            d.etUser.setText(it.user); d.etPass.setText(it.password); d.etKey.setText(it.keyPath)
        }
        d.btnPickKey.setOnClickListener {
            keyPathTarget = d.etKey
            runCatching {
                keyPickerLauncher.launch(
                    com.twig.app.ui.PickerActivity.intent(this, getString(R.string.sftp_pick_key)),
                )
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (edit == null) getString(R.string.action_connect_sftp) else getString(R.string.server_edit))
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val host = d.etHost.text.toString().trim()
                val user = d.etUser.text.toString().trim()
                if (host.isEmpty() || user.isEmpty()) return@setPositiveButton
                saveServer(
                    edit,
                    SavedConnection(
                        "sftp", host,
                        port = d.etPort.text.toString().toIntOrNull() ?: 22,
                        user = user,
                        password = d.etPass.text.toString(),
                        name = d.etName.text.toString().trim(),
                        keyPath = d.etKey.text.toString().trim(),
                        // 保留已记住的主机密钥:改个显示名不该把信任一起清掉。
                        // 要重置走服务器长按菜单里的「忘记主机密钥」。
                        hostKey = edit?.hostKey.orEmpty(),
                    ),
                    "sftp",
                )
            }
            .show()
    }

    private fun showWebdavDialog(edit: SavedConnection? = null) {
        val d = DialogWebdavBinding.inflate(layoutInflater)
        edit?.let {
            d.etName.setText(it.name); d.etUrl.setText(it.host)
            d.etUser.setText(it.user); d.etPass.setText(it.password)
        }
        AlertDialog.Builder(this)
            .setTitle(if (edit == null) getString(R.string.action_connect_webdav) else getString(R.string.server_edit))
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val url = d.etUrl.text.toString().trim()
                if (!url.startsWith("http")) return@setPositiveButton
                saveServer(
                    edit,
                    SavedConnection(
                        "webdav", url,
                        user = d.etUser.text.toString().trim(),
                        password = d.etPass.text.toString(),
                        name = d.etName.text.toString().trim(),
                    ),
                    "dav",
                )
            }
            .show()
    }

    private fun showS3Dialog(edit: SavedConnection? = null) {
        val d = com.twig.app.databinding.DialogS3Binding.inflate(layoutInflater)
        edit?.let {
            d.etName.setText(it.name); d.etEndpoint.setText(it.host)
            d.etKey.setText(it.user); d.etSecret.setText(it.password)
            d.etBucket.setText(it.share); d.etRegion.setText(it.region)
            d.cbPathStyle.isChecked = it.pathStyle
        }
        AlertDialog.Builder(this)
            .setTitle(if (edit == null) getString(R.string.action_connect_s3) else getString(R.string.server_edit))
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val endpoint = d.etEndpoint.text.toString().trim()
                if (!endpoint.startsWith("http")) return@setPositiveButton
                saveServer(
                    edit,
                    SavedConnection(
                        "s3", endpoint,
                        share = d.etBucket.text.toString().trim(),
                        user = d.etKey.text.toString().trim(),
                        password = d.etSecret.text.toString().trim(),
                        name = d.etName.text.toString().trim(),
                        region = d.etRegion.text.toString().trim().ifEmpty { "us-east-1" },
                        pathStyle = d.cbPathStyle.isChecked,
                    ),
                    "s3",
                )
            }
            .show()
    }

    // ---- 菜单 ----

    /** R.menu.main 里几个开关项的勾选状态;Toolbar 菜单与横屏的操作列 PopupMenu 共用。 */
    private fun syncMenuChecks(menu: Menu) {
        menu.findItem(R.id.action_remember_location)?.isChecked = Prefs.rememberLocation(this)
        menu.findItem(R.id.action_fullscreen)?.isChecked = Prefs.fullscreen(this)
        menu.findItem(R.id.action_thumbs)?.isChecked = Prefs.thumbs(this)
        menu.findItem(R.id.action_show_hidden)?.isChecked = Prefs.showHidden(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        syncMenuChecks(menu)
        // 终端入口常驻:没有会话时点进去会新建一条本地 shell(见 TerminalActivity.handleIntent)
        menu.add(0, MENU_TERMINAL, 0, getString(R.string.terminal_menu)).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_terminal)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_MUSIC, 0, getString(R.string.music_menu)).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_music_note)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        // 切到另一面板:当前面板实心、目标空心,箭头指向要切去的一侧
        menu.add(0, MENU_SWAP_PANE, 1, getString(R.string.menu_swap_pane)).apply {
            icon = ContextCompat.getDrawable(
                this@MainActivity,
                if (activeIndex == 0) R.drawable.ic_pane_to_right else R.drawable.ic_pane_to_left,
            )
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_TERMINAL -> {
            TerminalActivity.resume(this); true
        }
        MENU_MUSIC -> {
            startActivity(Intent(this, com.twig.app.ui.MusicPlayerActivity::class.java)); true
        }
        MENU_SWAP_PANE -> {
            setActiveIndex(1 - activeIndex); true
        }
        R.id.action_density -> {
            showDensityDialog(); true
        }
        R.id.action_view_mode -> {
            showViewModeDialog(); true
        }
        R.id.action_thumbs -> {
            Prefs.setThumbs(this, !item.isChecked)
            uiSig = Prefs.uiSignature(this)
            recreate()
            true
        }
        R.id.action_show_hidden -> {
            Prefs.setShowHidden(this, !item.isChecked)
            uiSig = Prefs.uiSignature(this)
            recreate()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, com.twig.app.ui.SettingsActivity::class.java)); true
        }
        R.id.action_remember_location -> {
            val on = !item.isChecked
            item.isChecked = on
            Prefs.setRememberLocation(this, on)
            true
        }
        R.id.action_fullscreen -> {
            val on = !item.isChecked
            item.isChecked = on
            Prefs.setFullscreen(this, on)
            applyFullscreen()
            true
        }
        R.id.action_theme -> {
            showThemeDialog(); true
        }
        R.id.action_language -> {
            showLanguageDialog(); true
        }
        R.id.action_pin_music_shortcut -> {
            pinMusicShortcut(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun pinMusicShortcut() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, R.string.music_pin_shortcut_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, com.twig.app.ui.MusicPlayerActivity::class.java).setAction(Intent.ACTION_VIEW)
        val shortcut = ShortcutInfoCompat.Builder(this, "music_pinned")
            .setShortLabel(getString(R.string.music_menu))
            .setLongLabel(getString(R.string.music_title))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_music_note))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
    }

    private fun showSortDialog() {
        val d = DialogSortBinding.inflate(layoutInflater)
        val cur = SortSpec.load(this)
        d.rgBy.check(
            when (cur.by) {
                FileSortKey.NAME -> R.id.rb_name
                FileSortKey.SIZE -> R.id.rb_size
                FileSortKey.EXT -> R.id.rb_ext
                FileSortKey.DATE -> R.id.rb_date
            },
        )
        d.cbReversed.isChecked = cur.reversed
        d.rgFolder.check(
            when (cur.folderBy) {
                FolderSortKey.NAME -> R.id.rf_name
                FolderSortKey.DATE_OLD -> R.id.rf_date_old
                FolderSortKey.DATE_NEW -> R.id.rf_date_new
            },
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.action_sort)
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val spec = SortSpec(
                    by = when (d.rgBy.checkedRadioButtonId) {
                        R.id.rb_size -> FileSortKey.SIZE
                        R.id.rb_ext -> FileSortKey.EXT
                        R.id.rb_date -> FileSortKey.DATE
                        else -> FileSortKey.NAME
                    },
                    reversed = d.cbReversed.isChecked,
                    folderBy = when (d.rgFolder.checkedRadioButtonId) {
                        R.id.rf_date_old -> FolderSortKey.DATE_OLD
                        R.id.rf_date_new -> FolderSortKey.DATE_NEW
                        else -> FolderSortKey.NAME
                    },
                )
                SortSpec.save(this, spec)
                paneAt(0)?.viewModel?.setSort(spec)
                paneAt(1)?.viewModel?.setSort(spec)
            }
            .show()
    }

    /** 视图快速切换:网格三态(独立于缩略图开关,细项在设置页)。 */
    private fun showViewModeDialog() {
        val labels = arrayOf(
            getString(R.string.view_mode_grid_off),
            getString(R.string.view_mode_grid_media),
            getString(R.string.view_mode_grid_all),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.action_view_mode)
            .setSingleChoiceItems(labels, Prefs.thumbsGrid(this).coerceIn(0, 2)) { dlg, which ->
                Prefs.setThumbsGrid(this, which)
                dlg.dismiss()
                uiSig = Prefs.uiSignature(this)
                recreate()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showDensityDialog() {
        val labels = arrayOf(
            getString(R.string.density_compact),
            getString(R.string.density_normal),
            getString(R.string.density_large),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.action_density)
            .setSingleChoiceItems(labels, Prefs.density(this)) { dlg, which ->
                Prefs.setDensity(this, which)
                dlg.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showThemeDialog() {
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
        )
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
        )
        val current = modes.indexOf(Prefs.themeMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_theme)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                Prefs.setThemeMode(this, modes[which])
                dlg.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 语言:跟随系统 / 中文 / English。用 AppCompatDelegate 的按应用语言 API(1.6+),
     * 不用自己在每个 Activity 的 attachBaseContext 里套 Locale/Configuration——
     * 它会自动让所有存活的 Activity 按新语言 recreate,并且(靠 manifest 里声明的
     * AppLocalesMetadataHolderService)API 33 以下也能持久化 + 冷启动早期应用,不闪一下系统语言。
     */
    private fun showLanguageDialog() {
        val tags = arrayOf("", "zh", "en")
        val labels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_zh),
            getString(R.string.language_en),
        )
        val current = tags.indexOf(AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-'))
            .let { if (it < 0) 0 else it }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_language)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                val tag = tags[which]
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    else androidx.core.os.LocaleListCompat.forLanguageTags(tag),
                )
                dlg.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---- 权限 ----

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePermissionThenInit() {
        if (hasStoragePermission()) {
            initPanesIfNeeded()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_needed_title)
                .setMessage(R.string.perm_needed_msg)
                .setPositiveButton(R.string.perm_go_settings) { _, _ -> requestManageAllFiles() }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        } else {
            legacyPermLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    private fun requestManageAllFiles() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { manageAllFilesLauncher.launch(intent) }
            .onFailure {
                manageAllFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_needed_title)
            .setMessage(R.string.perm_needed_msg)
            .setPositiveButton(R.string.perm_go_settings) { _, _ -> ensurePermissionThenInit() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
