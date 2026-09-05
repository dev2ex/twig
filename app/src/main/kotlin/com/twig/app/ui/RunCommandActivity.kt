package com.twig.app.ui

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import com.twig.app.Connections
import com.twig.app.R
import com.twig.app.RemoteCmd
import com.twig.app.TwigApp

/**
 * Entry point for desktop command shortcuts: a headless relay page (same pattern as
 * [ViewIntentActivity]) that decides whether this command should go to the terminal
 * or run in the background, dispatches it, and finishes immediately.
 *
 * ★ This is an **exported** Activity (the old INSTALL_SHORTCUT broadcast path on
 * minSdk 24 requires it), so we only trust the random id from the intent, look up
 * the actual command body in [com.twig.app.RemoteCmdStore], and **never trust the
 * intent's command text directly** — otherwise any zero-permission app on the device
 * could make Twig run arbitrary commands on the user's saved credentials. See the
 * class comment on RemoteCmdStore.
 *
 * ★ Must be a plain [Activity] instead of AppCompatActivity: it uses
 * `Theme.Translucent.NoTitleBar` (the transparent theme a headless relay needs),
 * which is not a descendant of any AppCompat theme, and AppCompatActivity throws
 * "You need to use a Theme.AppCompat theme" from onPostCreate and crashes.
 *
 * When going through the terminal the connection has to be built on demand — the
 * shortcut may have been tapped while the process is cold-starting and that server
 * hasn't registered its scheme yet; [Connections.ensure] blocks on the connect, so
 * we kick it to a background thread and come back to the main thread with the scheme
 * to open the terminal page. The background path hands off to [CmdService] (the
 * foreground service — see its notes).
 */
class RunCommandActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TwigApp.registerBaseFs(this)
        // ★ This path takes a saved connection to run remote commands — skip the gate and
        // the master password gets bypassed (when locked, the password still decrypts to
        // ciphertext and the command just fails with no obvious reason).
        SecurityUi.gate(this) { run() }
    }

    private fun run() {
        val cmd = RemoteCmd.fromShortcut(this, intent)
        if (cmd == null) {
            Toast.makeText(this, R.string.cmd_bad_shortcut, Toast.LENGTH_LONG).show()
            finish(); return
        }

        if (!cmd.inTerminal) {
            CmdService.start(this, cmd)
            finish(); return
        }

        val conn = Connections.find(this, cmd.connLabel)
        if (conn == null) {
            Toast.makeText(this, R.string.cmd_conn_missing, Toast.LENGTH_LONG).show()
            finish(); return
        }
        Thread({
            val scheme = runCatching { Connections.ensure(this, conn) }.getOrNull()
            runOnUiThread {
                if (scheme == null) {
                    Toast.makeText(this, R.string.cmd_conn_missing, Toast.LENGTH_LONG).show()
                } else {
                    TerminalActivity.start(
                        this,
                        scheme,
                        conn.shortLabel(),
                        dir = cmd.workdir.ifEmpty { null },
                        command = cmd.command,
                    )
                }
                finish()
            }
        }, "twig-cmd-connect").start()
    }
}
