package com.twig.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.app.StreamProvider
import com.twig.app.TwigApp
import com.twig.app.databinding.ActivityPickerBinding
import com.twig.core.XFile

/**
 * 文件选择器:用 Twig 自己的面板选文件,而不是跳系统「文件」应用。
 *
 * 两个用处,返回值统一是 [StreamProvider] 的 `content://`(自包含 URI,读的时候
 * 才按 scheme 找 FileSystem 流式取):
 * - **对内**:字体 / 配色 / SSH 私钥的导入。好处是能选到 SMB/FTP/WebDAV/压缩包
 *   里的文件——SAF 看不见这些来源;调用方代码不用改,照旧 `openInputStream(uri)`。
 * - **对外**:别的 App 发 `ACTION_GET_CONTENT` 时把 Twig 列进选择器。结果 URI 带
 *   `FLAG_GRANT_READ_URI_PERMISSION`,系统在回传时授予调用方临时读权限
 *   (StreamProvider 是 `exported=false`,只能靠这条授权访问)。
 *
 * 单选时点文件即返回;`EXTRA_ALLOW_MULTIPLE` 时改成勾选若干项再按「确定」,
 * 多个结果按约定放进 ClipData。
 */
class PickerActivity : AppCompatActivity(), PaneFragment.Host {

    private lateinit var b: ActivityPickerBinding
    private var allowMultiple = false

    /**
     * true = 回传选中文件的 scheme+path 而不是 content://。给「在这台服务器上挑个
     * 脚本」这类用途:调用方要的是远端路径本身(拿去拼命令行),不是可读的字节流。
     */
    private var returnPath = false

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
        allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        returnPath = intent.getBooleanExtra(EXTRA_RETURN_PATH, false)

        b = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.picker_title)
        b.toolbar.setNavigationOnClickListener { cancel() }
        b.tvSummary.text = getString(
            if (allowMultiple) R.string.picker_hint_multi else R.string.picker_hint_single,
        )

        TwigApp.registerBaseFs(this)

        // 选文件场景用不到改动类操作,只留导航
        b.strip.sizeIconsLikeRows(this)
        b.strip.asSort.visibility = View.GONE
        b.strip.asCopy.visibility = View.GONE
        b.strip.asMove.visibility = View.GONE
        b.strip.asRename.visibility = View.GONE
        b.strip.asDelete.visibility = View.GONE
        b.strip.asCompare.visibility = View.GONE
        b.strip.asMap.visibility = View.GONE
        b.strip.asNewFolder.visibility = View.GONE
        b.strip.asUp.setOnClickListener { pane()?.actionUp() }
        b.strip.asRefresh.setOnClickListener { pane()?.actionRefresh() }

        b.btnCancel.setOnClickListener { cancel() }
        // 单选靠点文件返回,「确定」只在多选时有意义
        b.btnConfirm.visibility = if (allowMultiple) View.VISIBLE else View.GONE
        b.btnConfirm.setOnClickListener { confirmMultiple() }

        ensurePermissionThenInit()
    }

    private fun pane(): PaneFragment? =
        supportFragmentManager.findFragmentByTag(PANE_TAG) as? PaneFragment

    private fun initPaneIfNeeded() {
        if (supportFragmentManager.findFragmentByTag(PANE_TAG) != null) return
        val lock = intent.getStringExtra(EXTRA_START_SCHEME)
        // 指定了来源就用锁定面板:树上只有这台服务器,别的地方压根看不到也选不到
        val fragment = if (lock != null) {
            PaneFragment.locked(
                lock,
                intent.getStringExtra(EXTRA_START_PATH).orEmpty(),
                intent.getStringExtra(EXTRA_START_LABEL),
            )
        } else {
            PaneFragment.newInstance(0)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.pane, fragment, PANE_TAG)
            .commitNow()
    }

    // ---- 选中 ----

    /** 单选:点文件即返回;多选:交回 false 让它照常打开(勾选走「确定」)。 */
    override fun onPickFile(file: XFile): Boolean {
        if (allowMultiple) return false
        if (file.isDir) return false
        // 锁定来源时的兜底:树上本来就只有那台服务器,但压缩包展开等路径会带出
        // 别的 scheme,拦下比回传一个调用方用不了的路径好
        val lock = intent.getStringExtra(EXTRA_START_SCHEME)
        if (lock != null && file.scheme != lock) {
            Toast.makeText(this, R.string.picker_wrong_source, Toast.LENGTH_SHORT).show()
            return true
        }
        deliver(listOf(file))
        return true
    }

    private fun confirmMultiple() {
        val files = pane()?.checkedFiles()?.filter { !it.isDir }.orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.picker_none_checked, Toast.LENGTH_SHORT).show()
            return
        }
        deliver(files)
    }

    /**
     * 回传结果。单个走 `data`,多个走 ClipData(GET_CONTENT 多选的标准形状);
     * 两种都要带 [Intent.FLAG_GRANT_READ_URI_PERMISSION] —— StreamProvider 不导出,
     * 调用方只能靠这次授权读到内容。
     */
    private fun deliver(files: List<XFile>) {
        if (returnPath) {
            val f = files.first()
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_PICKED_SCHEME, f.scheme)
                    .putExtra(EXTRA_PICKED_PATH, f.path)
                    .putExtra(EXTRA_PICKED_NAME, f.name),
            )
            finish()
            return
        }
        val uris = files.map { StreamProvider.uriFor(this, it) }
        val data = Intent().apply {
            if (uris.size == 1) {
                setData(uris[0])
            } else {
                clipData = android.content.ClipData.newUri(contentResolver, "twig", uris[0]).also { clip ->
                    for (u in uris.drop(1)) clip.addItem(android.content.ClipData.Item(u))
                }
                setData(uris[0]) // 只认 data 的老调用方至少能拿到第一个
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 面板先吃返回键(退出占用图/回上级);它不接就是取消选择
        if (pane()?.handleBack() != true) cancel()
    }

    // ---- PaneFragment.Host:单面板,其余回调无意义 ----

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

    // ---- 存储权限(与 MainActivity / ShareTargetActivity 同一套判定) ----

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
                .setNegativeButton(R.string.dialog_cancel) { _, _ -> cancel() }
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
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> cancel() }
            .show()
    }

    companion object {
        private const val PANE_TAG = "picker_pane"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_RETURN_PATH = "return_path"
        private const val EXTRA_START_SCHEME = "start_scheme"
        private const val EXTRA_START_PATH = "start_path"
        private const val EXTRA_START_LABEL = "start_label"
        const val EXTRA_PICKED_SCHEME = "picked_scheme"
        const val EXTRA_PICKED_PATH = "picked_path"
        const val EXTRA_PICKED_NAME = "picked_name"

        /** 应用内选文件:返回的 content:// 用 contentResolver 照常读。 */
        fun intent(ctx: Context, title: String? = null): Intent =
            Intent(ctx, PickerActivity::class.java).putExtra(EXTRA_TITLE, title)

        /**
         * 选远端路径:从 [startScheme]/[startPath] 展开,结果放
         * [EXTRA_PICKED_SCHEME]/[EXTRA_PICKED_PATH]/[EXTRA_PICKED_NAME]。
         */
        fun pathIntent(
            ctx: Context,
            title: String?,
            startScheme: String,
            startPath: String,
            startLabel: String? = null,
        ): Intent =
            intent(ctx, title)
                .putExtra(EXTRA_RETURN_PATH, true)
                .putExtra(EXTRA_START_SCHEME, startScheme)
                .putExtra(EXTRA_START_PATH, startPath)
                .putExtra(EXTRA_START_LABEL, startLabel)
    }
}
