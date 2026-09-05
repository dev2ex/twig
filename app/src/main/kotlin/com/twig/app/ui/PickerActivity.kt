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
 * File picker: pick files via Twig's own panel rather than jumping to the system "Files" app.
 *
 * Two uses; the return value is uniformly a [StreamProvider] `content://` (self-contained URI;
 * at read time it looks up the FileSystem by scheme and streams the bytes):
 * - **Internal**: importing fonts / color schemes / SSH private keys. The benefit is being able
 *   to pick files inside SMB/FTP/WebDAV/archives — SAF can't see those sources; the caller's
 *   code doesn't change, still `openInputStream(uri)`.
 * - **External**: when another app sends `ACTION_GET_CONTENT`, list Twig in the chooser. The
 *   returned URI carries `FLAG_GRANT_READ_URI_PERMISSION`, the system grants the caller a
 *   temporary read permission on return (StreamProvider is `exported=false`, only reachable via
 *   this grant).
 *
 * Single-select: tapping a file returns it; with `EXTRA_ALLOW_MULTIPLE` it becomes "check a few,
 * then confirm"; multiple results go into ClipData as per the convention.
 */
class PickerActivity : AppCompatActivity(), PaneFragment.Host {

    private lateinit var b: ActivityPickerBinding
    private var allowMultiple = false
    private var pickDir = false

    /**
     * true = return the selected file's scheme+path instead of content://. For use cases like
     * "pick a script on this server": the caller wants the remote path itself (to build a command
     * line), not a readable byte stream.
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
        pickDir = intent.getBooleanExtra(EXTRA_PICK_DIR, false)

        b = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.picker_title)
        b.toolbar.setNavigationOnClickListener { cancel() }
        b.tvSummary.text = getString(
            when {
                pickDir -> R.string.picker_hint_dir
                allowMultiple -> R.string.picker_hint_multi
                else -> R.string.picker_hint_single
            },
        )

        TwigApp.registerBaseFs(this)

        // File-picking scenarios don't need modification actions, only navigation
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
        // Single-select returns on file tap; "Confirm" only matters for multi-select and directory-pick
        b.btnConfirm.visibility = if (allowMultiple || pickDir) View.VISIBLE else View.GONE
        b.btnConfirm.setOnClickListener { if (pickDir) confirmDir() else confirmMultiple() }

        SecurityUi.gate(this) { ensurePermissionThenInit() }
    }

    private fun pane(): PaneFragment? =
        supportFragmentManager.findFragmentByTag(PANE_TAG) as? PaneFragment

    private fun initPaneIfNeeded() {
        if (supportFragmentManager.findFragmentByTag(PANE_TAG) != null) return
        val lock = intent.getStringExtra(EXTRA_START_SCHEME)
        // When a source is specified, use a locked panel: the tree only contains that server,
        // so other places aren't even visible or selectable
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
            .commitNow() // synchronous commit; we need the viewModel immediately below
        if (pickDir) pane()?.let { enableOnWritableDir(it, b.btnConfirm) }
    }

    // ---- Selection ----

    /** Single-select: tap a file to return; multi-select: return false to let it open normally (checkboxes go through "Confirm"). */
    override fun onPickFile(file: XFile): Boolean {
        if (allowMultiple || pickDir) return false
        if (file.isDir) return false
        // Locked-source fallback: the tree only has that server, but expanding paths like
        // archives can bring in other schemes; blocking it is better than returning a path the
        // caller can't use
        val lock = intent.getStringExtra(EXTRA_START_SCHEME)
        if (lock != null && file.scheme != lock) {
            Toast.makeText(this, R.string.picker_wrong_source, Toast.LENGTH_SHORT).show()
            return true
        }
        deliver(listOf(file))
        return true
    }

    /**
     * Return the directory highlighted with the green box.
     *
     * Use `currentDir` rather than checked items: the tree "expands in place", and expanding a
     * directory already moves the green box there (see `PaneViewModel.toggleFile`), so "where
     * I am right now" is already specified by the user tapping in; requiring an extra checkbox
     * tap adds a step. Same idea as [ShareTargetActivity]'s "copy to here".
     */
    private fun confirmDir() {
        // The button is gated by enableOnWritableDir, can't be tapped on a non-writable dir —
        // this is just a fallback
        val dir = pane()?.viewModel?.currentDir ?: return
        deliver(listOf(dir))
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
     * Return the result. Single goes via `data`; multiple via ClipData (the standard shape for
     * GET_CONTENT multi-select); both must carry [Intent.FLAG_GRANT_READ_URI_PERMISSION] —
     * StreamProvider is not exported, the caller can only read the contents through this grant.
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
                setData(uris[0]) // legacy callers that only read data can at least get the first one
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
        // The panel consumes the back key first (exit occupancy image / go up); if it doesn't, that's cancellation
        if (pane()?.handleBack() != true) cancel()
    }

    // ---- PaneFragment.Host: single pane; the other callbacks are meaningless ----

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

    // ---- Storage permissions (same decision logic as MainActivity / ShareTargetActivity) ----

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
        private const val EXTRA_PICK_DIR = "pick_dir"
        const val EXTRA_PICKED_SCHEME = "picked_scheme"
        const val EXTRA_PICKED_PATH = "picked_path"
        const val EXTRA_PICKED_NAME = "picked_name"

        /** Pick a file inside the app: read the returned content:// via contentResolver as usual. */
        fun intent(ctx: Context, title: String? = null): Intent =
            Intent(ctx, PickerActivity::class.java).putExtra(EXTRA_TITLE, title)

        /**
         * Pick a remote path: expand from [startScheme]/[startPath]; the result goes into
         * [EXTRA_PICKED_SCHEME]/[EXTRA_PICKED_PATH]/[EXTRA_PICKED_NAME].
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

        /**
         * Pick a **file** but return its path (instead of content://), and don't lock the source.
         * For use cases where "this file is read by Twig itself" — via `FileSystem.openInput`,
         * the remote file doesn't need to be materialized first.
         */
        fun pathIntentAny(ctx: Context, title: String?): Intent =
            intent(ctx, title).putExtra(EXTRA_RETURN_PATH, true)

        /**
         * Pick a **directory**: "Confirm" returns the directory highlighted with the green box;
         * the result is the same as [pathIntent].
         *
         * Doesn't lock the source — this way "where to save" matches what's reachable on the
         * tree, so backups can land directly on SMB/WebDAV without first saving locally and
         * copying.
         */
        fun dirIntent(ctx: Context, title: String?): Intent =
            intent(ctx, title)
                .putExtra(EXTRA_RETURN_PATH, true)
                .putExtra(EXTRA_PICK_DIR, true)
    }
}
