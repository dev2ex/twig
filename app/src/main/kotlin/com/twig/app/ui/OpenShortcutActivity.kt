package com.twig.app.ui

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
 */
class OpenShortcutActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_MODE = "mode"

        const val MODE_AUTO = "auto"
        const val MODE_TEXT = "text"
        const val MODE_HEX = "hex"
        const val MODE_EXTERNAL = "external"

        fun intent(ctx: Context, file: XFile, mode: String): Intent =
            Intent(ctx, OpenShortcutActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_SCHEME, file.scheme)
                putExtra(EXTRA_PATH, file.path)
                putExtra(EXTRA_NAME, file.name)
                putExtra(EXTRA_SIZE, file.size)
                putExtra(EXTRA_MODE, mode)
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

        if (scheme == "file" || scheme == "saf") {
            proceed(file, mode)
        } else {
            SecurityUi.gate(this) { proceed(file, mode) }
        }
    }

    private fun proceed(file: XFile, mode: String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { ensureSource(file.scheme) }
            if (!ok) {
                Toast.makeText(this@OpenShortcutActivity, R.string.shortcut_source_unavailable, Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@OpenShortcutActivity, MainActivity::class.java))
                finish()
                return@launch
            }
            dispatch(file, mode)
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

    private fun dispatch(file: XFile, mode: String) {
        when (mode) {
            MODE_TEXT -> TextViewerActivity.start(this, file, preview = false)
            MODE_HEX -> HexViewerActivity.start(this, file)
            MODE_EXTERNAL -> if (!OpenFiles.openWith(this, file, forceChooser = true)) {
                Toast.makeText(this, R.string.open_no_app, Toast.LENGTH_SHORT).show()
            }
            else -> OpenDispatch.open(this, file)
        }
    }
}
