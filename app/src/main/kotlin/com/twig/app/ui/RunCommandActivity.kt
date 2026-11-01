package com.twig.app.ui

import com.twig.app.TwigActivity
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
 * The transparent Twig theme must remain AppCompat-based so the unlock dialog
 * can be shown even though this relay has no content view of its own.
 *
 * When going through the terminal the connection has to be built on demand — the
 * shortcut may have been tapped while the process is cold-starting and that server
 * hasn't registered its scheme yet; [Connections.ensure] blocks on the connect, so
 * we kick it to a background thread and come back to the main thread with the scheme
 * to open the terminal page. The background path hands off to [CmdService] (the
 * foreground service — see its notes).
 */
class RunCommandActivity : TwigActivity() {

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

        if (cmd.isLocal) {
            if (cmd.isShizuku && !com.twig.app.Privileged.shizukuMatches(cmd.target)) {
                Toast.makeText(this, R.string.terminal_priv_unavailable, Toast.LENGTH_LONG).show()
            } else if (cmd.target == RemoteCmd.TARGET_ROOT && com.twig.app.PrivShell.suPath() == null) {
                Toast.makeText(this, R.string.terminal_priv_unavailable, Toast.LENGTH_LONG).show()
            } else if (cmd.inTerminal) {
                TerminalActivity.startLocal(this, cmd.workdir.ifEmpty { null },
                    when {
                        cmd.isShizuku -> com.twig.app.Privileged.SHIZUKU
                        cmd.target == RemoteCmd.TARGET_ROOT -> com.twig.app.Privileged.ROOT
                        else -> com.twig.app.Privileged.OFF
                    },
                    command = cmd.command, shizukuTarget = cmd.target.takeIf { cmd.isShizuku })
            } else CmdService.start(this, cmd)
            finish()
            return
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
