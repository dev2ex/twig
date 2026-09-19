package com.twig.app

import android.content.Context
import java.io.File

/**
 * Runs the **local terminal** under a privileged identity (root or Shizuku).
 *
 * ★ The key point: no bridging, AIDL, or fd passing is needed here — the local
 * terminal already has a **real PTY** allocated by Termux's `JNI.createSubprocess`;
 * we just swap "what to run" from `/system/bin/sh` to `su` (or rish), and resize,
 * job control, full-screen programs all keep working because they depend on that
 * local PTY.
 *
 * The two paths have different shapes but are the same thing to the caller:
 *  - **root**: `su` directly launches a root shell inside this local PTY — this file's job.
 *  - **Shizuku**: PTY is allocated by the privileged process and the fd passed back;
 *    see [com.twig.app.priv.TwigPrivService] — this file only decides whether it can start.
 */
object PrivShell {

    /**
     * Absolute path of `su`, or null if not found.
     *
     * Not using `which`: PATH usually doesn't have it. **Do not treat this result as
     * a verdict on "is root available"** — Magisk's DenyList hides `su` from
     * unauthorized apps, so a miss doesn't mean it isn't there; callers should still
     * surface the root option for the user to try.
     */
    fun suPath(): String? = SU_PATHS.firstOrNull { File(it).exists() }

    /**
     * Whether a privileged identity can start a terminal right now.
     *
     * ★ **The Shizuku path tried rish once; it's a dead end** (2026-08-16 empirical
     * verdict): rish's dex can only land in app-private directories, and
     * `untrusted_app` is **not allowed to load `app_data_file`-labelled files** (the
     * concrete form of W^X in SELinux); `app_process` throws
     * `ClassNotFoundException`. Same dex, same environment, same command line — but
     * switching to `u:r:su:s0` (adb root) or `u:r:runas_app:s0` (run-as, **the same
     * uid as the app**) loads fine. The only variable is the SELinux domain. Termux
     * gets away with rish because it has been stuck on targetSdk 28 for years.
     *
     * The path now is `bindUserService`: it runs **our own APK** (`/data/app`, label
     * `apk_data_file`), which is not affected — see [com.twig.app.priv.TwigPrivService].
     */
    fun available(ctx: Context, mode: Int): Boolean = when (mode) {
        Privileged.ROOT -> suPath() != null
        // Shizuku terminal no longer goes via rish (SELinux blocks it); the
        // privileged process now allocates the PTY — see TwigPrivService. As long
        // as the Shizuku service is running, we can start.
        Privileged.SHIZUKU -> Privileged.shizukuRunning()
        else -> true
    }

    /**
     * Executable and arguments to run for this session. Returns null if this identity
     * cannot start right now. [Privileged.OFF] uses the original `/system/bin/sh`.
     */
    fun command(ctx: Context, mode: Int): Pair<String, Array<String>>? = when (mode) {
        Privileged.OFF -> SHELL to arrayOf()
        // ★ Root starts an **ordinary** shell and becomes root on its first line — see
        // [rootKickoff] for why `su` must not be the process the pty was forked into.
        // `suPath()` is still what decides whether root can be offered at all.
        Privileged.ROOT -> suPath()?.let { SHELL to arrayOf<String>() }
        // Shizuku does not run a local process; TwigPrivService allocates the PTY —
        // see the note in available().
        else -> null
    }

    /**
     * The line fed to a freshly opened local shell to turn it into a root one, or null when no `su`
     * was found.
     *
     * ★★ **`su` has to be a *child* of the shell, never the process the pty was forked into**
     * (measured 2026-09-19 on a Magisk 26.3 / Android 9 device): exec'ing `su` straight from
     * `createSubprocess` — where it is the session leader that has just acquired the pty as its
     * controlling terminal — made it exit 1 within ~60 ms, printing nothing at all, while the very
     * same binary reached uid 0 from the very same app process through a pipe, and typing the same
     * absolute path by hand into Twig's own local terminal turned that session into root. Ruled out
     * along the way: SELinux (it fails under `setenforce 0` too), the rc file `$ENV` points at (it
     * fails with that file truncated to zero bytes), the working directory, the environment, and the
     * Magisk policy (allow, with no rejection logged). This is also the shape Termux's `tsu` has:
     * `su` runs as a child and execs the real shell.
     *
     * No `exec`: that would replace the shell with `su` and put us back to the failing shape. The
     * trailing `exit` is what keeps the promise that a privileged session never silently degrades —
     * when `su` fails, the outer shell leaves at once and the session ends carrying su's own error
     * message, instead of quietly handing back a prompt at the app's uid that looks like root.
     */
    fun rootKickoff(): String? = suPath()?.let { "$it; exit\n" }

    const val SHELL = "/system/bin/sh"

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/su/bin/su",
    )
}
