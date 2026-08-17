package com.twig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.twig.app.ConnectionStore
import com.twig.app.Connections
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.app.formatLocationPath
import com.twig.app.databinding.DialogShareBinding
import com.twig.app.share.Discovery
import com.twig.app.share.ShareConfig
import com.twig.app.share.ShareScope
import com.twig.app.share.ShareStore
import com.twig.app.share.WebShare
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WiFi 共享的两个对话框:配置/状态,以及"扫描附近的 Twig 设备"。
 *
 * 这个功能没有独立页面——开启后真正要看的只有一行地址,而它已经常驻在通知栏里
 * ([com.twig.app.share.ShareService]),再为它开一个 Activity 只是多一层壳。
 */
object ShareDialogs {

    /**
     * 配置 / 状态对话框。
     *
     * @param dir 要共享的目录——调用方给的就是**当前绿框选中的那个目录**
     *   ([com.twig.app.ui.PaneViewModel.currentDir]),或长按菜单点中的目录。
     *   为 null(树上什么都没选)时只剩"所有来源"。
     */
    fun show(act: AppCompatActivity, dir: XFile? = null) {
        val ctx = act
        val saved = ShareStore.load(ctx)
        val running = WebShare.session

        val b = DialogShareBinding.inflate(act.layoutInflater)

        // "指定目录"就是调用方给的那个(当前绿框 / 长按的目录);没有的话回退到上次存的。
        // **不在框里另做目录选择器**:入口本来就长在文件树上,用户点开对话框之前
        // 已经用绿框指定过位置了,再让他在一个小对话框里重走一遍目录树是多余的一步。
        val dirScope: ShareScope.Dir? = when {
            dir != null -> scopeOf(ctx, dir)
            else -> saved.scope as? ShareScope.Dir
        }

        if (running != null) {
            b.boxRunning.visibility = View.VISIBLE
            b.boxForm.visibility = View.GONE
            val urls = running.urls()
            b.tvAddress.text = urls.firstOrNull() ?: running.primaryUrl()
            if (urls.size > 1) {
                b.tvAddressMore.visibility = View.VISIBLE
                b.tvAddressMore.text = ctx.getString(
                    R.string.share_address_more, urls.drop(1).joinToString("  "),
                )
            }
            val mode = ctx.getString(
                if (running.cfg.readOnly) R.string.share_mode_readonly else R.string.share_mode_writable,
            )
            val auth = ctx.getString(
                if (running.cfg.needsAuth) {
                    R.string.share_auth_on
                } else {
                    R.string.share_auth_off
                },
            )
            b.tvStatus.text = ctx.getString(R.string.share_status, running.scopeLabel, mode, auth)
            if (!com.twig.app.share.Net.onWifi()) {
                b.tvWarn.visibility = View.VISIBLE
                b.tvWarn.text = ctx.getString(R.string.share_no_wifi)
            }
            b.btnBattery.visibility = if (batteryOptimized(ctx)) View.VISIBLE else View.GONE
            b.btnBattery.setOnClickListener { openBatterySettings(ctx) }
        } else {
            b.boxRunning.visibility = View.GONE
            b.boxForm.visibility = View.VISIBLE
            b.etPort.setText(saved.port.toString())
            b.etUser.setText(saved.user)
            b.etPass.setText(saved.password)
            b.etName.setText(saved.deviceName)
            b.swReadonly.isChecked = saved.readOnly

            if (dirScope == null) {
                b.rbDir.visibility = View.GONE
                b.tvDirPath.visibility = View.GONE
                b.rbAll.isChecked = true
            } else {
                b.rbDir.visibility = View.VISIBLE
                b.tvDirPath.visibility = View.VISIBLE
                b.tvDirPath.text = scopePath(ctx, dirScope)
                // 路径那一行点着也算选中这一项——它在视觉上就是同一项的第二行
                b.tvDirPath.setOnClickListener { b.rbDir.isChecked = true }
                // 带着目录进来的默认就共享它;没带(从上次配置恢复的)才跟上次的选择走
                val useDir = dir != null || saved.scope is ShareScope.Dir
                b.rbDir.isChecked = useDir
                b.rbAll.isChecked = !useDir
            }
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.share_title)
            .setView(b.root)
            .setNegativeButton(R.string.dialog_close, null)
            .setPositiveButton(
                if (running != null) R.string.share_stop else R.string.share_start,
                null, // 自己接管点击,免得校验没过也把框关掉
            )
            .setNeutralButton(
                if (running != null) R.string.share_copy_address else R.string.share_scan,
                null,
            )
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (running != null) {
                    WebShare.stop(ctx)
                    dialog.dismiss()
                    Toast.makeText(ctx, R.string.share_stopped, Toast.LENGTH_SHORT).show()
                } else {
                    val cfg = readForm(ctx, b, saved, dirScope) ?: return@setOnClickListener
                    dialog.dismiss()
                    startShare(act, cfg, dir)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (running != null) {
                    copyToClipboard(ctx, running.primaryUrl())
                } else {
                    dialog.dismiss()
                    scanAndAdd(act)
                }
            }
        }
        dialog.show()
    }

    /**
     * 指定目录那一行显示的路径,与**最近位置/路径栏同一写法**
     * (`类型:/服务器名/路径`,见 [formatLocationPath]);本地就是绝对路径。
     *
     * 用带别名的那一版而不是 `formatRawLocationPath`:这里是给人看"共享的是哪儿",
     * 用户给服务器起的名字比 IP 好认;要能直接定位的原始地址是收藏行那种场景。
     */
    private fun scopePath(ctx: Context, s: ShareScope.Dir): String {
        val conn = if (s.connLabel.isEmpty()) null else Connections.find(ctx, s.connLabel)
        return formatLocationPath(s.connLabel, s.path, conn)
    }

    private fun scopeOf(ctx: Context, dir: XFile) = ShareScope.Dir(
        scheme = dir.scheme,
        path = dir.path,
        label = dir.name.ifEmpty { dir.path },
        connLabel = ShareStore.connLabelOf(ctx, dir.scheme),
    )

    /** 读表单;端口非法时当场提示并返回 null(调用方据此不关框)。 */
    private fun readForm(
        ctx: Context,
        b: DialogShareBinding,
        saved: ShareConfig,
        dirScope: ShareScope.Dir?,
    ): ShareConfig? {
        val port = b.etPort.text.toString().trim().toIntOrNull()
        if (port == null || port < 1024 || port > 65535) {
            // 1024 以下是特权端口,非 root 的应用进程根本绑不上,与其让它绑定失败
            // 再报一句看不懂的 Permission denied,不如在这儿就说清楚
            Toast.makeText(ctx, R.string.share_bad_port, Toast.LENGTH_LONG).show()
            return null
        }
        val scope = if (b.rbDir.isChecked && dirScope != null) dirScope else ShareScope.AllSources
        return saved.copy(
            scope = scope,
            readOnly = b.swReadonly.isChecked,
            port = port,
            user = b.etUser.text.toString().trim(),
            password = b.etPass.text.toString(),
            deviceName = b.etName.text.toString().trim(),
        )
    }

    /** 起服务:连接可能要重建、绑定可能失败,整段放 IO 线程,结果回主线程说话。 */
    private fun startShare(act: AppCompatActivity, cfg: ShareConfig, dir: XFile?) {
        val ctx = act.applicationContext
        val label = when (val s = cfg.scope) {
            is ShareScope.Dir -> s.label
            ShareScope.AllSources -> act.getString(R.string.share_scope_all)
        }
        ShareStore.save(ctx, cfg)
        act.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WebShare.start(ctx, cfg, label) }
            }
            result.onSuccess {
                // 重新弹一次:这时候是"共享中"那一面,地址就在最上面
                show(act, dir)
            }.onFailure {
                AlertDialog.Builder(act)
                    .setTitle(R.string.share_title)
                    .setMessage(act.getString(R.string.share_failed, it.message ?: it::class.java.simpleName))
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show()
            }
        }
    }

    // ---- 扫描 ----

    /**
     * 扫描局域网里正在共享的 Twig,选中的存成一条 WebDAV 连接。
     *
     * 存成 WebDAV 而不是发明新连接类型:服务端本来就说 WebDAV,现成的
     * [com.twig.fs.network.WebDavFileSystem] 直接就能读写它,树上、复制引擎、
     * 播放器那些通路一行都不用改。
     */
    fun scanAndAdd(act: AppCompatActivity, onAdded: () -> Unit = {}) {
        val progress = AlertDialog.Builder(act)
            .setTitle(R.string.share_scan)
            .setMessage(R.string.share_scanning)
            .setCancelable(true)
            .show()
        act.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { Discovery.scan(act.applicationContext) }
            progress.dismiss()
            if (found.isEmpty()) {
                AlertDialog.Builder(act)
                    .setTitle(R.string.share_scan)
                    .setMessage(R.string.share_scan_none)
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show()
                return@launch
            }
            val labels = found.map { f ->
                val mode = act.getString(
                    if (f.readOnly) R.string.share_mode_readonly else R.string.share_mode_writable,
                )
                "${f.name}\n${f.host}:${f.port} · ${f.scope} · $mode"
            }.toTypedArray()
            AlertDialog.Builder(act)
                .setTitle(R.string.share_scan_found)
                .setItems(labels) { _, w -> addFound(act, found[w], onAdded) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun addFound(act: AppCompatActivity, f: Discovery.Found, onAdded: () -> Unit) {
        if (!f.needsAuth) {
            saveFound(act, f, "", "")
            onAdded()
            return
        }
        // 对面设了密码:现问,存进连接里,以后展开就不用再输
        val box = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, 0)
        }
        val etUser = android.widget.EditText(act).apply {
            hint = act.getString(R.string.share_user)
            setSingleLine()
            setText("twig") // 服务端 user 留空时用的就是这个默认值
        }
        val etPass = android.widget.EditText(act).apply {
            hint = act.getString(R.string.share_password)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(etUser)
        box.addView(etPass)
        AlertDialog.Builder(act)
            .setTitle(f.name)
            .setView(box)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                saveFound(act, f, etUser.text.toString().trim(), etPass.text.toString())
                onAdded()
            }
            .show()
    }

    private fun saveFound(act: AppCompatActivity, f: Discovery.Found, user: String, pass: String) {
        ConnectionStore.save(
            act,
            SavedConnection(
                type = "webdav",
                host = f.url(),
                user = user,
                password = pass,
                name = f.name,
            ),
        )
        Toast.makeText(act, act.getString(R.string.share_scan_added, f.name), Toast.LENGTH_SHORT).show()
    }

    // ---- 杂项 ----

    private fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("twig", text))
        Toast.makeText(ctx, R.string.share_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * 还在被电池优化管着吗。前台服务能挡住大部分回收,但厂商 ROM 的"省电"策略
     * 常常更狠(息屏一段时间后照样冻结后台进程),加白名单才稳。
     */
    private fun batteryOptimized(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * 打开电池优化列表让用户自己把 Twig 设成"不优化"。
     *
     * 故意**不用** `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 那个一步到位的弹框:
     * 它要求声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限,而这个权限在 Google Play
     * 上是受限的(要单独申报),为一个可选的保活提示背上上架风险不划算。
     */
    private fun openBatterySettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
