package com.twig.app.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.twig.app.R

/**
 * The launcher's long-press menu (Music / Terminal).
 *
 * ★ These are **dynamic** shortcuts published from an Activity, not the static
 * `res/xml/shortcuts.xml` they used to be — because a static shortcut's labels are
 * resource references that **the launcher resolves in the system locale**, while
 * this app has its own language setting ([LanguagePref], a per-app locale). With
 * the system in English and Twig set to Chinese, the menu came out English and
 * nothing in the app could change that. Publishing from an Activity hands the
 * launcher **already-resolved text**, so the menu follows the app's language.
 * (Republishing on a language change costs nothing: [LanguagePref] recreates every
 * Activity, so `MainActivity.onCreate` runs again.)
 *
 * The price is that the menu is empty until Twig has been opened once. That is a
 * fair trade for entries that only mean anything after there is something to come
 * back to.
 *
 * Icons go through [ShortcutIcons] — never by resource, see that class.
 *
 * ★ **Every dynamic shortcut must name an activity, and it must be a launcher
 * activity.** `setDynamicShortcuts` throws
 * `IllegalArgumentException: Cannot publish shortcut: target activity is not set`
 * when it is missing — unlike `requestPinShortcut`, where the system fills in the
 * default main activity for us, which is why the pinned shortcuts worked without
 * it. That is the *owning* activity (it decides which app icon the menu hangs
 * off), not the one the shortcut opens: both entries open something else and still
 * belong to `MainActivity`.
 */
object Shortcuts {

    /**
     * ★ Not `music` / `terminal`: those ids belonged to the **manifest** shortcuts this
     * replaced, and a manifest shortcut's id stays reserved for as long as some launcher
     * keeps it pinned — even after the declaration is gone. Reusing one is refused with
     * `IllegalArgumentException: Manifest shortcut ID=music may not be manipulated via
     * APIs`, and since that hits the whole batch, **both** entries disappear.
     */
    private const val ID_MUSIC = "open_music"
    private const val ID_TERMINAL = "open_terminal"

    fun publish(act: Activity) {
        val owner = ComponentName(act, com.twig.app.MainActivity::class.java)
        val music = ShortcutInfoCompat.Builder(act, ID_MUSIC)
            .setActivity(owner)
            .setShortLabel(act.getString(R.string.music_menu))
            .setLongLabel(act.getString(R.string.music_menu))
            .setIcon(ShortcutIcons.of(act, R.drawable.ic_shortcut_music))
            .setRank(0)
            .setIntent(
                Intent(act, MusicPlayerActivity::class.java).setAction(Intent.ACTION_VIEW),
            )
            .build()
        // Opens the session you were last in, or a local shell when there is none (see
        // TerminalActivity.handleIntent). The target stays exported="false": the system
        // launches a shortcut as this app, and exporting it would hand every app on the
        // device "open a session with the saved credentials" — and because this entry
        // point skips the main UI, that activity gates on the master password.
        val terminal = ShortcutInfoCompat.Builder(act, ID_TERMINAL)
            .setActivity(owner)
            .setShortLabel(act.getString(R.string.shortcut_terminal))
            .setLongLabel(act.getString(R.string.shortcut_terminal))
            .setIcon(ShortcutIcons.of(act, R.drawable.ic_shortcut_terminal))
            .setRank(1)
            .setIntent(
                Intent(act, TerminalActivity::class.java).setAction(Intent.ACTION_VIEW),
            )
            .build()
        // ★ Never swallow this silently: a failed publish and "never called at all" look
        // identical on the desktop (an empty menu), and every precondition of this call is
        // reported as an IllegalArgumentException — throwing it away discards the only
        // clue there is (same lesson as the terminal resize).
        try {
            ShortcutManagerCompat.setDynamicShortcuts(act, listOf(music, terminal))
        } catch (e: Exception) {
            Log.w("TwigShortcuts", "publish failed", e)
        }
    }
}
