package com.twig.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.twig.app.ui.ShortcutIcons
import androidx.core.content.pm.ShortcutManagerCompat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * X-plore-style dual pane:
 * - Portrait: single pane full-screen, action strip on the "opposite" side (left pane → right column,
 *   right pane → left column), swipe horizontally to switch panes;
 * - Landscape: two panes side-by-side, action strip in the middle, touch determines the active pane.
 */
class MainActivity : AppCompatActivity(), PaneFragment.Host {

    private lateinit var b: ActivityMainBinding
    private var activeIndex = 0
    /** Snapshot of layout-affecting preferences; when changed in onResume (after settings edit), recreate. */
    private var uiSig = ""

    companion object {
        /** Width (dp) of one action-strip column; matches the include's default width in activity_main.xml */
        private const val STRIP_COL_DP = 52

        /** Disabled-state alpha: visible but clearly grey, so users don't think the button has disappeared. */
        private const val DISABLED_ALPHA = 0.35f

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

        /** Interval (ms) for polling removable volumes while in the foreground (see [pollVolumes]). */
        private const val VOLUME_POLL_MS = 3000L


        /**
         * "Open with Twig" for an archive ([ui.ViewIntentActivity]): mount it at the top of
         * the current pane as a row that expands in place. ★ Don't add FLAG_ACTIVITY_NEW_TASK
         * — the temporary read grant on a content:// follows the receiving task stack, so
         * a new stack wouldn't be able to read it (see ViewIntentActivity's class comment).
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
         * Jump to the file manager and locate a directory in the tree (used by the music
         * player page / listing page's "go to containing directory").
         * When [file] is given (a full path on the same scheme as [path]), scroll to that
         * row after expanding so "go to containing directory" lands you directly on the file
         * rather than stopping at the directory.
         */
        fun revealIntent(ctx: Context, scheme: String, path: String, file: String? = null): Intent =
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_REVEAL_SCHEME, scheme)
                putExtra(EXTRA_REVEAL_PATH, path)
                file?.let { putExtra(EXTRA_REVEAL_FILE, it) }
            }

        /**
         * Tap the transfer progress notification: return to the file manager and re-attach
         * the progress dialog (see [ui.TransferService]). NEW_TASK is needed because a
         * notification launch has no task stack.
         */
        fun transferIntent(ctx: Context): Intent =
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SHOW_TRANSFER, true)
            }

        /** Tap the "Sharing" notification: return to the file manager and pop up the share status dialog (address / stop). */
        fun shareIntent(ctx: Context): Intent =
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SHOW_SHARE, true)
            }
    }

    // Pending target directory: when the intent carries scheme+path, record it first, then expand once the pane is ready (may have to wait for permission / async init)
    private var pendingReveal: XFile? = null
    /** File inside the pending target to scroll to (optional, see [revealIntent]). */
    private var pendingRevealFile: XFile? = null
    /** External archive waiting to mount (see [mountIntent]); same — wait until the pane is ready before mounting. */
    private var pendingMount: XFile? = null
    /** Returning from a notification tap: re-attach the transfer progress dialog once the pane is ready (see [transferIntent]). */
    private var pendingShowTransfer = false

    private val legacyPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) initPanesIfNeeded() else showPermissionRationale()
        }

    private val manageAllFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (hasStoragePermission()) initPanesIfNeeded() else showPermissionRationale()
        }

    /** SFTP private-key picker: after SAF selection, copy the file into the app's private dir (so cross-app permissions don't expire) and fill the path back into the dialog. */
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
        com.twig.app.ui.NavBarTint.surface(this) // tint the nav bar to the pane's background, no longer a black strip at the bottom
        setSupportActionBar(b.toolbar)
        // Portrait's "up" on the left of the title bar: same action as the strip's as_up
        // (landscape has no Toolbar, see applyLayoutMode) — placed here because one-handed
        // use reaches the top-left more easily than the right-side action strip
        b.toolbar.setNavigationIcon(R.drawable.ic_up)
        b.toolbar.setNavigationContentDescription(R.string.strip_up)
        b.toolbar.setNavigationOnClickListener { activePane()?.actionUp() }

        TwigApp.registerBaseFs(this) // Base sources (local / archive / SAF / apps / content://)

        uiSig = Prefs.uiSignature(this)
        wireStrip(b.stripLeft)
        wireStrip(b.stripMid)
        wireStrip(b.stripRight)
        syncShareIcon()
        installClipboardBar()
        applyLayoutMode()
        applyFullscreen()

        // Desktop long-press menu: re-publish every time we enter the main UI, so language changes follow too (see Shortcuts)
        com.twig.app.ui.Shortcuts.publish(this)

        readRevealExtras(intent)
        readMountExtras(intent)
        readTransferExtra(intent)
        readShareExtra(intent)
        // ★ Unlocking must happen before the panes initialise: as soon as the panes exist, they
        // reconnect to the server last expanded (via "remember last location") — if still
        // locked, the "password" read back then is still ciphertext, and the resulting
        // FileSystem instance keeps carrying it (see that note in Connections.ensure).
        com.twig.app.ui.SecurityUi.gate(this) { ensurePermissionThenInit() }
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
     * Returning from the "Sharing" notification: directly pop the status dialog. Unlike
     * the transfer progress dialog, this doesn't wait for pane readiness — the share
     * dialog depends on no pane state; the post() is just to avoid show()ing a window
     * inside onCreate.
     */
    private fun readShareExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_SHARE, false) != true) return
        intent.removeExtra(EXTRA_SHOW_SHARE) // don't let the post-config-change recreate pop it again
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
        if (activePane() == null) return // pane not ready yet (permission / init not done); try again after initPanesIfNeeded
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
        if (activePane()?.handleBack() == true) return // inside a takeover view: go up a level / exit the takeover view
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen() // the system may restore the status bar after switching; re-apply
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, volumeReceiver, volumeFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        // After locking and switching back (or locking elsewhere) must re-enter — onCreate only handles cold start
        if (com.twig.app.secure.Secrets.locked(this)) {
            com.twig.app.ui.SecurityUi.gate(this) { }
            return
        }
        // Returning from settings: if layout-affecting prefs changed (row height / thumbnails etc.), recreate
        if (uiSig.isNotEmpty() && uiSig != Prefs.uiSignature(this)) {
            uiSig = Prefs.uiSignature(this)
            recreate()
            return
        }
        // A transfer finished while the UI wasn't around: come back and catch up (clear selection / refresh both sides / announce result).
        // Don't consume if the pane isn't ready — taken too early nothing will handle it, defer to next onResume
        activePane()?.let { p -> com.twig.app.ui.Transfers.consumeFinished()?.let { p.finishTransfer(it) } }
        invalidateOptionsMenu() // terminal sessions may have been added/removed in another Activity; refresh the entry's visibility
        val land = isLandscape()
        for (s in listOf(b.stripMid, b.stripLeft, b.stripRight)) applyStripTop(s, land)
        // Sharing may have been turned off via the notification bar's "stop" button (the UI wasn't even present); sync once on returning to foreground
        syncShareIcon()
        WebShare.onStateChanged = { runOnUiThread { syncShareIcon() } }
        // SD cards / USB drives are usually inserted/removed while the app isn't in the foreground; rescan on return — only refresh the tree if something changed
        rescanVolumes()
        pollVolumes()
    }

    override fun onPause() {
        super.onPause()
        // Drop the callback: it captures this Activity, leaving it in place is a reference to an already-destroyed UI
        WebShare.onStateChanged = null
        runCatching { unregisterReceiver(volumeReceiver) }
    }

    /** Rescan removable volumes, refresh the volume row on both trees when something changed. One binder IPC, done on the main thread. */
    private fun rescanVolumes() {
        if (StorageVolumes.refresh(this)) refreshTrees()
    }

    /** Plug/unplug while in foreground: refresh the volume row on both trees when something changed.
     *  ★ Registration goes at the very top of onResume: several paths below early-return (locked / recreate on layout change) but still walk into onPause, so a missed registration would unregister a never-registered receiver there. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = rescanVolumes()
    }

    /**
     * When in the foreground, poll removable volumes every few seconds so a freshly
     * plugged-in USB drive / SD card shows up without the user leaving and returning.
     *
     * ★ **Polling is the only reliable option**: in testing (Sony Android 16 with a USB
     * drive), the system mounted the volume as "invisible to the app", neither firing
     * ACTION_MEDIA_MOUNTED nor calling `StorageManager.StorageVolumeCallback` — both
     * event paths were tried, none fired once, while `getStorageVolumes()` does return
     * that volume. So those two channels stay to cover ordinary SD cards; the actual
     * fallback for "plug-in shows up immediately" is here.
     * Cost is one binder IPC, and it auto-stops outside RESUMED.
     */
    private fun pollVolumes() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(VOLUME_POLL_MS)
                    rescanVolumes()
                }
            }
        }
    }

    /** ★ `addDataScheme("file")` cannot be omitted — these broadcasts all carry file:// data; without it, none of them arrive. */
    private fun volumeFilter() = IntentFilter().apply {
        addAction(Intent.ACTION_MEDIA_MOUNTED)
        addAction(Intent.ACTION_MEDIA_UNMOUNTED)
        addAction(Intent.ACTION_MEDIA_EJECT)
        addAction(Intent.ACTION_MEDIA_REMOVED)
        addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
        addDataScheme("file")
    }

    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (Prefs.fullscreen(this)) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars()) // hide only the status bar
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    // ---- Side action row ----

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
        // Shared scope defaults to the green-highlighted directory ([PaneViewModel.currentDir]),
        // the dialog no longer offers a separate directory picker — the entry point is
        // next to the file tree, and the location was already chosen before the dialog opened
        s.asShare.setOnClickListener {
            ShareDialogs.show(this, activePane()?.viewModel?.currentDir)
        }

        // Top pinned row (only shown in landscape when there's no Toolbar): the entries that
        // used to live on the right of the title bar
        s.asTerm.setOnClickListener { TerminalActivity.resume(this) }
        s.asMusic.setOnClickListener {
            startActivity(Intent(this, com.twig.app.ui.MusicPlayerActivity::class.java))
        }
        s.asSwap.setOnClickListener { setActiveIndex(1 - activeIndex) }
        s.asMenu.setOnClickListener { showStripOverflow(it) }
    }

    /** Landscape has no Toolbar; the overflow menu becomes a PopupMenu anchored on the action strip's "menu" button (items and handling both reuse R.menu.main). */
    private fun showStripOverflow(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.inflate(R.menu.main)
        syncMenuChecks(popup.menu)
        popup.setOnMenuItemClickListener { onOptionsItemSelected(it) }
        popup.show()
    }

    /**
     * Top pinned row visibility and state: only appears in landscape after the Toolbar is
     * hidden; the terminal entry appears only when there are sessions; the pane-swap icon
     * flips direction with the active pane (matching the one on the Toolbar).
     */
    /**
     * When sharing is on, swap the action strip's icon to a filled fan.
     *
     * Sharing can stay on for hours unattended, and a notification alone is easy to forget —
     * the main UI needs a status that's visible at a glance. All three action strips
     * (landscape middle + portrait left/right) need to be refreshed.
     */
    private fun syncShareIcon() {
        val res = if (WebShare.isRunning) R.drawable.ic_share_wifi_on else R.drawable.ic_share_wifi
        for (s in listOf(b.stripMid, b.stripLeft, b.stripRight)) s.asShareIcon.setImageResource(res)
    }

    private fun applyStripTop(s: ActionStripBinding, land: Boolean) {
        s.asTop.visibility = if (land) View.VISIBLE else View.GONE
        s.asTopDivider.visibility = if (land) View.VISIBLE else View.GONE
        if (!land) return
        // GridLayout reserves a cell for GONE children, so the terminal entry is removed/added in full rather than just set to GONE
        val wantTerm = TermManager.list().isNotEmpty()
        val hasTerm = s.asTerm.parent != null
        if (wantTerm && !hasTerm) s.asTop.addView(s.asTerm, 0)
        else if (!wantTerm && hasTerm) s.asTop.removeView(s.asTerm)
        s.asSwapIcon.setImageResource(
            if (activeIndex == 0) R.drawable.ic_pane_to_right else R.drawable.ic_pane_to_left,
        )
    }

    // ---- Clipboard bar (spans the whole window, doesn't belong to either pane) ----

    /**
     * Content and "Move" check come from the global [FileClipboard]; **the paste target is
     * the active pane's current directory** — the bar spans the area beneath both panes,
     * so switching the pane switches the target, no need for one bar per side.
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
        // Paste no longer has a confirmation dialog, this line is the only landing-point hint; when it can't land, write the reason here and grey out the button
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
        // Don't write back when the value is the same; avoids bouncing with setOnCheckedChangeListener
        if (b.cbClipMove.isChecked != s.move) b.cbClipMove.isChecked = s.move
        // Sources that can't be moved (the document tree root grant, "Apps" entries) don't get a "Move" toggle; FileClipboard.put already judged this, here it's just shown on the bar
        val movable = FileClipboard.movable(s.items)
        b.cbClipMove.isEnabled = movable
        b.cbClipMove.alpha = if (movable) 1f else DISABLED_ALPHA
    }

    /** Tap the content summary on the bar: list every entry to confirm exactly what was put in. */
    private fun showClipContents() {
        val items = FileClipboard.items
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.clip_title)
            .setItems(items.map { it.toUri() }.toTypedArray(), null)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    // ---- Layout mode ----

    private fun isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyLayoutMode() {
        val land = isLandscape()
        b.paneA.visibility = if (land || activeIndex == 0) View.VISIBLE else View.GONE
        b.paneB.visibility = if (land || activeIndex == 1) View.VISIBLE else View.GONE
        b.stripMid.root.visibility = if (land) View.VISIBLE else View.GONE
        b.stripLeft.root.visibility = if (!land && activeIndex == 1) View.VISIBLE else View.GONE
        b.stripRight.root.visibility = if (!land && activeIndex == 0) View.VISIBLE else View.GONE
        // Landscape has no Toolbar (vertical space is precious); its right-side buttons move into the strip's top pinned row
        b.toolbar.visibility = if (land) View.GONE else View.VISIBLE
        // Landscape's height can't fit a full single column, so the strip becomes two columns (width doubles)
        for (s in listOf(b.stripMid, b.stripLeft, b.stripRight)) {
            applyStripColumns(s, land)
            applyStripTop(s, land)
        }
        paneAt(0)?.setActive(activeIndex == 0)
        paneAt(1)?.setActive(activeIndex == 1)
        renderClipBar() // active pane changed, the paste target follows
    }

    /** Strip column count: 1 column in portrait (height is enough), 2 in landscape; column width is fixed at [STRIP_COL_DP], total width scales with column count. */
    private fun applyStripColumns(s: ActionStripBinding, land: Boolean) {
        val on = Prefs.rowDivider(this) // shares the switch with list row dividers
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
            invalidateOptionsMenu() // the swap icon direction follows the active pane (including swipe / touch switch)
            // The two panes can land on sources with different capabilities (one local, one a media server); switching active pane means recomputing the action strip — ★ must happen **after** activeIndex updates, otherwise we still read the previous pane
            syncStripEnabled()
        }
    }

    /**
     * Open the comparison page with the current directories of the two panes. Both sides
     * need a selected directory first — right after launch, before any directory has been
     * tapped, `currentDir` is null, so we prompt instead of forcing the root.
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

    /**
     * Exit confirmation.
     *
     * **If nothing is running, don't ask** (just exit) — at that point the confirmation
     * dialog prevents no real loss, it's just one extra step. If music is playing, a
     * terminal session is open, sharing is on, or files are transferring, list them
     * so the user can see exactly what's about to be lost, and offer the "lock instead"
     * exit, which doesn't interrupt any of that.
     */
    private fun confirmExit() {
        val running = com.twig.app.ui.AppExit.running(this)
        if (running.isEmpty()) {
            com.twig.app.ui.AppExit.quit(this)
            return
        }
        val canLock = com.twig.app.secure.Secrets.hasMasterPassword(this)
        val msg = buildString {
            append(getString(R.string.exit_note))
            running.forEach { append("\n  • ").append(it) }
            if (canLock) append("\n\n").append(getString(R.string.exit_lock_hint))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(msg)
            .setPositiveButton(R.string.exit_confirm) { _, _ -> com.twig.app.ui.AppExit.quit(this) }
            .apply {
                // "Lock instead" is the "don't interrupt anything" path; it's only available when
// a master password has been set.
                if (canLock) {
                    setNeutralButton(R.string.exit_lock_instead) { _, _ ->
                        com.twig.app.ui.AppExit.lock(this@MainActivity)
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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
        // Wait for the fragment transaction to land and the panes to be ready.
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
        // Swipe left (negative velocity) → show right pane; swipe right → left pane
        if (!isLandscape()) setActiveIndex(if (velocityX < 0) 1 else 0)
    }

    override fun onClipTargetChanged() {
        if (b.clipBar.visibility == View.VISIBLE) renderClipBar()
        syncStripEnabled()
    }

    /**
     * The action strip's **write** entries follow the active pane's capability toggles.
     *
     * On read-only sources (media servers, restic, 7z/RAR, the git view, "Apps"), create /
     * move / rename / delete are all greyed out — not "tap and get an error toast".
     * Copy / compress / share are **pure read source**, always available on any source.
     *
     * All three action strips (landscape middle + portrait left/right) need refreshing,
     * same as [syncShareIcon]. The trigger is [onClipTargetChanged] — PaneFragment.render
     * calls it on every state refresh, so directory switches, ticks, pane switches all
     * update it as a side effect.
     */
    private fun syncStripEnabled() {
        // ★ Don't ask the pane before it's ready: the two panes' views come up one at a time,
        // and the first one to come up may fire its first frame before the other has reached
        // onViewCreated (see PaneFragment.isReady)
        val pane = activePane()?.takeIf { it.isReady() }
        val modify = pane?.canModify() ?: false
        val create = pane?.canCreateHere() ?: false
        for (s in listOf(b.stripMid, b.stripLeft, b.stripRight)) {
            setStripEnabled(s.asNewFolder, create)
            setStripEnabled(s.asMove, modify)
            setStripEnabled(s.asRename, modify)
            setStripEnabled(s.asDelete, modify)
        }
        b.btnPaste.isEnabled = create
        b.btnPaste.alpha = if (create) 1f else DISABLED_ALPHA
    }

    /**
     * ★ Setting `alpha` alone is not enough — looks grey, but a tap still fires the action.
     * `isEnabled = false` makes `View.onTouchEvent` not dispatch clicks at all, and that
     * works on `LinearLayout` too; both are required.
     * Child views (icon / text) don't need to be touched, `alpha` is applied to the whole container.
     */
    private fun setStripEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else DISABLED_ALPHA
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
        "jellyfin", "emby" -> showMediaDialog(type)
        // Scanned Twig shares are stored as WebDAV connections, so the entry lives in the WebDAV group
        "scan_twig" -> ShareDialogs.scanAndAdd(this) {
            refreshTrees()
            activePane()?.viewModel?.expandGroup("dav")
        }
        "saf" -> openSafPicker(null)
        else -> Unit
    }

    /** When [initial] is non-null, the system picker opens straight at that location (used by the removable volume's "Authorise this volume with SAF" entry). */
    override fun openSafPicker(initial: Uri?) {
        runCatching { safPickerLauncher.launch(initial) }
            .onFailure {
                Toast.makeText(this, getString(R.string.saf_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
            }
    }

    override fun onEditServer(conn: SavedConnection) = when (conn.type) {
        "smb" -> showSmbDialog(conn)
        "ftp" -> showFtpDialog(conn)
        "sftp" -> showSftpDialog(conn)
        "webdav" -> showWebdavDialog(conn)
        "s3" -> showS3Dialog(conn)
        "jellyfin", "emby" -> showMediaDialog(conn.type, conn)
        else -> Unit
    }

    // ---- Add / edit server (only save the config; connect on node expand) ----

    /**
     * Save (on edit, drop any cached connection for the old entry).
     *
     * Editing goes through [ConnectionStore.replace] rather than remove + save so the
     * server **keeps its position in the sidebar**: an edit may change the label (host,
     * port, root directory are all part of it), and re-adding at the end would move a row
     * the user only meant to rename.
     */
    private fun saveServer(edit: SavedConnection?, conn: SavedConnection, group: String) {
        if (edit != null) {
            paneAt(0)?.viewModel?.forgetServer(edit.label())
            paneAt(1)?.viewModel?.forgetServer(edit.label())
            ConnectionStore.replace(this, edit, conn)
        } else {
            ConnectionStore.save(this, conn)
        }
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
                // Optional, and it doubles as the start path: empty mounts the whole
                // server (the root lists every share), "Public/Photos" roots the
                // connection at that directory. See SmbFileSystem.
                val share = d.etShare.text.toString().trim().trim('/')
                if (host.isEmpty()) return@setPositiveButton
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
            d.etPath.setText(it.share)
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
                        share = d.etPath.text.toString().trim().trim('/'),
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
            d.etPath.setText(it.share)
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
                        share = d.etPath.text.toString().trim().trim('/'),
                        user = user,
                        password = d.etPass.text.toString(),
                        name = d.etName.text.toString().trim(),
                        keyPath = d.etKey.text.toString().trim(),
                        // Keep the remembered host key: changing a display name should not also wipe trust.
                        // To reset, use "Forget host key" in the server's long-press menu.
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

    /**
     * Jellyfin / Emby add / edit dialog. The two server endpoints share the same source and
     * identical fields, so they reuse one dialog; [type] only decides the title and the
     * persisted type.
     *
     * On edit we **don't prefill token / userId, nor clear them** — they're credentials
     * obtained from login, not fields the user filled in. If the address or user changed,
     * we invalidate them below (a new identity makes the old token meaningless).
     */
    private fun showMediaDialog(type: String, edit: SavedConnection? = null) {
        val d = com.twig.app.databinding.DialogJellyfinBinding.inflate(layoutInflater)
        edit?.let {
            d.etName.setText(it.name); d.etUrl.setText(it.host)
            d.etUser.setText(it.user); d.etPass.setText(it.password)
            d.etApiKey.setText(it.apiKey)
        }
        val title =
            if (type == "emby") R.string.action_connect_emby else R.string.action_connect_jellyfin
        AlertDialog.Builder(this)
            .setTitle(if (edit == null) getString(title) else getString(R.string.server_edit))
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val url = d.etUrl.text.toString().trim().trimEnd('/')
                if (!url.startsWith("http")) return@setPositiveButton
                val user = d.etUser.text.toString().trim()
                val apiKey = d.etApiKey.text.toString().trim()
                // Address or user changed = new identity; invalidate token/userId together, otherwise
                // we'd list the new user's "Continue watching" using the old user's token
                val same = edit != null && edit.host == url && edit.user == user
                saveServer(
                    edit,
                    SavedConnection(
                        type, url,
                        user = user,
                        password = d.etPass.text.toString(),
                        name = d.etName.text.toString().trim(),
                        apiKey = apiKey,
                        token = if (same) edit!!.token else "",
                        userId = if (same) edit!!.userId else "",
                    ),
                    "media",
                )
            }
            .show()
    }

    // ---- Menu ----

    /** Checked states of the toggle items in R.menu.main; shared between the Toolbar menu and the landscape strip's PopupMenu. */
    private fun syncMenuChecks(menu: Menu) {
        menu.findItem(R.id.action_remember_location)?.isChecked = Prefs.rememberLocation(this)
        menu.findItem(R.id.action_fullscreen)?.isChecked = Prefs.fullscreen(this)
        menu.findItem(R.id.action_thumbs)?.isChecked = Prefs.thumbs(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        syncMenuChecks(menu)
        // Without a master password, "Lock" is meaningless (locking wouldn't require a password to come back)
        menu.findItem(R.id.action_lock)?.isVisible = com.twig.app.secure.Secrets.hasMasterPassword(this)
        // Terminal entry is always present: tapping with no sessions creates a fresh local shell (see TerminalActivity.handleIntent)
        menu.add(0, MENU_TERMINAL, 0, getString(R.string.terminal_menu)).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_terminal)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_MUSIC, 0, getString(R.string.music_menu)).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_music_note)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        // Swap to the other pane: current pane filled, target hollow, arrow points to the side you're switching to
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
        R.id.action_view_mode -> {
            showViewModeDialog(); true
        }
        R.id.action_thumbs -> {
            Prefs.setThumbs(this, !item.isChecked)
            uiSig = Prefs.uiSignature(this)
            recreate()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, com.twig.app.ui.SettingsActivity::class.java)); true
        }
        R.id.action_lock -> {
            com.twig.app.ui.AppExit.lock(this); true
        }
        R.id.action_exit -> {
            confirmExit(); true
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
            // On the desktop it's just called "Music": a long label (e.g. "Music Player") would push out the short name on launchers
            .setShortLabel(getString(R.string.music_menu))
            .setLongLabel(getString(R.string.music_menu))
            .setIcon(ShortcutIcons.of(this, R.drawable.ic_shortcut_music))
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

    /** Quick view-mode switch: three grid states (independent of the thumbnail switch; details on the settings page). */
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

    // ---- Permission ----

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
