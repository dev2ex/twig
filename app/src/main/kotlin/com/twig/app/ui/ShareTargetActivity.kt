package com.twig.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.app.ShareSourceFileSystem
import com.twig.app.databinding.ActivityShareTargetBinding
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isWritableDir
import kotlinx.coroutines.launch

/**
 * 接收其他 App 的分享(图片/任意文件),弹出目录选择界面("复制到"):
 * 复用主界面同一套 [PaneFragment] 树导航,分享进来的 content:// URI 经
 * [ShareSourceFileSystem] 包成 XFile,交给 [CopyEngine] 流式写入所选目标目录——
 * 不新增拷贝逻辑,只新增一个只读来源。
 */
class ShareTargetActivity : AppCompatActivity(), PaneFragment.Host {

    private data class IncomingItem(val uri: Uri, val name: String, val size: Long)

    private lateinit var b: ActivityShareTargetBinding
    private lateinit var incoming: List<IncomingItem>
    private lateinit var shareFs: ShareSourceFileSystem

    private val manageAllFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (hasStoragePermission()) initPaneIfNeeded() else showPermissionRationale()
        }

    private val legacyPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) initPaneIfNeeded() else showPermissionRationale()
        }

    private val safPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                pane()?.viewModel?.refreshTree()
                pane()?.viewModel?.expandGroup("saf")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming = resolveIncoming(intent)
        if (incoming.isEmpty()) {
            Toast.makeText(this, getString(R.string.share_no_content), Toast.LENGTH_LONG).show()
            finish(); return
        }

        b = ActivityShareTargetBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.title = getString(R.string.copy_to)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.tvSummary.text = if (incoming.size == 1) incoming[0].name
        else getString(R.string.copy_items, incoming.size)

        com.twig.app.TwigApp.registerBaseFs(this)
        shareFs = FsRegistry.of(ShareSourceFileSystem.SCHEME) as ShareSourceFileSystem

        // 只留导航相关的几个操作;复制/移动/重命名/删除/占用图在"选目标目录"场景下不需要
        b.strip.sizeIconsLikeRows(this)
        b.strip.asSort.visibility = View.GONE
        b.strip.asCopy.visibility = View.GONE
        b.strip.asMove.visibility = View.GONE
        b.strip.asRename.visibility = View.GONE
        b.strip.asDelete.visibility = View.GONE
        b.strip.asCompare.visibility = View.GONE
        b.strip.asMap.visibility = View.GONE
        b.strip.asUp.setOnClickListener { pane()?.actionUp() }
        b.strip.asRefresh.setOnClickListener { pane()?.actionRefresh() }
        b.strip.asNewFolder.setOnClickListener { pane()?.actionNewFolder() }

        b.btnCancel.setOnClickListener { finish() }
        b.btnConfirm.setOnClickListener { onConfirm() }
        setConfirmEnabled(false) // 默认禁用,直到选中一个可写目录

        ensurePermissionThenInit()
    }

    private fun pane(): PaneFragment? = supportFragmentManager.findFragmentByTag(PANE_TAG) as? PaneFragment

    private fun initPaneIfNeeded() {
        if (supportFragmentManager.findFragmentByTag(PANE_TAG) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.pane, PaneFragment.newInstance(0), PANE_TAG)
                .commitNow() // 同步提交,后面立刻取 viewModel 观察 currentDir
        }
        observeCurrentDir()
    }

    /** 只有选中一个真实存在且可写的目录时才允许点"复制到此";其余情况(未选择/只读来源等)禁用。 */
    private fun observeCurrentDir() {
        val vm = pane()?.viewModel ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { st -> setConfirmEnabled(st.currentDir?.isWritableDir() == true) }
            }
        }
    }

    private fun setConfirmEnabled(enabled: Boolean) {
        b.btnConfirm.isEnabled = enabled
        b.btnConfirm.alpha = if (enabled) 1f else 0.4f
    }

    // ---- PaneFragment.Host:单面板场景,大部分回调无意义 ----

    override fun siblingOf(self: PaneFragment): PaneFragment? = null

    override fun onAddServer(type: String) {
        if (type == "saf") {
            runCatching { safPickerLauncher.launch(null) }
                .onFailure {
                    Toast.makeText(this, getString(R.string.saf_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                }
        } else {
            Toast.makeText(this, getString(R.string.share_add_server_hint), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onEditServer(conn: SavedConnection) {
        Toast.makeText(this, getString(R.string.share_add_server_hint), Toast.LENGTH_SHORT).show()
    }

    override fun onPaneTouched(self: PaneFragment) {}
    override fun onPaneSwipe(velocityX: Float) {}
    override fun refreshTrees() { pane()?.viewModel?.refreshTree() }
    override fun isPaneActive(self: PaneFragment): Boolean = true
    override fun focusPane(pane: PaneFragment) {}

    // ---- 确认:拷贝到当前选中目录 ----

    private fun onConfirm() {
        val dest = pane()?.viewModel?.currentDir
        if (dest?.isWritableDir() != true) {
            Toast.makeText(this, getString(R.string.msg_pick_dir_first), Toast.LENGTH_SHORT).show()
            return
        }
        startCopy(dest)
    }

    /**
     * 与主界面同一套:活儿交给 [Transfers] 的后台会话(前台服务 + 通知栏进度条),
     * 进度框/冲突框都由内嵌的 [PaneFragment] 出([PaneFragment.showTransferBox])。
     *
     * ★ 分享进来的 `content://` 读权限跟**本任务栈**走:任务还在,后台跑着的复制就读得到;
     * 用户把这张卡片从最近任务划掉,权限连同进程一起没,这一趟也就断了(和改造前一样)。
     */
    private fun startCopy(dest: XFile) {
        val items = incoming.map { shareFs.wrap(it.uri, it.name, it.size) }
        val session = Transfers.Session(
            Transfers.Work.Copy(items, dest, move = false, fromClipboard = false),
            R.string.progress_copy,
            pathLabel(dest),
            FileIcons.sourceIconRes(dest.scheme),
            plan0 = null,
        )
        val started = runCatching { Transfers.start(applicationContext, session) }
            .getOrElse {
                Toast.makeText(this, it.message ?: getString(R.string.err_failed), Toast.LENGTH_LONG).show()
                return
            }
        if (!started) {
            Toast.makeText(this, getString(R.string.transfer_busy), Toast.LENGTH_LONG).show()
            return
        }
        pane()?.showTransferBox()
    }

    /** 复制完就收工(取消/失败留在原地,让用户看得见并能重试)。 */
    override fun onTransferFinished(session: Transfers.Session) {
        if (session.finished?.isSuccess == true && !session.cancelled.get()) finish()
    }

    /** 与面板路径栏同一写法(网络来源带服务器名);面板还没就绪时退回不带服务器名的形式。 */
    private fun pathLabel(f: XFile): String = pane()?.displayPath(f) ?: when {
        f.scheme == "file" -> f.path
        f.scheme == "saf" -> f.name
        else -> "${Format.schemeLabel(f.scheme)}:${f.path}"
    }

    // ---- 接收分享内容 ----

    private fun resolveIncoming(intent: Intent): List<IncomingItem> {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(extraStreamSingle(intent))
            Intent.ACTION_SEND_MULTIPLE -> extraStreamMultiple(intent) ?: emptyList()
            else -> emptyList()
        }
        return uris.mapNotNull { queryMeta(it) }
    }

    private fun extraStreamSingle(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun extraStreamMultiple(intent: Intent): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

    private fun queryMeta(uri: Uri): IncomingItem? {
        var name: String? = null
        var size = 0L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: return null
        return IncomingItem(uri, finalName, size)
    }

    // ---- 存储权限(与 MainActivity 一致的判定/申请流程) ----

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePermissionThenInit() {
        if (hasStoragePermission()) {
            initPaneIfNeeded()
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

    companion object {
        private const val PANE_TAG = "share_pane"
    }
}
