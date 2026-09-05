package com.twig.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.twig.app.ui.RunCommandActivity
import com.twig.app.ui.ShortcutIcons
import org.json.JSONObject

/**
 * A "run this command on a server" entry. A desktop shortcut tap runs it: either
 * inside a terminal (visible output, interactive) or silently, with only the result.
 *
 * ★ What we store is [connLabel] (`SavedConnection.label()`), not the scheme — the
 * scheme is registered dynamically per session, and a shortcut has to work across
 * launches, so like favorites and recent locations we store "how to reach it". At
 * execution time we resolve the scheme via [Connections.find] + [Connections.ensure].
 */
data class RemoteCmd(
    val connLabel: String,
    /** Remote working directory; empty = use the login default. */
    val workdir: String,
    val command: String,
    /** true = open a terminal session to run it (interactive); false = run silently in the background, report result only. */
    val inTerminal: Boolean,
    /**
     * Whether to wrap silent execution in a **login shell**. Default off: sshd
     * running `cmd` uses the user's login shell's `-c` (zsh user gets zsh), but
     * that is **non-interactive, non-login**, so it doesn't read `.zshrc` /
     * `.zprofile` / `.profile`, and PATH tends to be narrower than in a terminal.
     * With the wrap, we go via `$SHELL -lc` — `$SHELL` is set by sshd from
     * passwd, so **if the user's default shell is zsh, zsh is what runs**,
     * never hard-coded to bash. Terminal mode doesn't need this: that is
     * already an interactive login shell.
     */
    val loginShell: Boolean = false,
    /** Name shown on the shortcut / notification. */
    val label: String,
) {
    /**
     * Puts the full command body into an Intent. **Only for in-app use, with the
     * target component un-exported** (currently just [ui.CmdService], which is
     * `exported="false"`, so other apps can't start it). Shortcuts across the
     * process boundary go via [launchIntent] — which only passes the id; see
     * [RemoteCmdStore] for why.
     */
    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_CONN, connLabel)
        putExtra(EXTRA_WORKDIR, workdir)
        putExtra(EXTRA_CMD, command)
        putExtra(EXTRA_TERM, inTerminal)
        putExtra(EXTRA_LOGIN, loginShell)
        putExtra(EXTRA_LABEL, label)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("conn", connLabel); put("workdir", workdir); put("command", command)
        put("terminal", inTerminal); put("login", loginShell); put("label", label)
    }

    /**
     * Goes into [RunCommandActivity] on tap (a no-UI relay that decides terminal
     * or background service).
     *
     * ★ Intent carries **only the id, not the command text**: this Activity has
     * to be exported (see [RemoteCmdStore] for why), and carrying the text would
     * mean opening up "execute arbitrary remote commands with the user's
     * credentials" to every app on the device.
     */
    fun launchIntent(ctx: Context): Intent =
        Intent(ctx, RunCommandActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ID, RemoteCmdStore.put(ctx, this@RemoteCmd))
        }

    companion object {
        private const val EXTRA_CONN = "cmd_conn"
        private const val EXTRA_WORKDIR = "cmd_workdir"
        private const val EXTRA_CMD = "cmd_command"
        private const val EXTRA_TERM = "cmd_terminal"
        private const val EXTRA_LOGIN = "cmd_login"
        private const val EXTRA_LABEL = "cmd_label"
        private const val EXTRA_ID = "cmd_id"

        /** Pulls the command body back out of an internal Intent (only for un-exported components, see [putInto]). */
        fun from(intent: Intent): RemoteCmd? {
            val conn = intent.getStringExtra(EXTRA_CONN) ?: return null
            val command = intent.getStringExtra(EXTRA_CMD) ?: return null
            return RemoteCmd(
                connLabel = conn,
                workdir = intent.getStringExtra(EXTRA_WORKDIR).orEmpty(),
                command = command,
                inTerminal = intent.getBooleanExtra(EXTRA_TERM, false),
                loginShell = intent.getBooleanExtra(EXTRA_LOGIN, false),
                label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifEmpty { command.take(24) },
            )
        }

        /**
         * Restores the command from a **desktop shortcut** Intent: only the id is
         * recognized; null if not found. We must NOT fall back to reading [EXTRA_CMD]
         * here — that would reopen the hole we just closed. Old (≤ 0.79.1) pinned
         * shortcuts carry the command text and no id, so they fall through to null
         * and the caller tells the user to re-create them.
         */
        fun fromShortcut(ctx: Context, intent: Intent): RemoteCmd? =
            intent.getStringExtra(EXTRA_ID)?.let { RemoteCmdStore.get(ctx, it) }

        fun fromJson(o: JSONObject) = RemoteCmd(
            connLabel = o.getString("conn"),
            workdir = o.optString("workdir", ""),
            command = o.getString("command"),
            inTerminal = o.optBoolean("terminal", false),
            loginShell = o.optBoolean("login", false),
            label = o.optString("label", ""),
        )

        /**
         * Builds the actual command line to send: if there's a workdir, cd there
         * first (abort on cd failure — never run in the wrong place); when
         * [RemoteCmd.loginShell] is set, wrap the whole thing in `$SHELL -lc`,
         * with `$SHELL` set remotely by sshd from the user's passwd entry, so a
         * zsh user's command runs in zsh.
         */
        fun shellLine(cmd: RemoteCmd): String {
            val inner =
                if (cmd.workdir.isEmpty()) cmd.command
                else "cd " + sq(cmd.workdir) + " && " + cmd.command
            return if (!cmd.loginShell) inner else "\${SHELL:-/bin/sh} -lc " + sq(inner)
        }

        fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        /** Requests pinning this command to the home screen. */
        fun pin(ctx: Context, cmd: RemoteCmd): Boolean {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
            val id = "cmd_" + (cmd.connLabel + "|" + cmd.workdir + "|" + cmd.command).hashCode()
            val shortcut = ShortcutInfoCompat.Builder(ctx, id)
                .setShortLabel(cmd.label.ifEmpty { cmd.command.take(24) })
                // Use the terminal icon: on the home screen this immediately reads as "run a
                // command", not "a server".
                .setIcon(ShortcutIcons.of(ctx, R.drawable.ic_shortcut_terminal))
                .setIntent(cmd.launchIntent(ctx))
                .build()
            ShortcutManagerCompat.requestPinShortcut(ctx, shortcut, null)
            return true
        }
    }
}
