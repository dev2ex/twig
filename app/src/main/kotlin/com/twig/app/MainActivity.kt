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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.twig.app.databinding.ActionStripBinding
import com.twig.app.databinding.ActivityMainBinding
import com.twig.app.databinding.DialogFtpBinding
import com.twig.app.databinding.DialogSmbBinding
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
import com.twig.core.isWritableDir
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
    private lateinit var fullscreenLayout: com.twig.app.ui.StatusBarFullscreen
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
        private const val EXTRA_LOCATE_SCHEME = "locate_scheme"
        private const val EXTRA_LOCATE_PATH = "locate_path"
        private const val EXTRA_LOCATE_LABEL = "locate_label"
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
        /**
         * Show a terminal session's directory **without navigating the pane**: it is pinned on top
         * of the tree like an externally opened archive and taken down again when the user goes
         * back, which also returns them to the terminal they came from.
         *
         * That, rather than [revealIntent], is how the terminal jumps: a session can sit anywhere on
         * a server, including outside the subdirectory the connection mounts, where there is no row
         * in the tree to reveal — and a temporary node leaves the pane exactly as the user left it.
         */
        fun locateIntent(ctx: Context, scheme: String, path: String, label: String): Intent =
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_LOCATE_SCHEME, scheme)
                putExtra(EXTRA_LOCATE_PATH, path)
                putExtra(EXTRA_LOCATE_LABEL, label)
            }

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
    private var pendingLocate: XFile? = null
    private var pendingLocateLabel: String? = null
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
                AndroidDataAccess.instance?.refresh()
                refreshTrees()
                activePane()?.viewModel?.expandGroup("saf")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.rememberLocation(this)) activeIndex = Prefs.activePane(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        fullscreenLayout = com.twig.app.ui.StatusBarFullscreen(this, b.root)
        com.twig.app.ui.NavBarTint.surface(this) // tint the nav bar to the pane's background, no longer a black strip at the bottom
        setSupportActionBar(b.toolbar)
        // Portrait's "up" on the left of the title bar: same action as the strip's as_up
        // (landscape has no Toolbar, see applyLayoutMode) — placed here because one-handed
        // use reaches the top-left more easily than the right-side action strip
        b.toolbar.setNavigationIcon(R.drawable.ic_parent_directory)
        b.toolbar.setNavigationContentDescription(R.string.strip_up)
        b.toolbar.setNavigationOnClickListener { activePane()?.actionUp() }
        for (i in 0 until b.toolbar.childCount) {
            val child = b.toolbar.getChildAt(i)
            if (child is android.widget.ImageButton && child.drawable === b.toolbar.navigationIcon) {
                child.setOnLongClickListener { activePane()?.actionScrollToTop(); true }
            }
        }

        TwigApp.registerBaseFs(this) // Base sources (local / archive / SAF / apps / content://)

        uiSig = Prefs.uiSignature(this)
        wireStrip(b.browser.stripLeft)
        wireStrip(b.browser.stripMid)
        wireStrip(b.browser.stripRight)
        syncShareIcon()
        installClipboardBar()
        applyLayoutMode()
        applyFullscreen()

        // Desktop long-press menu: re-publish every time we enter the main UI, so language changes follow too (see Shortcuts)
        com.twig.app.ui.Shortcuts.publish(this)

        readRevealExtras(intent)
        readMountExtras(intent)
        readLocateExtras(intent)
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
        readLocateExtras(intent)
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

    private fun readLocateExtras(intent: Intent?) {
        val scheme = intent?.getStringExtra(EXTRA_LOCATE_SCHEME) ?: return
        val path = intent.getStringExtra(EXTRA_LOCATE_PATH) ?: return
        pendingLocate = XFile(scheme, path, isDir = true)
        pendingLocateLabel = intent.getStringExtra(EXTRA_LOCATE_LABEL)
        // A relaunch after a configuration change must not mount it a second time.
        intent.removeExtra(EXTRA_LOCATE_SCHEME)
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
        pendingLocate?.let {
            pendingLocate = null
            activePane()?.mountLocated(it, pendingLocateLabel)
            pendingLocateLabel = null
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
        if (activePane()?.viewModel?.unmountPinnedDirectory() == true) return
        // Came here from a terminal session's "Locate current directory": take the temporary node
        // down, put the pane back the way it was, and return to the session — which is still
        // running in its own task, so this brings that task forward rather than starting anything.
        if (activePane()?.unmountLocated() == true) {
            startActivity(
                Intent(this, com.twig.app.ui.TerminalActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (Prefs.exitOnBack(this)) {
            confirmExit(alwaysConfirm = true)
            return
        }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::fullscreenLayout.isInitialized) applyFullscreen() // the system may restore the status bar after switching; re-apply
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, volumeReceiver, volumeFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, transferReceiver,
            android.content.IntentFilter(com.twig.app.ui.Transfers.ACTION_FINISHED), ContextCompat.RECEIVER_NOT_EXPORTED)
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
        // A transfer finished while the UI wasn't around: come back and catch up (clear selection / refresh both sides).
        // Don't consume if the pane isn't ready — taken too early nothing will handle it, defer to next onResume
        consumeFinishedTransfer()
        invalidateOptionsMenu() // terminal sessions may have been added/removed in another Activity; refresh the entry's visibility
        val land = isLandscape()
        for (s in listOf(b.browser.stripMid, b.browser.stripLeft, b.browser.stripRight)) applyStripState(s, land)
        // Sharing may have been turned off via the notification bar's "stop" button (the UI wasn't even present); sync once on returning to foreground
        syncShareIcon()
        WebShare.onStateChanged = { runOnUiThread { syncShareIcon() } }
        // SD cards / USB drives are usually inserted/removed while the app isn't in the foreground; rescan on return — only refresh the tree if something changed
        rescanVolumes()
        pollVolumes()
    }

    override fun onPause() {
        b.browser.root.finishTransition()
        super.onPause()
        // Drop the callback: it captures this Activity, leaving it in place is a reference to an already-destroyed UI
        WebShare.onStateChanged = null
        runCatching { unregisterReceiver(volumeReceiver) }
        runCatching { unregisterReceiver(transferReceiver) }
    }

    private fun consumeFinishedTransfer() {
        com.twig.app.ui.ShareTransfers.attach(this)
        activePane()?.takeIf { it.isReady() }?.let { pane ->
            com.twig.app.ui.Transfers.consumeFinished()?.let { pane.finishTransfer(it) }
        }
    }

    private val transferReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = consumeFinishedTransfer()
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
        fullscreenLayout.apply(Prefs.fullscreen(this))
    }

    // ---- Side action row ----

    private fun wireStrip(s: ActionStripBinding) {
        s.sizeIconsLikeRows(this)
        s.asUp.setOnClickListener { activePane()?.actionUp() }
        s.asUp.setOnLongClickListener { activePane()?.actionScrollToTop(); true }
        s.asSort.setOnLongClickListener { showSortDialog(selectedDirectory = true); true }
        s.asRefresh.setOnLongClickListener { activePane()?.actionRefreshThumbnails(); true }
        s.asNewFolder.setOnLongClickListener { activePane()?.actionNewTextFile(); true }
        s.asCopy.setOnLongClickListener { activePane()?.actionCopy(move = false, immediate = true); true }
        s.asMove.setOnLongClickListener { activePane()?.actionCopy(move = true, immediate = true); true }
        s.asCompress.setOnLongClickListener { activePane()?.actionCompress(immediate = true); true }
        s.asRename.setOnLongClickListener { activePane()?.actionRename(selectExtension = true); true }
        s.asDelete.setOnLongClickListener { activePane()?.actionDelete(skipTrashConfirmation = true); true }
        s.asCompare.setOnLongClickListener { startCompare(reversed = true); true }
        s.asMap.setOnLongClickListener {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_MEMORY_CARD_SETTINGS)) }
                .onFailure { Toast.makeText(this, R.string.err_failed, Toast.LENGTH_SHORT).show() }
            true
        }
        s.asSearch.setOnLongClickListener {
            startActivity(com.twig.app.ui.WebsiteActivity.browserIntent(this, "https://start.duckduckgo.com/")); true
        }
        s.asHistory.setOnLongClickListener { activePane()?.actionHistory(continueWatching = true); true }
        s.asShare.setOnLongClickListener {
            ShareDialogs.startBackground(this, activePane()?.viewModel?.currentDir); true
        }
        s.asTerm.setOnLongClickListener { activePane()?.actionTerminal(); true }
        s.asMusic.setOnLongClickListener { openMusicPlaying(); true }
        s.asMenu.setOnLongClickListener { confirmExit(); true }
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
        s.asSwap.setOnClickListener { setActiveIndex(1 - activeIndex, animateSwitch = true) }
        s.asSwap.setOnLongClickListener { showCurrentDirInOtherPane(); true }
        s.asMenu.setOnClickListener { showStripOverflow(it) }
    }

    private fun showCurrentDirInOtherPane() {
        val dir = activePane()?.viewModel?.currentDir
        if (dir == null) {
            Toast.makeText(this, R.string.msg_pick_dir_first, Toast.LENGTH_SHORT).show()
            return
        }
        val targetIndex = 1 - activeIndex
        val target = paneAt(targetIndex) ?: return
        target.mountPinnedDirectory(dir, Format.pathLabel(dir))
        setActiveIndex(targetIndex, animateSwitch = true)
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
     * hidden; the terminal entry is always available; the pane-swap icon
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
        for (s in listOf(b.browser.stripMid, b.browser.stripLeft, b.browser.stripRight)) s.asShareIcon.setImageResource(res)
    }

    private fun applyStripState(s: ActionStripBinding, land: Boolean) {
        val direction = if (activeIndex == 0) 1f else -1f
        s.asMoveIcon.scaleX = direction
        s.asCompareIcon.scaleX = direction
        s.asTop.visibility = if (land) View.VISIBLE else View.GONE
        s.asTopDivider.visibility = if (land) View.VISIBLE else View.GONE
        if (!land) return
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
        val canPaste = target?.isWritableDir() == true && block == null
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

    private fun applyLayoutMode(animateSwitch: Boolean = false) {
        val land = isLandscape()
        b.browser.paneA.visibility = View.VISIBLE
        b.browser.paneB.visibility = View.VISIBLE
        b.browser.stripMid.root.visibility = if (land) View.VISIBLE else View.GONE
        b.browser.stripLeft.root.visibility = View.GONE
        b.browser.stripRight.root.visibility = if (!land) View.VISIBLE else View.GONE
        // Landscape has no Toolbar (vertical space is precious); its right-side buttons move into the strip's top pinned row
        b.toolbar.visibility = if (land) View.GONE else View.VISIBLE
        com.twig.app.ui.ToolbarPlacement.apply(this, b.toolbar, Prefs.toolbarAtBottom(this))
        // Landscape's height can't fit a full single column, so the strip becomes two columns (width doubles)
        for (s in listOf(b.browser.stripMid, b.browser.stripLeft, b.browser.stripRight)) {
            applyStripColumns(s, land)
            applyStripState(s, land)
        }
        b.browser.root.showPane(activeIndex, animateSwitch && !land)
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
        s.asGrid.setColumns(cols)
        val w = (STRIP_COL_DP * cols * resources.displayMetrics.density).toInt()
        val lp = s.root.layoutParams
        if (lp.width != w) {
            lp.width = w
            s.root.layoutParams = lp
        }
    }

    private fun setActiveIndex(i: Int, animateSwitch: Boolean = false) {
        if (activeIndex != i) {
            activeIndex = i
            Prefs.setActivePane(this, i)
            applyLayoutMode(animateSwitch)
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
    private fun startCompare(reversed: Boolean = false) {
        val l = paneAt(0)?.viewModel?.currentDir
        val r = paneAt(1)?.viewModel?.currentDir
        if (l == null || r == null) {
            android.widget.Toast.makeText(this, R.string.compare_need_two, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        paneAt(0)?.viewModel?.noteChangedIn(l)
        paneAt(1)?.viewModel?.noteChangedIn(r)
        com.twig.app.ui.CompareActivity.start(this, if (reversed) r else l, if (reversed) l else r)
    }

    /** Back-triggered exits always ask; explicit exit shortcuts ask only when tasks are running. */
    private fun confirmExit(alwaysConfirm: Boolean = false) {
        val running = com.twig.app.ui.AppExit.running(this)
        if (running.isEmpty() && !alwaysConfirm) {
            com.twig.app.ui.AppExit.quit(this)
            return
        }
        val canLock = running.isNotEmpty() && com.twig.app.secure.Secrets.hasMasterPassword(this)
        val msg = buildString {
            append(getString(R.string.exit_note))
            running.forEach { append("\n  • ").append(it) }
            if (canLock) append("\n\n").append(getString(R.string.exit_lock_hint))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(msg.takeIf { running.isNotEmpty() })
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
        if (pendingReveal != null || pendingMount != null || pendingLocate != null) {
            b.root.post { applyPendingReveal() }
        }
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
        if (!isLandscape()) setActiveIndex(if (velocityX < 0) 1 else 0, animateSwitch = true)
    }

    override fun onClipTargetChanged() {
        if (b.clipBar.visibility == View.VISIBLE) renderClipBar()
        syncStripEnabled()
    }

    /**
     * ★ A check / uncheck changes what Move / Rename / Delete act on without touching the tree,
     * so no `render` follows it — the strip has to be recomputed here. Missing this left the
     * buttons greyed out on writable sources until the next pane switch recomputed them by accident
     * (only [setActiveIndex] and [onClipTargetChanged] used to call [syncStripEnabled]).
     */
    override fun onSelectionChanged() {
        syncStripEnabled()
    }

    /** Refresh all strip instances when either pane, the selection or map mode changes. */
    private fun syncStripEnabled() {
        // The first pane can render before the other pane's adapter is initialized.
        val pane = activePane()?.takeIf { it.isReady() }
        val actions = pane?.stripActions() ?: PaneFragment.StripActions()
        val compare = pane?.viewModel?.currentDir?.isDir == true
        for (s in listOf(b.browser.stripMid, b.browser.stripLeft, b.browser.stripRight)) {
            setStripEnabled(s.asNewFolder, actions.create)
            setStripEnabled(s.asCopy, actions.copy)
            setStripEnabled(s.asCompress, actions.copy)
            setStripEnabled(s.asMove, actions.move)
            setStripEnabled(s.asRename, actions.rename)
            setStripEnabled(s.asDelete, actions.delete)
            setStripEnabled(s.asCompare, compare)
            setStripEnabled(s.asMap, actions.treemap)
            setStripEnabled(s.asSearch, actions.search)
        }
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
            d.smb1.isChecked = it.smb1
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
                        domain = d.etDomain.text.toString().trim(),
                        name = d.etName.text.toString().trim(),
                        smb1 = d.smb1.isChecked,
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
        menu.findItem(R.id.action_settings)?.title = "⚙︎  ${getString(R.string.action_settings)}"
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
        com.twig.app.ui.PaneSwitch.add(menu, b.toolbar, MENU_SWAP_PANE, activeIndex,
            onSwitch = { setActiveIndex(1 - activeIndex, animateSwitch = true) },
            onLongPress = { showCurrentDirInOtherPane() },
        )
        com.twig.app.ui.ToolbarLongPress.bind(b.toolbar,
            actions = mapOf(MENU_TERMINAL to { activePane()?.actionTerminal(); Unit },
                MENU_MUSIC to { openMusicPlaying() }),
            overflow = { confirmExit() },
            title = { com.twig.app.ui.AboutDialog.show(this) })
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
            setActiveIndex(1 - activeIndex, animateSwitch = true); true
        }
        R.id.action_view_mode -> {
            showViewModeDialog(); true
        }
        R.id.action_root_items -> {
            showRootItemsDialog(); true
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

    private fun showRootItemsDialog() {
        val items = RootItems.all
        val checked = Array(2) { pane -> items.map { Prefs.rootItemVisible(this, pane, it.id) }.toBooleanArray() }
        val content = com.twig.app.databinding.DialogRootItemsBinding.inflate(layoutInflater)
        val metrics = resources.displayMetrics
        val availableHeight = (resources.configuration.screenHeightDp - 200).coerceAtLeast(96) * metrics.density
        content.itemsScroll.layoutParams.height = minOf(metrics.heightPixels * 0.55f, availableHeight).toInt()
        items.forEachIndexed { index, item ->
            val row = com.twig.app.databinding.ItemRootVisibilityBinding.inflate(layoutInflater, content.items, false)
            row.root.tag = item.id
            row.icon.setImageResource(item.icon)
            row.label.setText(item.label)
            listOf(row.left, row.right).forEachIndexed { pane, checkbox ->
                checkbox.isChecked = checked[pane][index]
                checkbox.contentDescription = getString(item.label) + " · " +
                    getString(if (pane == 0) R.string.compare_side_left else R.string.compare_side_right)
                checkbox.setOnCheckedChangeListener { _, on -> checked[pane][index] = on }
                (if (pane == 0) row.leftCell else row.rightCell).setOnClickListener { checkbox.performClick() }
            }
            content.items.addView(row.root)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_root_items)
            .setView(content.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                for (pane in 0..1) {
                    items.forEachIndexed { index, item -> Prefs.setRootItemVisible(this, pane, item.id, checked[pane][index]) }
                    paneAt(pane)?.viewModel?.refreshRootItems()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun showSortDialog(selectedDirectory: Boolean = false) {
        com.twig.app.ui.SortDialog.show(this, activePane()?.viewModel?.currentDir, selectedDirectory,
            parentOf = { activePane()?.viewModel?.directoryViewParent(it) }) {
            paneAt(0)?.refreshSortAndView()
            paneAt(1)?.refreshSortAndView()
            uiSig = Prefs.uiSignature(this)
        }
    }

    private fun openMusicPlaying() {
        startActivity(Intent(this, com.twig.app.ui.MusicPlayerActivity::class.java).putExtra(com.twig.app.ui.MusicPlayerActivity.EXTRA_AUTO_PLAY, true))
    }

    private fun showViewModeDialog() {
        com.twig.app.ui.SortDialog.showView(this, activePane()?.viewModel?.currentDir,
            parentOf = { activePane()?.viewModel?.directoryViewParent(it) }) {
            paneAt(0)?.refreshSortAndView()
            paneAt(1)?.refreshSortAndView()
            uiSig = Prefs.uiSignature(this)
        }
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
