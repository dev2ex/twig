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
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.SavedConnection
import com.twig.app.ShareSourceFileSystem
import com.twig.app.databinding.ActivityShareTargetBinding
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isWritableDir

/**
 * Receives a share from another app (image / arbitrary file) and shows the
 * directory-picker UI ("Copy to"): it reuses the same [PaneFragment] tree
 * navigation as the main screen, the inbound content:// URI is wrapped into an
 * XFile via [ShareSourceFileSystem] and streamed by [CopyEngine] into the chosen
 * destination directory — no new copy logic, just one new read-only source.
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

        // Keep only navigation actions; copy/move/rename/delete/treemap aren't needed in
// the "pick a destination directory" scenario.
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

        SecurityUi.gate(this) { ensurePermissionThenInit() }
    }

    private fun pane(): PaneFragment? = supportFragmentManager.findFragmentByTag(PANE_TAG) as? PaneFragment

    private fun initPaneIfNeeded() {
        if (supportFragmentManager.findFragmentByTag(PANE_TAG) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.pane, PaneFragment.newInstance(0), PANE_TAG)
                .commitNow() // Commit synchronously — the very next lines read viewModel and observe currentDir.
        }
        observeCurrentDir()
    }

    /** The "Copy here" button is only enabled when the selected directory actually exists and is writable; see [enableOnWritableDir]. */
    private fun observeCurrentDir() {
        pane()?.let { enableOnWritableDir(it, b.btnConfirm) }
    }

    // ---- PaneFragment.Host: single-pane scenario, most callbacks are no-ops ----

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

    // ---- Confirm: copy to the currently selected directory ----

    private fun onConfirm() {
        val dest = pane()?.viewModel?.currentDir
        if (dest?.isWritableDir() != true) {
            Toast.makeText(this, getString(R.string.msg_pick_dir_first), Toast.LENGTH_SHORT).show()
            return
        }
        startCopy(dest)
    }

    /**
     * Same as the main screen: the work goes to [Transfers]' background session
     * (foreground service + notification progress), and the progress dialog /
     * conflict dialog are surfaced by the embedded [PaneFragment]
     * ([PaneFragment.showTransferBox]).
     *
     * ★ The `content://` read permission from an inbound share follows **this task
     * stack**: while the task is alive, the background copy can still read; if the
     * user swipes this card off the recents list, the permission goes away with
     * the process, and the in-flight copy is gone too (same as before the change).
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

    /** Wrap up on successful copy (cancel/failure stay in place so the user can see them and retry). */
    override fun onTransferFinished(session: Transfers.Session) {
        if (session.finished?.isSuccess == true && !session.cancelled.get()) finish()
    }

    /** Written the same way as the panel's path bar (network sources carry the server name); falls back to the no-server-name form when the panel isn't ready yet. */
    private fun pathLabel(f: XFile): String = pane()?.displayPath(f) ?: when {
        f.scheme == "file" -> f.path
        f.scheme == "saf" -> f.name
        else -> "${Format.schemeLabel(f.scheme)}:${f.path}"
    }

    // ---- Resolve the share payload ----

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

    // ---- Storage permission (same check / request flow as MainActivity) ----

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
