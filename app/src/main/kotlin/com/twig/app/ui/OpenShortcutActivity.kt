package com.twig.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.twig.app.Connections
import com.twig.app.ConnectionStore
import com.twig.app.MainActivity
import com.twig.app.OpenFiles
import com.twig.app.R
import com.twig.app.TwigApp
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop shortcut for a single file (created from [PaneFragment.pinFileOpenShortcut]):
 * reconnects the source if needed and dispatches straight to the mode chosen when the
 * shortcut was pinned — no visible UI, finishes immediately after.
 *
 * ★ **Only gates on the master password when the source can actually need it.** Local
 * files and SAF documents never touch [com.twig.app.secure.Secrets] — there is no server
 * password to decrypt — so those dispatch immediately, locked or not; a shortcut to a
 * local video asking for the master password on every tap would defeat the point of a
 * shortcut. Everything else has to reconnect through [ConnectionStore], whose saved
 * passwords **are** sealed behind the master password ([ConnectionStore.opened]) —
 * reading them before unlocking hands [Connections.ensure] ciphertext, which is the same
 * silent-login-failure trap documented on that function, so those schemes gate first.
 *
 * ★ Not exported, same reasoning as [TerminalActivity]: the system launches pinned
 * shortcuts without needing the target exported, and this intent carries an internal
 * scheme+path that can point at a private server — exporting it would let any app on the
 * device replay it.
 *
 * ★ Runs in **its own task** (a dedicated `taskAffinity` in the manifest + `FLAG_ACTIVITY_NEW_TASK`
 * here): without that, if Twig's main task is already sitting in Recents, this Activity — and
 * the viewer it starts — lands on top of *that* task instead of a fresh one. The symptom is
 * exactly "opens the file browser first, then jumps to the file" (MainActivity flashes on
 * screen underneath) and "back from the viewer goes to the file browser" instead of exiting.
 */
class OpenShortcutActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_COMPONENT = "component"

        const val MODE_AUTO = "auto"
        const val MODE_TEXT = "text"
        const val MODE_HEX = "hex"
        /** Always opens with the one app chosen when the shortcut was pinned (see
         * [PaneFragment.pickAppForShortcut]) — a shortcut has no chance to show the system
         * resolver on every tap, so that choice is made once, up front, not at open time. */
        const val MODE_EXTERNAL = "external"

        /** [component] is required for [MODE_EXTERNAL] (the app chosen when pinning); unused otherwise. */
        fun intent(ctx: Context, file: XFile, mode: String, component: ComponentName? = null): Intent =
            Intent(ctx, OpenShortcutActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                // The launcher runs as a different app/task; without this the taskAffinity
                // declared in the manifest has nothing to act on (see the class doc).
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_SCHEME, file.scheme)
                putExtra(EXTRA_PATH, file.path)
                putExtra(EXTRA_NAME, file.name)
                putExtra(EXTRA_SIZE, file.size)
                putExtra(EXTRA_MODE, mode)
                component?.let { putExtra(EXTRA_COMPONENT, it.flattenToString()) }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TwigApp.registerBaseFs(this) // on cold start FsRegistry is still empty (main screen never came up)
        val scheme = intent.getStringExtra(EXTRA_SCHEME)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (scheme == null || path == null) { finish(); return }
        val file = XFile(
            scheme, path, isDir = false,
            size = intent.getLongExtra(EXTRA_SIZE, 0L),
            displayName = intent.getStringExtra(EXTRA_NAME),
        )
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_AUTO
        val component = intent.getStringExtra(EXTRA_COMPONENT)?.let { ComponentName.unflattenFromString(it) }

        if (scheme == "file" || scheme == "saf") {
            proceed(file, mode, component)
        } else {
            SecurityUi.gate(this) { proceed(file, mode, component) }
        }
    }

    private fun proceed(file: XFile, mode: String, component: ComponentName?) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { ensureSource(file.scheme) }
            if (!ok) {
                Toast.makeText(this@OpenShortcutActivity, R.string.shortcut_source_unavailable, Toast.LENGTH_SHORT).show()
                // NEW_TASK here specifically routes to MainActivity's *own* (default-affinity)
                // task rather than nesting a stray instance inside this Activity's isolated one.
                startActivity(
                    Intent(this@OpenShortcutActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                finish()
                return@launch
            }
            dispatch(file, mode, component)
            finish()
        }
    }

    /** Local / SAF need nothing extra; a source already registered this process (the app
     * was merely backgrounded, not killed) is reused as-is; otherwise look the scheme up
     * among saved connections and reconnect — same algorithm [Connections.ensure] uses
     * when a pane expands a server node. */
    private fun ensureSource(scheme: String): Boolean {
        if (scheme == "file" || scheme == "saf") return true
        if (FsRegistry.all().any { it.scheme == scheme }) return true
        val conn = ConnectionStore.all(this).find { Connections.schemeOf(it) == scheme } ?: return false
        return runCatching { Connections.ensure(this, conn) }.isSuccess
    }

    private fun dispatch(file: XFile, mode: String, component: ComponentName?) {
        when (mode) {
            MODE_TEXT -> TextViewerActivity.start(this, file, preview = false)
            MODE_HEX -> HexViewerActivity.start(this, file)
            // component is always set when this shortcut was pinned through the picker; the
            // chooser fallback only covers a shortcut somehow pinned without one.
            MODE_EXTERNAL -> {
                val opened = if (component != null) {
                    OpenFiles.openWithComponent(this, file, component)
                } else {
                    OpenFiles.openWith(this, file, forceChooser = true)
                }
                if (!opened) Toast.makeText(this, R.string.open_no_app, Toast.LENGTH_SHORT).show()
            }
            else -> OpenDispatch.open(this, file)
        }
    }
}
