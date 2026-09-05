package com.twig.app.ui

import android.app.Activity
import android.content.Context
import com.twig.app.R
import com.twig.app.secure.Secrets
import com.twig.app.share.WebShare

/**
 * "Lock" and "Quit".
 *
 * The difference between the two is one sentence: **Lock does not interrupt anything
 * in progress**, Quit stops everything.
 *
 * - **Lock** = discard the in-memory DEK and move to the background. Music keeps playing,
 *   terminal sessions stay open, WiFi sharing continues, but switching back requires
 *   re-entering the master password. This is exactly what "I'm stepping away for a moment"
 *   calls for. Only meaningful when the master password is enabled — without it, locking
 *   does not require a password, so the menu entry is hidden.
 * - **Quit** = stop everything and close all surfaces.
 *
 * The wording avoids "Sign out": that word reads like logging out of an account
 * (Twig has no accounts); it also avoids "Forgot password" — elsewhere that is usually
 * the entry point to "recover password", which is the opposite meaning. "Lock" pairs
 * with the unlock dialog's "Unlock Twig" — two ends of the same thing.
 */
object AppExit {

    /**
     * Things that quitting would interrupt; the strings here are user-facing copy.
     *
     * If empty, "Quit" does not need to ask again — nothing would be lost, and a
     * confirmation dialog would be an extra step for no reason.
     */
    fun running(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val track = MusicEngine.currentTrack()
        if (track != null) {
            out += ctx.getString(R.string.exit_running_music, track.title.ifEmpty { track.name })
        }
        val terms = TermManager.list().size
        if (terms > 0) out += ctx.resources.getQuantityString(R.plurals.exit_running_term, terms, terms)
        if (WebShare.isRunning) out += ctx.getString(R.string.exit_running_share)
        if (Transfers.active != null) out += ctx.getString(R.string.exit_running_transfer)
        return out
    }

    /** Lock: only discard the DEK and move to the background; leave everything else alone. */
    fun lock(act: Activity) {
        Secrets.lock()
        act.moveTaskToBack(true)
    }

    /**
     * Quit: stop everything that is running, then close the whole task stack.
     *
     * **Does not call `exitProcess`**: `SharedPreferences.apply()` flushes asynchronously,
     * and a hard process kill can drop preferences that were just written. After stopping
     * services and calling `finishAffinity()`, the process will be reclaimed by the system
     * anyway; [Secrets.lock] ensures that **even if the process somehow survives, the next
     * entry still requires the master password** — security does not depend on
     * "did the process really die", which is not under our control.
     */
    fun quit(act: Activity) {
        runCatching { Transfers.active?.cancel() }
        runCatching { WebShare.stop(act) }
        runCatching { TermManager.closeAll() }
        runCatching { MusicEngine.shutdown() }
        Secrets.lock()
        act.finishAffinity()
    }
}
