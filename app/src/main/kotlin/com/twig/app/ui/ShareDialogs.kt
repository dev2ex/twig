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
 * The two dialogs for WiFi sharing: configuration/status, and "scan nearby Twig
 * devices".
 *
 * There is no dedicated page for this feature — once enabled, the only thing you
 * actually want to see is one line of address, which is already pinned to the
 * notification shade ([com.twig.app.share.ShareService]); spinning up another
 * Activity for it would be just another shell.
 */
object ShareDialogs {

    /**
     * Configuration / status dialog.
     *
     * @param dir the directory to share — what the caller passes is exactly the
     *   currently green-highlighted directory
     *   ([com.twig.app.ui.PaneViewModel.currentDir]), or the directory picked from
     *   the long-press menu. When null (nothing selected in the tree) only
     *   "all sources" remains.
     */
    fun show(act: AppCompatActivity, dir: XFile? = null) {
        val ctx = act
        val saved = ShareStore.load(ctx)
        val running = WebShare.session

        val b = DialogShareBinding.inflate(act.layoutInflater)

        // "Specify directory" is whatever the caller passed (the current green highlight
        // / the long-press menu's directory); when that's absent, fall back to the
        // last saved one.
        // **Do not build another directory picker inside the dialog**: the entry point
        // is already attached to the file tree, the user has already chosen the location
        // with the green highlight before opening the dialog, and making them walk the
        // tree again in a small dialog is one extra step too many.
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
                // Tapping the path line also selects this radio entry — visually it is the
                // same row's second line.
                b.tvDirPath.setOnClickListener { b.rbDir.isChecked = true }
                // Coming in with a directory, default to sharing it; only when not
                // provided (restored from the previous config) do we follow the
                // previous choice.
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
                null, // We take over the click ourselves, so a validation failure doesn't still dismiss the dialog.
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
     * Path shown on the "specify directory" row, written the same way as the recent
     * location / path bar (`scheme:/server name/path`, see [formatLocationPath]);
     * for local it's just the absolute path.
     *
     * We use the alias version rather than `formatRawLocationPath`: this row tells
     * humans "what am I sharing", and the user-set server name reads better than
     * an IP; the raw address that can be navigated to directly is the favorites
     * row's use case.
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

    /** Read the form; if the port is invalid, show a toast right here and return null (so the caller knows not to close the dialog). */
    private fun readForm(
        ctx: Context,
        b: DialogShareBinding,
        saved: ShareConfig,
        dirScope: ShareScope.Dir?,
    ): ShareConfig? {
        val port = b.etPort.text.toString().trim().toIntOrNull()
        if (port == null || port < 1024 || port > 65535) {
            // Below 1024 is privileged; a non-root app process cannot bind there. Rather
            // than letting the bind fail and report an opaque "Permission denied", say so
            // right here.
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

    /** Start the service: the connection may need re-establishment, the bind may fail — the whole thing goes on an IO thread, and the result is delivered back on the main thread. */
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
                // Re-open: this shows the "Sharing" face, with the address right at the top.
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

    // ---- Scan ----

    /**
     * Scan for Twig instances sharing over the LAN, and save the chosen one as a
     * WebDAV connection.
     *
     * Saved as WebDAV rather than inventing a new connection type: the server speaks
     * WebDAV natively, the existing [com.twig.fs.network.WebDavFileSystem] already
     * reads and writes it, and not a single line in the tree, copy engine or player
     * needs to change.
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
        // The other side set a password: ask now and persist into the connection, so
        // future expansions don't need to re-enter it.
        val box = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, 0)
        }
        val etUser = android.widget.EditText(act).apply {
            hint = act.getString(R.string.share_user)
            setSingleLine()
            setText("twig") // This is the default the server uses when its user is left empty.
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

    // ---- Misc ----

    private fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("twig", text))
        Toast.makeText(ctx, R.string.share_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Whether we're still under battery optimization. A foreground service can keep
     * us out of most reaping, but vendor ROMs' "battery saver" strategies are often
     * harsher (freezing background processes after the screen is off for a while),
     * so getting onto the whitelist is what makes this steady.
     */
    private fun batteryOptimized(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * Open the battery optimization list and let the user set Twig to "don't optimize".
     *
     * Deliberately **don't** use the one-shot dialog at
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: that path requires the
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which on Google Play is
     * restricted (needs a separate declaration), and the listing risk isn't worth
     * carrying just to keep an optional keep-alive prompt alive.
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
