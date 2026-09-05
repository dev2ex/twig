package com.twig.app.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.termux.terminal.KeyHandler
import com.termux.terminal.TermBridge
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import com.twig.app.Prefs
import com.twig.app.PrivShell
import com.twig.app.Privileged
import com.twig.app.R
import com.twig.app.databinding.ActivityTerminalBinding
import com.twig.core.FsRegistry
import com.twig.fs.network.SftpFileSystem
import kotlin.math.roundToInt

/**
 * A persistent terminal session (independent of Activity lifecycle): stored in [TermManager], it keeps
 * accumulating output in the background after returning to the file manager, and resumes where it left off.
 * Each session has its own SSH connection and terminal emulator.
 */
class TermSession(
    val id: Long,
    /** SSH session = that server's scheme; local session = [LOCAL_SCHEME]. */
    val scheme: String,
    var title: String,
) {
    /**
     * Local shell session: takes termux's own local PTY path (`initializeEmulator` forks /system/bin/sh),
     * without SSH — so the bridge thread, resize probing, reconnect logic and other things prepared for remote sessions are all unnecessary.
     */
    val isLocal: Boolean get() = scheme == LOCAL_SCHEME

    lateinit var session: TerminalSession
    lateinit var emulator: TerminalEmulator

    /**
     * Null before it's ready. SSH sessions have the emulator injected before the view is built; local sessions have to wait
     * until the process forks — anywhere that might run before the session is ready (UI callbacks, iterating all sessions)
     * has to go through this; reading `lateinit` directly would throw UninitializedPropertyAccessException.
     */
    val emulatorOrNull: TerminalEmulator? get() = if (::emulator.isInitialized) emulator else null
    var shell: SftpFileSystem.ShellSession? = null
    @Volatile var connecting = true
    @Volatile var alive = false
    /** Incremented every time the shell is (re)connected; the bridge thread uses it to know whether it has been replaced by a newer connection. */
    @Volatile var gen = 0
    /** Set true when the user actively ends the session, to stop the reconnect logic from mistaking it for a dropped connection. */
    @Volatile var closing = false

    /** When the session's process started. Used to tell apart "user typed exit" and "it died before it ever came up". */
    @Volatile var startedAt = System.currentTimeMillis()

    /** Privileged PTY session: master device fd and child pid (obtained via the Shizuku helper); null for ordinary sessions. */
    @Volatile var privFd: android.os.ParcelFileDescriptor? = null
    @Volatile var privPid = 0
    /** Only the currently-displayed session sets this callback to refresh the UI; background sessions are null and just silently accumulate into the emulator. */
    @Volatile var onOutput: (() -> Unit)? = null

    fun close() {
        closing = true
        alive = false
        connecting = false
        onOutput = null
        privFd?.let { pfd ->
            privFd = null
            // Kill the process group first, then close the fd: only closing the fd means the shell won't receive SIGHUP
            // until its next write, and a shell stuck reading may never write.
            if (privPid > 0) runCatching { com.twig.app.priv.Pty.nativeKill(privPid) }
            runCatching { pfd.close() }
            return
        }
        if (isLocal) {
            if (::session.isInitialized) runCatching { session.finishIfRunning() }
            return
        }
        val sh = shell
        shell = null
        if (sh != null) Thread({ runCatching { sh.close() } }, "twig-term-close").start()
    }
}

/** Local shell session scheme marker (not a source in FsRegistry). */
const val LOCAL_SCHEME = "local"

/**
 * Multi-session manager (static, persists across Activity lifecycle). The dropdown at the top of the terminal page is this list;
 * "Terminal here / SSH terminal / New" all append sessions here; selecting one switches, only an explicit close removes.
 */
object TermManager {
    private val sessions = ArrayList<TermSession>()
    @Volatile var current: TermSession? = null
        private set
    private var nextId = 1L

    @Synchronized fun list(): List<TermSession> = ArrayList(sessions)

    @Synchronized fun isEmpty(): Boolean = sessions.isEmpty()

    @Synchronized fun create(scheme: String, title: String): TermSession {
        val t = TermSession(nextId++, scheme, title)
        sessions.add(t)
        current = t
        return t
    }

    @Synchronized fun select(t: TermSession) {
        if (sessions.contains(t)) current = t
    }

    /** Remove and close a session; returns the session that should be shown after removal (may be null). */
    @Synchronized fun remove(t: TermSession): TermSession? {
        val idx = sessions.indexOf(t)
        t.close()
        sessions.remove(t)
        if (current === t) {
            current = sessions.getOrNull(idx) ?: sessions.lastOrNull()
        }
        return current
    }

    @Synchronized fun closeAll() {
        for (s in sessions) s.close()
        sessions.clear()
        current = null
    }
}

/**
 * SSH terminal: Termux terminal emulator/rendering + SSHJ shell channel, with multi-session support.
 *
 * Termux's TerminalSession is final and bound to a local PTY (JNI child process); here we don't start its
 * process but reflectively inject a self-built TerminalEmulator (output written straight to SSH stdin), set mShellPid=1
 * to queue keypresses, with a bridge thread carrying them to SSH. Sessions live in [TermManager], switched via the top dropdown;
 * Activity lives in its own task stack, so users can swap back and forth with the file manager without losing the session.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var b: ActivityTerminalBinding
    private val main = Handler(Looper.getMainLooper())
    private val sessionClient = SessionClient()
    private var ctrlPending = false
    private var altPending = false
    private var shiftPending = false

    /** Currently displayed session (the one bound to the view). */
    private var displayed: TermSession? = null
    private var suppressSpinner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyFullscreen()
        CmdShims.ensureAsync(this) // Command-completion shims for local shells — build/repair symlinks in the background.
        SshHome.ensureAsync(this) // .mkshrc's ssh alias needs -F on this config, so it has to exist first.

        b.toolbar.setNavigationOnClickListener { finish() } // Back to file manager; the session keeps running.
        b.toolbar.menu.add(0, MENU_END_CURRENT, 0, getString(R.string.terminal_end_current)).apply {
            icon = ContextCompat.getDrawable(this@TerminalActivity, R.drawable.ic_close)
                ?.mutate()?.apply { setTint(Color.WHITE) }
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        b.toolbar.menu.add(0, MENU_NEW, 1, getString(R.string.terminal_new)).apply {
            icon = ContextCompat.getDrawable(this@TerminalActivity, R.drawable.ic_add)
                ?.mutate()?.apply { setTint(Color.WHITE) }
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        b.toolbar.menu.add(0, MENU_KEYBOARD, 2, getString(R.string.terminal_keyboard)).apply {
            icon = ContextCompat.getDrawable(this@TerminalActivity, R.drawable.ic_keyboard)
                ?.mutate()?.apply { setTint(Color.WHITE) }
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        b.toolbar.menu.add(0, MENU_NEW_LOCAL, 3, getString(R.string.terminal_new_local))
        // Privileged session: only appears when privileged access has connected and that path can actually start.
        if (Privileged.active != Privileged.OFF && PrivShell.available(this, Privileged.active)) {
            b.toolbar.menu.add(0, MENU_NEW_PRIV, 4, getString(R.string.terminal_new_priv))
        }
        b.toolbar.menu.add(0, MENU_FONT, 4, getString(R.string.settings_term_font))
        b.toolbar.menu.add(0, MENU_COLORS, 5, getString(R.string.settings_term_colors))
        b.toolbar.menu.add(0, MENU_END_ALL, 6, getString(R.string.terminal_end_all))
        b.toolbar.menu.add(0, MENU_KEEP_AWAKE, 7, getString(R.string.terminal_keep_awake)).apply {
            isCheckable = true
            isChecked = Prefs.terminalKeepAwake(this@TerminalActivity)
        }
        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_NEW -> { newOnCurrentServer(); true }
                MENU_KEYBOARD -> { toggleIme(); true }
                MENU_END_CURRENT -> { endCurrent(); true }
                MENU_FONT -> { chooseFont(); true }
                MENU_COLORS -> { chooseColors(); true }
                MENU_NEW_LOCAL -> { openLocal(null); true }
                MENU_NEW_PRIV -> { openPrivileged(null); true }
                MENU_END_ALL -> { TermManager.closeAll(); finish(); true }
                MENU_KEEP_AWAKE -> {
                    val on = !it.isChecked
                    it.isChecked = on
                    Prefs.setTerminalKeepAwake(this, on)
                    b.terminal.keepScreenOn = on
                    true
                }
                else -> false
            }
        }

        // ★ setTextSize must come before setTypeface: the latter reads mRenderer.mTextSize directly,
        // and mRenderer is only created inside setTextSize, so the reverse order NPEs.
        applyTextSize(
            Prefs.terminalTextSize(this).takeIf { it > 0 }
                ?: (13 * resources.displayMetrics.density).toInt(),
        )
        applyFont()
        b.terminal.keepScreenOn = Prefs.terminalKeepAwake(this)
        b.terminal.setTerminalViewClient(ViewClient())
        b.sessionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressSpinner) return
                val t = TermManager.list().getOrNull(pos) ?: return
                if (t !== displayed) showSession(t)
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        buildExtraKeys()
        applyColors()
        // Layout changes (soft keyboard show/hide, orientation flip) cause the termux-view to alter the local emulator's row/column;
        // after debouncing, sync the new size to the remote PTY to fix the blank padding that full-screen programs like htop leave when the keyboard retracts.
        // Whether to dare send is decided from per-server probing memory; see syncRemoteSize().
        b.terminal.viewTreeObserver.addOnGlobalLayoutListener {
            // While frozen the emulator did not move, so there is nothing to forward —
            // just keep pushing the thaw out until the window stops jittering.
            if (b.terminalBox.frozen) scheduleThaw(restart = false) else scheduleSizeSync()
            syncKeyboardIcon() // The keyboard's show/hide also changes layout, so swap the icon to match.
        }

        // ★ This is a **home-screen entry** (long-press app icon → Terminal), bypassing the main screen,
        // so the unlock gate must intercept here: saved credentials are connected in the session, and even local shells can read the app's private dir.
        // Views and lateinit are all built above; the callback only does "open the session" — moving initialization past the unlock callback
        // causes lifecycle callbacks to run first (same trap as PaneFragment.adapter).
        SecurityUi.gate(this) { handleIntent(intent) }
    }

    /**
     * Current terminal text size (px). Pinch-zoom has to multiply on top of this — termux's `mScaleFactor` is
     * a cumulative factor and gets overwritten by [ViewClient.onScale]'s return value; we always return 1.0f to reset it,
     * so if the baseline is still the fixed initial size, a single gesture just keeps setting the same base×threshold value,
     * looking like "pinch does nothing".
     */
    private var textSizePx = 0

    /**
     * Switch the text size. ★ Must call `invalidate()` ourselves: termux's `setTextSize` → `updateSize()`
     * only calls `invalidate()` in the "row/column changed" branch (`setTypeface` does call it itself),
     * so when a size tweak doesn't cross a row/column boundary the screen stays at the old size.
     */
    private fun applyTextSize(px: Int) {
        textSizePx = px.coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
        b.terminal.setTextSize(textSizePx)
        b.terminal.invalidate()
    }

    /** Applied font path, used to reload after a change on the settings page. */
    private var appliedFont: String? = null

    /**
     * Apply the font chosen in settings (see [TerminalFont]). termux's `setTypeface` itself calls
     * `updateSize()` + `invalidate()`, and a font change that affects row/column also fires the `onEmulatorSet`
     * callback to sync the new size to the remote, so we don't need to add anything here.
     */
    private fun applyFont() {
        appliedFont = Prefs.terminalFont(this)
        b.terminal.setTypeface(TerminalFont.typeface(this))
    }

    /**
     * Apply the color scheme. The renderer only paints up to the grid edge; the leftover region (right/bottom) shows the View background,
     * so the View background has to follow the scheme too (the default black layer shows as a black border under a light scheme);
     * same for the extra-key bar — otherwise a light scheme with a dark key bar looks mismatched.
     */
    private fun applyColors() {
        TermColors.apply(this)
        TermColors.applyToSessions()
        b.terminal.setBackgroundColor(TermColors.bg())
        b.extraKeys.setBackgroundColor(TermColors.keyBarBg())
        // The terminal has its own color scheme (independent of the app theme); the nav bar follows the bottom-most extra-key bar;
        // living in applyColors means it changes together with the terminal scheme.
        NavBarTint.apply(this, TermColors.keyBarBg())
        for (btn in extraKeyButtons) btn.setTextColor(TermColors.fg())
        markMod(ctrlBtn, ctrlPending)
        markMod(altBtn, altPending)
        markMod(shiftBtn, shiftPending)
        b.terminal.onScreenUpdated()
    }

    /** Top-bar "Terminal colors" quick entry. */
    private val colorsPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val name = TermColors.import(this, uri)
            if (name == null) {
                Toast.makeText(this, R.string.msg_colors_invalid, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.msg_colors_applied, name), Toast.LENGTH_SHORT).show()
            }
            applyColors()
        }

    private fun chooseColors() =
        TermColors.showPicker(
            this,
            { colorsPicker.launch(PickerActivity.intent(this, getString(R.string.settings_term_colors))) },
            { applyColors() },
        )

    /** Top-bar "Terminal font" quick entry (the details are also on the settings page, per project convention). */
    private val fontPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val name = TerminalFont.import(this, uri)
            if (name == null) {
                Toast.makeText(this, R.string.msg_font_invalid, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.msg_font_applied, name), Toast.LENGTH_SHORT).show()
            }
            applyFont()
        }

    private fun chooseFont() {
        val custom = TerminalFont.currentName(this)
        val items = if (custom != null) {
            arrayOf(
                getString(R.string.settings_term_font_import),
                getString(R.string.settings_term_font_reset),
            )
        } else {
            arrayOf(getString(R.string.settings_term_font_import))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_term_font))
            .setItems(items) { _, w ->
                // MIME use */*: many file apps don't give ttf the right MIME, pinning it means you can't pick anything.
                if (w == 0) {
                    fontPicker.launch(PickerActivity.intent(this, getString(R.string.settings_term_font)))
                } else {
                    TerminalFont.clear(this)
                    applyFont()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Screen off/on and app switching relayout the window several times before it
     * settles, often ending up at the size it started from. Letting each of those
     * passes resize the emulator wrecks the alternate screen buffer (tmux, htop, vim)
     * while the peer sees no net change and never repaints — see [TermSizeFreezeLayout].
     * So the size is frozen from [onPause] and applied again in **one** step once the
     * layout has been quiet for [THAW_SETTLE_MS] (or [THAW_MAX_MS] has passed, in case
     * layout passes keep trickling in).
     */
    private val thawSize = Runnable {
        if (b.terminalBox.frozen) {
            b.terminalBox.frozen = false
            // The relayout requested above lands next frame; apply the settled size
            // then, in one step (a no-op when it is the size we started from).
            b.terminal.post {
                b.terminal.updateSize()
                logSize("size thaw")
            }
        }
    }

    /** Hard deadline for the debounce above, so a restless layout cannot starve it. */
    private var thawAt = 0L

    /**
     * ★ Only coming back to the foreground arms the thaw; a layout pass may **postpone**
     * an armed one but must never start one. Measured otherwise (2026-08-27): leaving the
     * app restores the status bar, that relayout keeps rescheduling the debounce, and
     * ~300 ms later — with the user already in another app — the thaw fired and applied
     * the shorter size, so we froze again on the *wrong* size and coming back applied the
     * real one: a blank strip at the bottom and the content visibly jumping. The three
     * two exits (focus loss / `onPause`) arrive in no fixed order — measured here, focus
     * loss always came first — so this flag, not their ordering, is what makes it right.
     */
    private var thawArmed = false

    private fun freezeSize(why: String) {
        if (!b.terminalBox.frozen) logSize("size freeze ($why)")
        b.terminalBox.frozen = true
        thawArmed = false
        main.removeCallbacks(thawSize)
    }

    /** This corner is fragile enough to be worth a trace; grep logcat for `size freeze`. */
    private fun logSize(what: String) {
        val e = b.terminal.mEmulator
        Log.i(
            TAG,
            "$what: view=${b.terminal.width}x${b.terminal.height} " +
                "box=${b.terminalBox.width}x${b.terminalBox.height} " +
                "grid=${e?.mColumns}x${e?.mRows}",
        )
    }

    private fun scheduleThaw(restart: Boolean) {
        if (restart) thawArmed = true
        if (!b.terminalBox.frozen || !thawArmed) return
        val now = SystemClock.uptimeMillis()
        if (restart || thawAt == 0L) thawAt = now + THAW_MAX_MS
        main.removeCallbacks(thawSize)
        main.postDelayed(thawSize, (thawAt - now).coerceIn(0L, THAW_SETTLE_MS))
    }

    /** Debounce: the soft-keyboard animation produces a flurry of layout events, only sync the final size after it settles. */
    private val sizeSync = Runnable { syncRemoteSize() }

    private fun scheduleSizeSync() {
        main.removeCallbacks(sizeSync)
        main.postDelayed(sizeSync, 300)
    }

    /**
     * Sync the local emulator's size to the remote PTY (window-change). Per-server capability memory:
     * on `unknown` the first send is a probe; if the connection dies within 3 seconds we mark it `broken` and never send again
     * (auto-reconnect falls back to the old fixed-size behavior); if it survives we mark it `ok` and forward freely.
     * resize() shares a lock with stdin — serialized and deduped.
     *
     * ★ The send has to happen on a background thread: hitting a socket on the main thread throws NetworkOnMainThreadException,
     * but SSHJ has already advanced the outbound packet sequence and cipher-stream state **before** it actually writes the socket —
     * once the exception is swallowed the state is dirty, and the next normal outbound packet (often the first keypress) has a mismatched MAC,
     * so the server closes the TCP. This is the real root cause of "disconnect on the first keypress" reproducing on **every** server,
     * unrelated to the server or the termux-view.
     */
    private fun syncRemoteSize() {
        val t = displayed ?: return
        if (t.isLocal) return // Local PTY size has already been synced by TerminalView.updateSize.
        if (!t.alive) return
        val sh = t.shell ?: return
        if (Prefs.termResizeCap(this, t.scheme) == Prefs.RESIZE_BROKEN) return
        val cols = t.emulator.mColumns.coerceIn(20, 500)
        val rows = t.emulator.mRows.coerceIn(6, 300)
        Thread({
            val sent = sh.resize(cols, rows)
            if (sent && Prefs.termResizeCap(this, t.scheme) == Prefs.RESIZE_UNKNOWN) {
                val gen = t.gen
                main.postDelayed({
                    if (t.closing) return@postDelayed // User actively ended it — can't judge, probe again next time.
                    val ok = t.gen == gen && t.alive && sh.isOpen
                    Prefs.setTermResizeCap(
                        this,
                        t.scheme,
                        if (ok) Prefs.RESIZE_OK else Prefs.RESIZE_BROKEN,
                    )
                    Log.i(TAG, "resize probe ${t.scheme}: ${if (ok) "ok" else "broken"}")
                }, 3000)
            }
        }, "twig-term-resize").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SecurityUi.gate(this) { handleIntent(intent) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            // Screen off takes the window's focus **before** onPause, and the relayout
            // it triggers (the IME is dismissed, the status bar returns for the
            // keyguard) is exactly what must not reach the emulator — freeze here, not
            // one lifecycle callback later. The soft keyboard does not take focus away,
            // so this does not gate the ordinary keyboard show/hide resize.
            freezeSize("unfocused")
            return
        }
        applyFullscreen() // The system may have restored the status bar after switching; re-apply.
        // Hiding the status bar again is itself a layout pass: keep the size frozen
        // until it has landed, otherwise this is the second half of the round trip.
        scheduleThaw(restart = true)
    }

    /** Follow the main screen's "Fullscreen (hide status bar)" setting. */
    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (Prefs.fullscreen(this)) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    /** scheme non-null = an "Open terminal" request from the file manager, always creates a new one; otherwise show the current session. */
    private fun handleIntent(intent: Intent) {
        val scheme = intent.getStringExtra(EXTRA_SCHEME)
        if (scheme == LOCAL_SCHEME) {
            val priv = intent.getIntExtra(EXTRA_PRIV, Privileged.OFF)
            if (priv == Privileged.OFF) openLocal(intent.getStringExtra(EXTRA_DIR))
            else openPrivileged(intent.getStringExtra(EXTRA_DIR))
        } else if (scheme != null) {
            val title = intent.getStringExtra(EXTRA_TITLE)
                ?: TermManager.current?.title ?: scheme
            openNew(scheme, title, intent.getStringExtra(EXTRA_DIR), intent.getStringExtra(EXTRA_CMD))
        } else {
            // When there are no sessions we no longer just exit: open a local shell, so that top-bar Terminal entry
            // can be persistent (tapping in always has something usable).
            val cur = TermManager.current
            if (cur == null) openLocal(intent.getStringExtra(EXTRA_DIR)) else showSession(cur)
        }
    }

    /** "New" menu: open another shell on the server the current session belongs to. */
    private fun newOnCurrentServer() {
        val cur = displayed ?: TermManager.current
        if (cur == null || cur.isLocal) { openLocal(null); return }
        openNew(cur.scheme, cur.title, cwd = null)
    }

    /**
     * Create a **local** shell session: takes termux's original path — `TerminalSession` itself forks /system/bin/sh via
     * `JNI.createSubprocess`, allocates a PTY, and starts read/write threads feeding the emulator, so we need neither a
     * bridge thread nor the connection / reconnect / size-probing rig (local resize is just an ioctl, and `TerminalView.updateSize` already calls it).
     *
     * Limitations: the process runs as this app's uid, not root; available commands are the system-provided mksh + toybox.
     * Since Android 10 you can't execve files in the app's private dir, so scripts have to be run as `sh xxx.sh`.
     */
    private fun openLocal(cwd: String?, priv: Int = Privileged.OFF) {
        val dir = cwd?.takeIf { java.io.File(it).isDirectory }
            ?: android.os.Environment.getExternalStorageDirectory().absolutePath
        val cmd = PrivShell.command(this, priv)
        if (cmd == null) {
            // If it can't start, just say which identity can't start — don't silently fall back to a plain shell —
            // the user thinks they're typing commands as root but is actually under the app uid; that's more dangerous than an error.
            Toast.makeText(this, getString(R.string.terminal_priv_unavailable), Toast.LENGTH_LONG).show()
            if (TermManager.current == null) openLocal(cwd, Privileged.OFF)
            return
        }
        val (exe, args) = cmd
        if (priv != Privileged.OFF) Log.i(PRIV_TAG, "local session priv=$priv exec=$exe args=${args.joinToString(" ")}")
        val title = when (priv) {
            Privileged.ROOT -> getString(R.string.terminal_local_root)
            Privileged.SHIZUKU -> getString(R.string.terminal_local_shizuku)
            else -> getString(R.string.terminal_local)
        }
        val t = TermManager.create(LOCAL_SCHEME, title)
        val env = localEnv(dir)
        val s = TerminalSession(exe, dir, args, env, 5000, sessionClient)
        t.session = s

        // attach lets the view measure out real columns/rows. ★ TerminalView.updateSize → TerminalSession.updateSize
        // **calls initializeEmulator itself** when the emulator is empty (i.e. forks the shell), so this attach step
        // usually starts the process already; we must check below, otherwise calling again forks a second shell
        // and overwrites the first's fd/emulator, leaking them.
        showSession(t)
        b.terminal.postDelayed({
            if (t.closing) return@postDelayed
            if (s.getEmulator() == null) {
                // The view hasn't measured a size yet (updateSize just returns); kick it off once.
                val cols = (b.terminal.mEmulator?.mColumns ?: 80).coerceIn(20, 500)
                val rows = (b.terminal.mEmulator?.mRows ?: 24).coerceIn(6, 300)
                runCatching { s.initializeEmulator(cols, rows) }
                    .onFailure { Log.e(TAG, "local shell", it) }
                b.terminal.attachSession(s)
            }
            val em = s.getEmulator()
            if (em == null) {
                Toast.makeText(this, R.string.terminal_failed, Toast.LENGTH_SHORT).show()
                closeExited(t)
                return@postDelayed
            }
            t.emulator = em
            t.alive = true
            t.connecting = false
            refreshSessions()
        }, 120)
    }

    /**
     * Environment for a local shell.
     *
     * - **Prepend [CmdShims]'s symlink directory to PATH**: the system PATH directories are unreadable to apps
     *   (`drwxr-x--x`, x without r), so Tab completion cannot list any candidates; that directory itself is readable.
     * - **The rest of PATH is inherited from this process** (the chain zygote got from init.environ.rc), so
     *   `/product/bin`, `/system_ext/bin`, `/vendor/bin`, `/apex/…/bin` and other vendor / partition directories
     *   are all there — some ROMs put `ssh`, `curl` under `/product/bin`; hard-coding `/system/bin` would
     *   produce "command not found" for them.
     * - **TERM must be set**, otherwise full-screen programs don't know the terminal capabilities.
     * - **HOME/TMPDIR point to the app's private directory**: external storage cannot host a Unix socket and the
     *   permission bits are fixed — `ssh` requires private keys to be 600, and refuses to use them on /sdcard.
     * - **ENV points to [rcFile]**: mksh sources it on interactive start, where users can add their own PATH / aliases.
     */
    private fun localEnv(dir: String): Array<String> = (
        listOf(
            "TERM=xterm-256color",
            "HOME=" + filesDir.absolutePath,
            "TMPDIR=" + cacheDir.absolutePath,
            "PATH=" + CmdShims.dir(this).absolutePath + ":" +
                (System.getenv("PATH") ?: "/system/bin:/system/xbin"),
            "LANG=en_US.UTF-8",
            "PWD=" + dir,
            "ENV=" + rcFile().absolutePath,
            "EXTERNAL_STORAGE=" + android.os.Environment.getExternalStorageDirectory().absolutePath,
        ) + RUNTIME_ENV.mapNotNull { k -> System.getenv(k)?.let { "$k=$it" } }
        ).toTypedArray()

    /**
     * The mksh startup script ($ENV, sourced by every interactive shell on start). Auto-generated on first use; first
     * sources the system-provided /system/etc/mkshrc (for the prompt etc.), and the user can drop their own PATH / aliases /
     * functions below — a new session picks them up immediately.
     *
     * **Do not try to set `HISTFILE`** (settled after an empirical test on 2026-08-06): Android's bundled
     * `/system/bin/sh` is mksh R59 built with `HAVE_PERSISTENT_HISTORY=0`; `strings` shows **no `HISTFILE` token at all**
     * (only `HISTSIZE`) — setting it neither writes nor reads (pre-creating the file and passing it via env, `fc -l` still
     * says "no history (yet)"). History is a pure in-memory array, and `fc` has no bash `history -r`-style load command, so
     * **cross-session history on the bundled shell is impossible**; the app layer can't fix it either, since the only path
     * is PTY input, which executes immediately. To really do it you'd have to ship a shell with persistent history (placed
     * in nativeLibraryDir to bypass W^X), which conflicts with size-first — evaluated and skipped.
     * `HISTSIZE` does work; use it to extend in-session history.
     *
     * File contents are always in English: the file lives on disk and the user edits it themselves, so unlike UI copy it
     * can't follow the language switch.
     */
    private fun rcFile(): java.io.File {
        val rc = java.io.File(filesDir, ".mkshrc")
        if (!rc.exists()) {
            runCatching {
                rc.writeText(
                    "# Twig local shell startup script (mksh \$ENV) — sourced by every new session.\n" +
                        "# Put your own PATH / aliases / functions below; a new session picks them up.\n" +
                        "[ -f /system/etc/mkshrc ] && . /system/etc/mkshrc\n" +
                        "\n" +
                        "# Longer in-session history. Note there is no cross-session history:\n" +
                        "# Android's /system/bin/sh is built without persistent history support\n" +
                        "# (no HISTFILE at all), so history lives in memory only and is gone\n" +
                        "# when the session closes. Setting HISTFILE here would do nothing.\n" +
                        "export HISTSIZE=5000\n" +
                        "\n" +
                        "alias ll='ls -lAh'\n" +
                        "alias l='ls -CF'\n" +
                        "\n" +
                        "# Up/Down search history by what you already typed (mksh binds these to\n" +
                        "# PageUp/PageDown by default, which no phone keyboard has). With an empty\n" +
                        "# line they behave exactly like plain up-history/down-history.\n" +
                        "# '^[[' is normal cursor keys, '^[O' the application-mode variant.\n" +
                        "bind '^[[A'=search-history-up 2>/dev/null\n" +
                        "bind '^[[B'=search-history-down 2>/dev/null\n" +
                        "bind '^[OA'=search-history-up 2>/dev/null\n" +
                        "bind '^[OB'=search-history-down 2>/dev/null\n" +
                        "\n" +
                        "# \$HOME/bin holds symlinks to system commands, generated by Twig and\n" +
                        "# prepended to PATH. Reason: /system/bin & friends are drwxr-x--x, so an\n" +
                        "# app uid may run a command by name but cannot list the directory — Tab\n" +
                        "# completion would find no candidates at all. Anything missing (a vendor\n" +
                        "# tool Twig doesn't know about) can be added by hand and is kept:\n" +
                        "#   ln -sf \"\$(command -v somecmd)\" \$HOME/bin/\n" +
                        "\n" +
                        "# ssh & friends resolve '~' via getpwuid(), which is /data (not writable)\n" +
                        "# on Android — \$HOME is ignored, so a plain ~/.ssh never works. Twig keeps\n" +
                        "# \$HOME/.ssh/config (absolute paths inside it); these point ssh at it.\n" +
                        "alias ssh='ssh -F \$HOME/.ssh/config'\n" +
                        "alias scp='scp -F \$HOME/.ssh/config'\n" +
                        "alias sftp='sftp -F \$HOME/.ssh/config'\n" +
                        "\n" +
                        "# New keys land in \$HOME/.ssh as well. Modes where -f means something\n" +
                        "# other than \"the key to create\" (-R/-F rewrite known_hosts, -l/-y/-p/-e/-i\n" +
                        "# read an existing key) are passed through untouched, as is your own -f.\n" +
                        "ssh-keygen() {\n" +
                        "    local a\n" +
                        "    for a in \"\$@\"; do\n" +
                        "        case \$a in\n" +
                        "        -*[fRFlypeiAQ]*) command ssh-keygen \"\$@\"; return ;;\n" +
                        "        esac\n" +
                        "    done\n" +
                        "    command ssh-keygen -f \"\$HOME/.ssh/id_ed25519\" \"\$@\"\n" +
                        "}\n",
                )
            }
        }
        return rc
    }

    /**
     * Create a session: first attach so the terminal view computes its real columns/rows, then open the shell at that size.
     * Subsequent size changes are forwarded selectively by [syncRemoteSize] based on the server's capability (probed + remembered).
     */
    private fun openNew(scheme: String, title: String, cwd: String?, command: String? = null) {
        val fs = runCatching { FsRegistry.of(scheme) }.getOrNull() as? SftpFileSystem
        if (fs == null) {
            Toast.makeText(this, R.string.terminal_failed, Toast.LENGTH_SHORT).show()
            if (TermManager.isEmpty()) finish()
            return
        }
        val t = TermManager.create(scheme, title)
        // Don't call initializeEmulator (that would JNI-launch a local process); inject the emulator by reflection.
        val s = TerminalSession("/system/bin/sh", "/", arrayOf(), arrayOf(), 5000, sessionClient)
        val output = SshOutput()
        val emulator = TerminalEmulator(output, 80, 24, 5000, sessionClient)
        TerminalSession::class.java.getDeclaredField("mEmulator")
            .apply { isAccessible = true }.set(s, emulator)
        // write() only enqueues when mShellPid>0
        TerminalSession::class.java.getDeclaredField("mShellPid")
            .apply { isAccessible = true }.setInt(s, 1)
        // Emulator replies (cursor position queries etc.) also go to the input queue; stdin has the bridge thread as its sole writer.
        output.redirect = { d, o, c -> s.write(d, o, c) }
        t.session = s
        t.emulator = emulator

        // Attach first: let the view lay out and update the emulator size to real columns/rows (local, no SSH).
        showSession(t)

        // After layout stabilizes, take the real size and use it to open the shell in one shot (no further resize from here).
        // Even if we've already returned to the file manager (isDestroyed), still establish the connection —
        // the session can be used in the background and resumed when we come back.
        b.terminal.postDelayed({
            if (!t.connecting) return@postDelayed // ended before connection
            val cols = emulator.mColumns.coerceIn(20, 500)
            val rows = emulator.mRows.coerceIn(6, 300)
            Thread({
                val sh = runCatching { fs.openShell(cols, rows) }
                    .onFailure { Log.e(TAG, "openShell", it) }
                    .getOrNull()
                main.post {
                    if (TermManager.list().none { it === t }) { sh?.close(); return@post }
                    if (sh == null) {
                        if (!isDestroyed) {
                            Toast.makeText(this, R.string.terminal_failed, Toast.LENGTH_SHORT).show()
                        }
                        val next = TermManager.remove(t)
                        if (displayed === t) {
                            displayed = null
                            if (next == null) finish() else showSession(next)
                        } else {
                            refreshSessions()
                        }
                    } else {
                        wire(t, sh, cwd, command)
                    }
                }
            }, "twig-term-connect").start()
        }, 120)
    }

    /**
     * Open a privileged session. Two identities, different in form, but the same thing to the caller:
     *  - **root**: `su` directly starts a root shell in termux's own locally-forked PTY;
     *  - **Shizuku**: the PTY is allocated by the privileged process and the fd is passed back (see [openPrivilegedPty]).
     */
    private fun openPrivileged(cwd: String?) {
        when (Privileged.active) {
            Privileged.ROOT -> openLocal(cwd, Privileged.ROOT)
            Privileged.SHIZUKU -> openPrivilegedPty(cwd)
            else -> Toast.makeText(this, R.string.terminal_priv_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The session's process exited.
     *
     * ★ **Don't clean up a session that died right after starting**: that's a startup failure, not the user typing
     * `exit`, and the failure reason is right here on this screen. Cleaning it up immediately would just flash back
     * to the file list, taking the only clue with it.
     */
    private fun onSessionEnded(t: TermSession) {
        val quick = System.currentTimeMillis() - t.startedAt < QUICK_EXIT_MS
        if (!quick) {
            closeExited(t)
            return
        }
        Log.w(PRIV_TAG, "session '${t.title}' exited immediately; keeping it for the message")
        t.alive = false
        t.connecting = false
        val note = "\r\n" + getString(R.string.terminal_exited_quickly) + "\r\n"
        runCatching { t.emulatorOrNull?.append(note.toByteArray(), note.toByteArray().size) }
        refreshSessions()
        if (t === displayed) b.terminal.onScreenUpdated()
    }

    /**
     * Open a **privileged PTY** session (Shizuku identity).
     *
     * The only difference from a local session: the PTY is not forked by us, but by [com.twig.app.priv.TwigPrivService]
     * running in the privileged process via `forkpty`, and the **master-side fd** is passed back via ParcelFileDescriptor.
     * From then on everything is the same — so we reuse the SSH path here (inject emulator + two bridge threads),
     * not the local path (termux forks itself).
     *
     * ★ Why this detour: the rish route (app_process loading Shizuku's dex inside our process) is blocked by SELinux —
     * `untrusted_app` is not allowed to load files labelled `app_data_file`. But the helper runs as **our own APK**
     * (`/data/app`, `apk_data_file`), and is not subject to that limit.
     */
    private fun openPrivilegedPty(cwd: String?) {
        val dir = cwd?.takeIf { it.isNotBlank() } ?: "/"
        val t = TermManager.create(LOCAL_SCHEME, getString(R.string.terminal_local_shizuku))
        val s = TerminalSession("/system/bin/sh", "/", arrayOf(), arrayOf(), 5000, sessionClient)
        val output = SshOutput()
        val emulator = TerminalEmulator(output, 80, 24, 5000, sessionClient)
        TerminalSession::class.java.getDeclaredField("mEmulator")
            .apply { isAccessible = true }.set(s, emulator)
        TerminalSession::class.java.getDeclaredField("mShellPid")
            .apply { isAccessible = true }.setInt(s, 1)
        output.redirect = { d, o, c -> s.write(d, o, c) }
        t.session = s
        t.emulator = emulator

        showSession(t)
        b.terminal.postDelayed({
            if (!t.connecting) return@postDelayed
            val cols = emulator.mColumns.coerceIn(20, 500)
            val rows = emulator.mRows.coerceIn(6, 300)
            val apk = com.twig.app.priv.PrivService.apkPath(this)
            val abi = com.twig.app.priv.PrivService.abi()
            val env = privEnv(dir)
            Thread({
                val pidOut = IntArray(1)
                val pfd = runCatching {
                    val svc = com.twig.app.priv.PrivService.get()
                        ?: error("could not bind the Shizuku helper")
                    Log.i(PRIV_TAG, "helper uid=${svc.uid}")
                    svc.start(apk, abi, "/system/bin/sh", dir, env, rows, cols, pidOut)
                        ?: error("helper could not allocate a pty")
                }.onFailure { Log.w(PRIV_TAG, "privileged pty failed", it) }.getOrNull()
                main.post {
                    if (TermManager.list().none { it === t }) {
                        runCatching { pfd?.close() }
                        return@post
                    }
                    if (pfd == null) {
                        if (!isDestroyed) {
                            Toast.makeText(this, R.string.terminal_priv_unavailable, Toast.LENGTH_LONG).show()
                        }
                        closeExited(t)
                    } else {
                        wirePty(t, pfd, pidOut[0], cwd)
                    }
                }
            }, "twig-term-priv").start()
        }, 120)
    }

    /**
     * Environment for a privileged shell. **Don't just copy the local one** — `HOME`/`TMPDIR` point at our app's
     * private directory, which the helper cannot read while running as shell (0700, owned by the app uid).
     * They point at `/data/local/tmp/twig` instead: shell and root can both write there.
     */
    private fun privEnv(dir: String): Array<String> = (
        listOf(
            "TERM=xterm-256color",
            "HOME=/data/local/tmp/twig",
            "TMPDIR=/data/local/tmp/twig",
            "PATH=" + (System.getenv("PATH") ?: "/system/bin:/system/xbin"),
            "LANG=en_US.UTF-8",
            "PWD=" + dir,
        ) + RUNTIME_ENV.mapNotNull { k -> System.getenv(k)?.let { "$k=$it" } }
        ).toTypedArray()

    /**
     * Connect the PTY master end returned by the helper to the emulator.
     *
     * ★ While we're at it, inject the real fd into `mTerminalFileDescriptor` by reflection: termux's
     * `TerminalSession.updateSize` already reads that field and calls `setPtyWindowSize`, so once it's set,
     * **window-size sync needs zero extra code** — orientation changes / keyboard show/hide automatically carry
     * SIGWINCH. SSH has to fall back to [syncRemoteSize] (probing + remembering) because there's no local fd to use there.
     */
    private fun wirePty(t: TermSession, pfd: android.os.ParcelFileDescriptor, pid: Int, cwd: String?) {
        val s = t.session
        val emulator = t.emulator
        t.privFd = pfd
        t.privPid = pid
        t.alive = true
        t.connecting = false
        val myGen = ++t.gen
        runCatching {
            TerminalSession::class.java.getDeclaredField("mTerminalFileDescriptor")
                .apply { isAccessible = true }.setInt(s, pfd.fd)
        }.onFailure { Log.w(PRIV_TAG, "cannot inject pty fd; resize will not reach the shell", it) }
        refreshSessions()

        val out = java.io.FileOutputStream(pfd.fileDescriptor)
        val ins = java.io.FileInputStream(pfd.fileDescriptor)
        val readInput = TermBridge.inputReader(s)
        Thread({
            val buf = ByteArray(4096)
            try {
                while (!t.closing && t.gen == myGen) {
                    val n = readInput(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w(PRIV_TAG, "pty stdin bridge ended", e)
            }
        }, "twig-priv-in").start()

        Thread({
            val buf = ByteArray(8192)
            try {
                while (t.gen == myGen) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    val chunk = buf.copyOf(n)
                    main.post {
                        emulator.append(chunk, chunk.size)
                        t.onOutput?.invoke()
                    }
                }
            } catch (e: Exception) {
                // When the shell exits, reading from the master end throws EIO — that's normal teardown, not a fault.
                Log.i(PRIV_TAG, "pty closed: ${e.message}")
            }
            if (t.gen == myGen && !t.closing) main.post { onSessionEnded(t) }
        }, "twig-priv-out").start()

        cwd?.let { s.write(cdCommand(it)) }
    }

    /**
     * Wire up the bridges once the shell is (re)ready. The stdin bridge thread is started only on the first
     * connection ([myGen] == 1) and lives for the whole TermSession — reconnecting only swaps [TermSession.shell]
     * to a new connection, to avoid a second stdin thread racing for the same key queue during reconnect
     * (whoever grabs first is non-deterministic and keys would be lost). The stdout bridge thread starts one
     * per physical connection and ends with it (on disconnect / connection swap).
     */
    private fun wire(t: TermSession, sh: SftpFileSystem.ShellSession, cwd: String?, command: String? = null) {
        val s = t.session
        val emulator = t.emulator
        val myGen = ++t.gen
        t.shell = sh
        t.alive = true
        t.connecting = false
        refreshSessions()
        // Sync the size once after connect: unknown servers complete probing here (the shell just started, no
        // full-screen program running — safest moment to probe); after a disconnect + reconnect the local size may
        // have changed, this also fixes that.
        if (displayed === t) scheduleSizeSync()

        if (myGen == 1) {
            val readInput = TermBridge.inputReader(s)
            // Key input → session queue → SSH stdin (sole writer for the entire session lifetime)
            Thread({
                val buf = ByteArray(4096)
                try {
                    while (!t.closing) {
                        val n = readInput(buf)
                        if (n <= 0) break
                        t.shell?.write(buf, 0, n) // shell is null between reconnects, drop silently
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stdin bridge", e)
                }
            }, "twig-term-in").start()
        }

        // SSH stdout → main thread → emulator (accumulates while the UI is gone, picked up on return)
        Thread({
            val buf = ByteArray(8192)
            try {
                while (t.gen == myGen) {
                    val n = sh.stdout.read(buf)
                    if (n < 0) break
                    val chunk = buf.copyOf(n)
                    main.post {
                        emulator.append(chunk, chunk.size)
                        t.onOutput?.invoke()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "stdout bridge", e)
            }
            // Only need to inspect this EOF when it's neither user-initiated termination nor replaced by an updated connection
            if (t.gen == myGen && !t.closing) {
                if (sh.exitedCleanly()) {
                    main.post { if (t.gen == myGen) closeExited(t) }
                } else {
                    reconnect(t, myGen)
                }
            }
        }, "twig-term-out").start()

        cwd?.let { s.write(cdCommand(it)) }
        // Command shortcut: after `cd`, type the command in (with Enter, like the user typed it themselves; output scrolls as usual)
        command?.takeIf { it.isNotBlank() }?.let { s.write(it.trimEnd() + "\r") }
    }

    /**
     * Win32-OpenSSH's SFTP root is virtual ("/" lists drive letters), and Windows paths in this app also get
     * composed as "/D:/bin" with a leading slash — so we can't just check whether the path starts with "/".
     * The real signal is "slash followed by a single drive letter + colon" ([WINDOWS_PATH]). When that matches,
     * strip the artificial leading slash (cmd.exe doesn't accept "/D:/bin", it has to be "D:/bin"); the default
     * shell is cmd.exe: it doesn't honour single-quote escapes, and crossing drives requires `cd /d`.
     */
    private fun cdCommand(dir: String): String =
        if (WINDOWS_PATH.containsMatchIn(dir)) {
            "cd /d \"${dir.removePrefix("/").replace("\"", "")}\" && cls\r"
        } else {
            "cd ${shq(dir)} && clear\r"
        }

    /**
     * The remote shell exited on its own (`exit` / Ctrl+D): don't reconnect, just tear this session down —
     * same end state as manually choosing "End current session". If the exited session was current, switch
     * to a neighbouring one; with none left, exit the terminal page. Background sessions just need to be
     * removed from the list.
     */
    private fun closeExited(t: TermSession) {
        val wasDisplayed = t === displayed
        val next = TermManager.remove(t) // internally close(), which also closes the connection
        if (isDestroyed) return // UI already gone, just remove
        if (!wasDisplayed) { refreshSessions(); return }
        displayed = null
        if (next == null) finish() else showSession(next)
    }

    /**
     * Reconnect after a drop: a screen-off / app-background event being silently disconnected by the system
     * or carrier NAT is common — back off and retry a few times; only mark "ended" if all attempts fail.
     * [myGen] is the connection generation captured at disconnect time; if an updated connection has
     * already taken over, or the user manually ended this session, abandon immediately.
     */
    private fun reconnect(t: TermSession, myGen: Int) {
        t.shell = null
        main.post { if (t.gen == myGen) { t.alive = false; t.connecting = true; refreshSessions() } }
        val fs = runCatching { FsRegistry.of(t.scheme) }.getOrNull() as? SftpFileSystem
        var sh: SftpFileSystem.ShellSession? = null
        if (fs != null) {
            for (attempt in 0 until 3) {
                if (t.gen != myGen || t.closing) { sh?.close(); return }
                Thread.sleep(3000L * (attempt + 1))
                if (t.gen != myGen || t.closing) { sh?.close(); return }
                val cols = t.emulator.mColumns.coerceIn(20, 500)
                val rows = t.emulator.mRows.coerceIn(6, 300)
                sh = runCatching { fs.openShell(cols, rows) }.getOrNull()
                if (sh != null) break
            }
        }
        main.post {
            if (t.gen != myGen || t.closing) { sh?.close(); return@post }
            if (sh != null) {
                wire(t, sh, null)
            } else {
                t.alive = false
                t.connecting = false
                refreshSessions()
                t.onOutput?.invoke()
            }
        }
    }

    /** Switch to [t] and bind the view: unbind the previous session's callback, register the new one's refresh callback. */
    private fun showSession(t: TermSession) {
        val prev = displayed
        if (prev !== t) prev?.onOutput = null
        displayed = t
        TermManager.select(t)
        t.onOutput = { if (!isDestroyed) b.terminal.onScreenUpdated() }
        runCatching { t.session.updateTerminalSessionClient(sessionClient) }
        b.terminal.attachSession(t.session)
        b.terminal.post { maybeShowIme() }
        scheduleSizeSync() // attach will change this session's local emulator size to the current view size
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription("SSH · ${t.title}"))
        refreshSessions()
    }

    /** End the current session: close and remove from the list, switch to a neighbour; exit if none left. */
    private fun endCurrent() {
        val t = displayed ?: return
        val next = TermManager.remove(t)
        if (t === displayed) displayed = null
        if (next == null) { finish(); return }
        showSession(next)
    }

    /** Rebuild the dropdown list (titles + connection/closed state markers) and align selection with the current session. */
    private fun refreshSessions() {
        if (isDestroyed) return
        val list = TermManager.list()
        if (list.isEmpty()) { finish(); return }
        val labels = list.map { it.title + statusTag(it) }
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, labels,
        ) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getView(pos, cv, parent) as TextView).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                }
            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getDropDownView(pos, cv, parent) as TextView).apply {
                    setTextColor(0xFFEEEEEE.toInt())
                }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        suppressSpinner = true
        b.sessionSpinner.adapter = adapter
        val idx = list.indexOfFirst { it === displayed }
        if (idx >= 0) b.sessionSpinner.setSelection(idx)
        b.sessionSpinner.post { suppressSpinner = false }
    }

    private fun statusTag(t: TermSession): String = when {
        t.connecting -> getString(R.string.terminal_connecting_tag)
        !t.alive -> getString(R.string.terminal_closed_tag)
        else -> ""
    }

    private fun focusTerminal() {
        b.terminal.isFocusable = true
        b.terminal.isFocusableInTouchMode = true
        b.terminal.requestFocus()
    }

    /** Force the keyboard up (regardless of cursor state — manual fallback when [maybeShowIme] guesses wrong). */
    private fun showIme() {
        focusTerminal()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(b.terminal, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideIme() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.terminal.windowToken, 0)
    }

    /** Toolbar "keyboard" button: if it's up, hide it; if it's hidden, show it. */
    private fun toggleIme() {
        if (imeShown()) hideIme() else showIme()
        // Show/hide only reflects on insets after layout finishes — icon sync is left to OnGlobalLayoutListener.
    }

    /**
     * Is the keyboard currently shown? API 30+ has an authoritative answer (ime visibility on `WindowInsets`);
     * on older systems `WindowInsetsCompat.isVisible(ime())` is just a placeholder that always returns true,
     * so we have to fall back to "how much was the visible window area squeezed from below" — this page
     * uses `adjustResize`, so popping the keyboard always shaves a big chunk off the visible area; that
     * heuristic is enough (threshold at 1/5 screen height, dodging nav bar / punch-hole's tens of pixels).
     */
    private fun imeShown(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.decorView.rootWindowInsets?.let {
                return it.isVisible(android.view.WindowInsets.Type.ime())
            }
        }
        val r = android.graphics.Rect()
        val root = b.root
        root.getWindowVisibleDisplayFrame(r)
        val screenH = root.rootView.height
        return screenH > 0 && screenH - r.bottom > screenH / 5
    }

    /** Keyboard button's icon follows the actual state: when up, swap to the "hide" one. */
    private fun syncKeyboardIcon() {
        val shown = imeShown()
        if (shown == keyboardIconShown) return
        keyboardIconShown = shown
        val res = if (shown) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard
        b.toolbar.menu.findItem(MENU_KEYBOARD)?.icon =
            ContextCompat.getDrawable(this, res)?.mutate()?.apply { setTint(Color.WHITE) }
    }

    /** The state [syncKeyboardIcon] last set, to avoid rebuilding the drawable on every layout. */
    private var keyboardIconShown = false

    /**
     * Pop the keyboard on demand when tapping the terminal / switching sessions: full-screen programs (htop/less/vim etc.)
     * usually hide the cursor (DECTCEM), so the program is most likely not waiting for input — not popping matches Termux's feel;
     * cases where the cursor is visible (shell prompt, etc.) still pop as usual.
     */
    private fun maybeShowIme() {
        focusTerminal()
        if (displayed?.emulatorOrNull?.isCursorEnabled != false) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(b.terminal, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private val TerminalSession.emulator: TerminalEmulator?
        get() = runCatching { getEmulator() }.getOrNull()

    /** The emulator's write-back channel (terminal replies like cursor-position queries); delivered to the input queue via `redirect`. */
    private class SshOutput : TerminalOutput() {
        var redirect: ((ByteArray, Int, Int) -> Unit)? = null
        override fun write(data: ByteArray, offset: Int, count: Int) {
            runCatching { redirect?.invoke(data, offset, count) }
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun copyToClipboard(text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        displayed?.session?.emulator?.paste(text)
    }

    private inner class SessionClient : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) {
            if (!isDestroyed && s === displayed?.session) b.terminal.onScreenUpdated()
        }
        override fun onTitleChanged(s: TerminalSession) = Unit
        /**
         * Local shell process exited (exit / Ctrl+D): same as a normal SSH exit, tear the session down.
         *
         * ★ **But don't tear down a session that died right after starting**: that's a startup failure, not the user
         * typing `exit`, and the failure reason is right here on this screen. Cleaning it up immediately would just
         * flash back to the file list, taking the only clue with it (this is exactly what rish/su failure looks like).
         * Keep the session so the message stays readable, and have it land in logcat too.
         */
        override fun onSessionFinished(s: TerminalSession) {
            val t = TermManager.list().firstOrNull { it.session === s } ?: return
            if (t.closing) return
            main.post { onSessionEnded(t) }
        }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) = copyToClipboard(text)
        override fun onPasteTextFromClipboard(s: TerminalSession) = pasteFromClipboard()
        override fun onBell(s: TerminalSession) = Unit
        override fun onColorsChanged(s: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {
            Log.e(TAG, "$tag: $message")
        }
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(TAG, "$tag: $message", e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(TAG, "stack", e)
        }
    }

    private inner class ViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale > 0.95f && scale < 1.05f) return scale // accumulate enough change before moving, avoid jitter
            var next = (textSizePx * scale).roundToInt()
            // At small font sizes, ×1.05 may round back to the original value and stall; nudge by at least one step
            if (next == textSizePx) next += if (scale > 1f) 1 else -1
            if (next != textSizePx) applyTextSize(next)
            return 1.0f // baseline has switched to the new font size, accumulated factor reset to zero
        }
        override fun onSingleTapUp(e: MotionEvent) = maybeShowIme()
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey(): Boolean = ctrlPending.also { if (it) main.post { setCtrl(false) } }
        override fun readAltKey(): Boolean = altPending.also { if (it) main.post { setAlt(false) } }
        override fun readShiftKey(): Boolean = shiftPending.also { if (it) main.post { setShift(false) } }
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        /**
         * termux only calls back here when columns/rows actually change. Font-size pinch zoom is exactly that
         * path — `setTextSize` does not `requestLayout()`, `OnGlobalLayoutListener` doesn't fire, so a listener-only
         * approach doesn't deliver the new size to the remote PTY; the program keeps writing to the old cols/rows
         * (and doesn't send SIGWINCH to redraw), so the screen retains content painted at the old width.
         */
        override fun onEmulatorSet() = scheduleSizeSync()
        override fun logError(tag: String?, message: String?) {
            Log.e(TAG, "$tag: $message")
        }
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(TAG, "$tag: $message", e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(TAG, "stack", e)
        }
    }

    // ---- Extra key bar ----

    /** All extra-key buttons — text colour is changed in one place when re-colouring. */
    private val extraKeyButtons = ArrayList<Button>()

    private var ctrlBtn: Button? = null
    private var altBtn: Button? = null
    private var shiftBtn: Button? = null

    private fun setCtrl(on: Boolean) {
        ctrlPending = on
        markMod(ctrlBtn, on)
    }

    private fun setAlt(on: Boolean) {
        altPending = on
        markMod(altBtn, on)
    }

    private fun setShift(on: Boolean) {
        shiftPending = on
        markMod(shiftBtn, on)
    }

    /**
     * The modifier's pressed state is shown by "background colour + bold", no longer by translucency —
     * applying alpha darkens the foreground (which is whatever the scheme foreground is, not necessarily bright white),
     * making it too faint to read. All keys are fully opaque.
     */
    private fun markMod(btn: Button?, on: Boolean) {
        btn ?: return
        btn.setBackgroundColor(if (on) TermColors.keyActiveBg() else Color.TRANSPARENT)
        btn.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    @SuppressLint("SetTextI18n")
    private fun buildExtraKeys() {
        fun key(label: String, onClick: (View) -> Unit): Button =
            Button(this).apply {
                text = label
                isAllCaps = false
                maxLines = 1
                textSize = KEY_TEXT_SP
                // A key cell is only ~45dp wide; the default button style spends 16dp of
                // that on padding at each side and reserves an 88dp minWidth, so at a
                // large system font scale "SHIFT"/"PGUP" get clipped. The gap between
                // labels is spacing enough — give the whole cell to the label.
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setTextColor(TermColors.fg())
                background = null
                // Key: don't steal terminal focus, otherwise the soft keyboard's target drifts and key routing goes wrong
                isFocusable = false
                isFocusableInTouchMode = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                // Arrow keys auto-repeat when held (see HoldRepeat); it consumes touch itself, so no click listener
                // can be attached, otherwise the click on release would add an extra key event after the long-press ends
                if (label in REPEAT_KEYS) {
                    HoldRepeat.install(this, main) { onClick(this) }
                } else {
                    setOnClickListener(onClick)
                }
            }

        // pending modifier states are only consumed by the soft keyboard via readControlKey/...; virtual keys
        // must take and clear them themselves, otherwise combinations like SHIFT+TAB would never go out
        fun takeMods(): Int {
            var m = 0
            if (ctrlPending) m = m or KeyHandler.KEYMOD_CTRL
            if (altPending) m = m or KeyHandler.KEYMOD_ALT
            if (shiftPending) m = m or KeyHandler.KEYMOD_SHIFT
            if (m != 0) { setCtrl(false); setAlt(false); setShift(false) }
            return m
        }
        fun sendKey(code: Int) {
            if (b.terminal.mEmulator == null) return // avoid NPE when the view isn't ready yet
            b.terminal.handleKeyCode(code, takeMods())
        }
        fun sendBytes(str: String) {
            var s = str
            val mods = takeMods()
            if (s.length == 1 && mods and KeyHandler.KEYMOD_CTRL != 0) {
                val c = s[0].uppercaseChar().code
                if (c in 0x40..0x7e) s = (c and 0x1f).toChar().toString()
            }
            if (mods and KeyHandler.KEYMOD_ALT != 0) s = "\u001b" + s
            displayed?.session?.write(s)
        }
        val row1 = listOf<Pair<String, (View) -> Unit>>(
            "ESC" to { _ -> sendBytes("\u001b") },
            "/" to { _ -> sendBytes("/") },
            "|" to { _ -> sendBytes("|") },
            "-" to { _ -> sendBytes("-") },
            "HOME" to { _ -> sendKey(KeyEvent.KEYCODE_MOVE_HOME) },
            "▲" to { _ -> sendKey(KeyEvent.KEYCODE_DPAD_UP) },
            "END" to { _ -> sendKey(KeyEvent.KEYCODE_MOVE_END) },
            "PGUP" to { _ -> sendKey(KeyEvent.KEYCODE_PAGE_UP) },
        )
        val row2 = listOf<Pair<String, (View) -> Unit>>(
            "TAB" to { _ -> sendKey(KeyEvent.KEYCODE_TAB) },
            "CTRL" to { _ -> setCtrl(!ctrlPending) },
            "SHIFT" to { _ -> setShift(!shiftPending) },
            "ALT" to { _ -> setAlt(!altPending) },
            "◀" to { _ -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT) },
            "▼" to { _ -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN) },
            "▶" to { _ -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
            "PGDN" to { _ -> sendKey(KeyEvent.KEYCODE_PAGE_DOWN) },
        )

        fun addRow(keys: List<Pair<String, (View) -> Unit>>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for ((label, action) in keys) {
                val btn = key(label, action)
                when (label) {
                    "CTRL" -> ctrlBtn = btn
                    "ALT" -> altBtn = btn
                    "SHIFT" -> shiftBtn = btn
                }
                extraKeyButtons += btn
                row.addView(btn)
            }
            b.extraKeys.addView(row)
        }
        addRow(row1)
        addRow(row2)
        // The cell width is only known after layout, and it changes on rotation.
        b.extraKeys.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitExtraKeyText() }
    }

    /**
     * Give every key the one text size at which the widest label still fits its cell
     * (see [TermKeyFit]). Called from layout, so it must be a no-op once it has settled:
     * the size does not change the cells, so the next pass computes the same value.
     */
    private fun fitExtraKeyText() {
        val cell = extraKeyButtons.firstOrNull()?.width ?: return
        val dm = resources.displayMetrics
        fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
        // Measure bold: a modifier key turns bold while armed, which is its widest state.
        val probe = TextPaint(extraKeyButtons[0].paint)
        probe.typeface = Typeface.create(extraKeyButtons[0].typeface, Typeface.BOLD)
        val size = TermKeyFit.textSize(
            cell, extraKeyButtons.map { it.text }, probe,
            fullPx = sp(KEY_TEXT_SP), minPx = dp(KEY_TEXT_MIN_DP), padPx = dp(2f),
        )
        for (btn in extraKeyButtons) {
            if (kotlin.math.abs(btn.textSize - size) > 0.5f) {
                btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.terminalFont(this) != appliedFont) applyFont() // font was just changed on the settings page
        scheduleThaw(restart = true)
    }

    override fun onPause() {
        super.onPause()
        // Leaving the foreground (screen off, another app): stop letting layout resize
        // the emulator until we are back and the window has settled. See [thawSize].
        freezeSize("pause")
        // Writing to SharedPreferences on every frame during zoom is unnecessary; commit once when leaving the page
        if (textSizePx > 0) Prefs.setTerminalTextSize(this, textSizePx)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Sessions stay in TermManager; only drop the references held by this UI
        displayed?.onOutput = null
    }

    companion object {
        private const val TAG = "TwigTerm"
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_CMD = "cmd"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PRIV = "priv"
        /** Layout must be quiet this long after a resume before the size is applied. */
        private const val THAW_SETTLE_MS = 250L

        /** …but never wait longer than this, however restless the layout is. */
        private const val THAW_MAX_MS = 1500L

        /** Normal font size for extra-key labels (sp, follows system font scaling; [fitExtraKeyText] shrinks them uniformly when they don't fit). */
        private const val KEY_TEXT_SP = 12f

        /** Lower bound (dp, no longer following font scaling) — won't shrink below this. */
        private const val KEY_TEXT_MIN_DP = 8f

        /** Keys that auto-repeat when held: arrow keys. Other extra keys are either modifiers or have no meaning when repeated. */
        private val REPEAT_KEYS = setOf("▲", "▼", "◀", "▶")

        private const val MENU_NEW = 1
        private const val MENU_KEYBOARD = 2
        private const val MENU_END_CURRENT = 3
        private const val MENU_END_ALL = 4
        private const val MENU_KEEP_AWAKE = 5
        private const val MENU_FONT = 6
        private const val MENU_COLORS = 7
        private const val MENU_NEW_LOCAL = 8
        private const val MENU_NEW_PRIV = 9

        /** A local session that exits within this duration is treated as "failed to start", not "user exited". */
        private const val QUICK_EXIT_MS = 3000L

        /** Same tag as Privileged — privileged-related events show up under one filter. */
        private const val PRIV_TAG = "twig-priv"

        /**
         * Runtime environment variables passed through from this process as-is.
         *
         * ★ [localEnv] **replaces** the entire environment, not appends to it — only setting TERM/HOME/PATH means
         * **anything that has to launch ART exits silently within a second with no output**: `app_process` can't find
         * `ANDROID_ROOT` / `ANDROID_DATA` / `ANDROID_ART_ROOT` / `BOOTCLASSPATH` and dies immediately, **without printing
         * a single character** (verified 2026-08-16: same command with full env runs, with a stripped env exits 0 with no
         * output). The screen just shows termux's "[Process completed]", looking like "command not found".
         *
         * It's not just rish that's affected: `am` / `pm` / `dumpsys` / `settings` are all `app_process` wrapper scripts,
         * and without these variables they fail silently in the local terminal.
         *
         * Whitelist only these, don't inherit the full set: `ANDROID_SOCKET_*` etc. are the parent process's private
         * fd conventions, passing them to children is pointless and risks misuse.
         */
        private val RUNTIME_ENV = listOf(
            "ANDROID_ROOT",
            "ANDROID_DATA",
            "ANDROID_ART_ROOT",
            "ANDROID_I18N_ROOT",
            "ANDROID_TZDATA_ROOT",
            "ANDROID_ASSETS",
            "ANDROID_STORAGE",
            "BOOTCLASSPATH",
            "DEX2OATBOOTCLASSPATH",
            "SYSTEMSERVERCLASSPATH",
        )

        /** The system shell (mksh); toybox commands all live under /system/bin. */
        private const val MIN_TEXT_PX = 18
        private const val MAX_TEXT_PX = 96

        /** Windows drive-letter paths where `join()` has forced a leading slash: "/D:", "/D:/bin", etc. */
        private val WINDOWS_PATH = Regex("^/[A-Za-z]:(/|$)")

        private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

        fun start(
            context: Context,
            scheme: String,
            title: String,
            dir: String? = null,
            command: String? = null,
        ) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java)
                    .putExtra(EXTRA_SCHEME, scheme)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_DIR, dir)
                    .putExtra(EXTRA_CMD, command)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /**
         * Open a local shell with the given local directory as cwd (used by the local-dir long-press menu).
         * When [priv] is not [Privileged.OFF] the session runs under that privileged identity (su / rish).
         */
        fun startLocal(context: Context, dir: String?, priv: Int = Privileged.OFF) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java)
                    .putExtra(EXTRA_SCHEME, LOCAL_SCHEME)
                    .putExtra(EXTRA_PRIV, priv)
                    .putExtra(EXTRA_DIR, dir)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** No scheme: return to an existing session (or create a local one if none), for the file manager's top-bar Terminal entry. */
        fun resume(context: Context) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
