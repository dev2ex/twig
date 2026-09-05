package com.twig.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.twig.fs.local.LocalFileSystem
import com.twig.fs.local.priv.JvmPrivilegedProcess
import com.twig.fs.local.priv.PrivilegedFs
import com.twig.fs.local.priv.PrivilegedLauncher
import com.twig.fs.local.priv.PrivilegedProcess
import com.twig.fs.local.priv.PrivilegedShell
import com.twig.fs.local.priv.SuLauncher
import rikka.shizuku.Shizuku

/**
 * Switch and lifecycle for privileged access.
 *
 * The two sources (root's `su`, Shizuku's shell) merge at this layer — underneath
 * both are just a [PrivilegedShell], and [LocalFileSystem.elevation] only knows
 * "when the normal API fails, here's something to retry". So the whole UI,
 * CopyEngine, thumbnails, search need no changes.
 *
 * ★ Authorization can only happen in the foreground, triggered by the user:
 * Android 10+ forbids starting Activities from the background; Magisk's grant
 * dialog can only degrade to a notification, which most users won't see — symptom
 * is just "stuck, nothing happens".
 */
object Privileged {

    const val OFF = 0
    const val ROOT = 1
    const val SHIZUKU = 2

    /** Shizuku manager's package name; `<queries>` declares the same one. */
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    private const val TAG = "twig-priv"

    init {
        // Pure-JVM module's diagnostic output is wired to logcat; one
        // `adb logcat -s twig-priv` command shows everything.
        PrivilegedShell.log = { Log.w(TAG, it) }
    }

    @Volatile
    private var shell: PrivilegedShell? = null

    /** Currently effective mode; [OFF] if disabled or failed. */
    @Volatile
    var active: Int = OFF
        private set

    /** Uid the privileged shell actually runs under: 0 = root, 2000 = shell (Shizuku). */
    val uid: Int get() = shell?.uid ?: -1

    /**
     * Enables privileged access. **Blocking; must be called from a background thread**
     * (the root path waits until the user finishes the grant dialog). Returns null on
     * success, otherwise a user-facing failure reason (one localized sentence + the
     * underlying real exception).
     */
    fun enable(ctx: Context, mode: Int): String? {
        disable()
        val launcher: PrivilegedLauncher = when (mode) {
            ROOT -> SuLauncher()
            SHIZUKU -> {
                if (!shizukuRunning()) return ctx.getString(R.string.priv_err_shizuku_absent)
                if (!shizukuGranted()) return ctx.getString(R.string.priv_err_shizuku_denied)
                ShizukuLauncher()
            }
            else -> return null
        }
        val s = PrivilegedShell(launcher)
        val ok = runCatching { s.connect() }.getOrElse {
            Log.w(TAG, "connect threw", it)
            false
        }
        if (!ok) {
            val detail = s.lastError
            s.close()
            // ★ Must surface the real reason. "Couldn't start the privileged process"
            // is useless to the user: no root / grant denied / Shizuku service
            // stopped / private method not resolvable via reflection — all four look
            // identical, and only one is something the user can fix. Diagnostics go
            // to both logcat and the dialog.
            Log.w(TAG, "enable(mode=$mode) failed: $detail | ${diagnostics(ctx)}")
            val base = ctx.getString(
                if (mode == ROOT) R.string.priv_err_no_root else R.string.priv_err_shizuku_failed,
            )
            return if (detail.isNullOrEmpty()) base else "$base\n\n$detail"
        }
        Log.i(TAG, "enable(mode=$mode) ok, uid=${s.uid}")
        shell = s
        active = mode
        LocalFileSystem.elevation = PrivilegedFs(s)
        Prefs.setPrivilegedMode(ctx, mode)
        return null
    }

    /** Status, line by line; on failure the cause goes to both logcat and the dialog. */
    fun diagnostics(ctx: Context): String {
        fun q(body: () -> Any?): String = runCatching { body()?.toString() ?: "null" }
            .getOrElse { "!" + (it.javaClass.simpleName) }
        return listOf(
            "installed=" + q { shizukuInstalled(ctx) },
            "binder=" + q { Shizuku.pingBinder() },
            "version=" + q { Shizuku.getVersion() },
            "serverUid=" + q { Shizuku.getUid() },
            "preV11=" + q { Shizuku.isPreV11() },
            // ★ Don't print the numeric value: PERMISSION_GRANTED is exactly **0**,
            // so "granted=0" reads like "not granted" when it means the opposite.
            // The diagnostic string is read by a person in a hurry.
            "granted=" + q {
                when (Shizuku.checkSelfPermission()) {
                    PackageManager.PERMISSION_GRANTED -> "yes"
                    else -> "no"
                }
            },
            "active=$active",
            "shellUid=$uid",
        ).joinToString(" ")
    }

    /** Closes and removes the fallback hook; the local file system immediately reverts
     * to "only what its own permissions allow". */
    fun disable() {
        LocalFileSystem.elevation = null
        active = OFF
        shell?.close()
        shell = null
    }

    fun disable(ctx: Context) {
        disable()
        Prefs.setPrivilegedMode(ctx, OFF)
    }

    /**
     * Restores the user's last choice at process startup.
     *
     * Shizuku's binder is **not in place synchronously** (it has to wait for the
     * provider to deliver it), so we can't just probe "is it alive?" here — we have
     * to attach a sticky listener, which fires immediately if it's already there or
     * waits for it to arrive. Root has no such issue; just connect in the background.
     */
    fun restore(ctx: Context) {
        val mode = Prefs.privilegedMode(ctx)
        if (mode == OFF) return
        val app = ctx.applicationContext
        if (mode == SHIZUKU) {
            Shizuku.addBinderReceivedListenerSticky {
                background {
                    // ★ Re-read the preference on every callback. This listener is **process-wide
                    // and stays alive after registration**, and the binder arrives
                    // more than once — Shizuku service/manager restarts both re-fire
                    // it. Hardcoding SHIZUKU here would mean: if the user later
                    // turned it off in settings or switched to Root, the next re-fire
                    // would force the mode back to Shizuku and rewrite Prefs —
                    // silently overturning their choice.
                    if (Prefs.privilegedMode(app) == SHIZUKU) enable(app, SHIZUKU)
                }
            }
        } else {
            background { enable(app, mode) }
        }
    }

    // ---- Shizuku state queries (all wrapped in runCatching: these classes throw when
//      Shizuku isn't installed) ----

    /** Shizuku service is running (binder in place). */
    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Whether the Shizuku manager is installed (different from "running"; messages need to be distinct). */
    fun shizukuInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0) != null
    }.getOrDefault(false)

    private fun background(body: () -> Unit) {
        Thread(body, "twig-priv-connect").apply { isDaemon = true }.start()
    }

    /**
     * Shizuku-side process launcher. The only difference from [SuLauncher] is this
     * one line — what you get is still a process with stdin/stdout/stderr, just
     * running as shell (uid 2000) instead of root. `ShizukuRemoteProcess` itself
     * is a `java.lang.Process` subclass, so the same wrapper applies.
     *
     * ★ `Shizuku.newProcess` was made **private** in 13.x (the upstream wants to
     * push everyone onto `bindUserService`), so this is reflective. The price is
     * R8 must keep `rikka.shizuku.**` method names (already in proguard-rules.pro);
     * otherwise this is a guaranteed NoSuchMethod.
     *
     * Switching to the official path is a **different design**: `bindUserService`
     * runs our own code inside the shell process, where we get direct java.io and
     * can hand back a ParcelFileDescriptor for true random access — but it needs
     * AIDL + Service + binding lifecycle, and **doesn't apply to root at all**,
     * so we'd have to maintain two unrelated backends and lose the abstraction
     * "all of these are just a privileged process" that this layer currently relies on.
     */
    private class ShizukuLauncher : PrivilegedLauncher {
        override fun start(cmd: String?): PrivilegedProcess {
            val argv = if (cmd == null) arrayOf("sh") else arrayOf("sh", "-c", cmd)
            val p = newProcess.invoke(null, argv, null, null) as Process
            return JvmPrivilegedProcess(p)
        }

        companion object {
            private val newProcess by lazy {
                Shizuku::class.java
                    .getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java,
                    )
                    .apply { isAccessible = true }
            }
        }
    }
}
