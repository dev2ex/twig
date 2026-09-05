package com.twig.app.ui

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Local shell command-completion shims.
 *
 * **Why this is needed**: Android's PATH directories have permissions `drwxr-x--x root:shell`
 * (and `/system/xbin` is even more restrictive, `drwxr-x---`); the app uid only has `x`
 * (enter by name) but not `r` (list directory). mksh's command completion calls `opendir`
 * on every PATH directory, so it cannot read a single candidate — typing `mkd` and pressing
 * Tab produces no `mkdir`. `adb shell` can complete because that user is in the `shell`
 * group, **so do not treat adb's behavior as the reference**. Only `/apex/…/bin` is 0755,
 * which is why some completions worked and some did not before.
 *
 * **The workaround is to not list directories but to probe by name** — `x` is enough to
 * stat/execute a single file:
 * - Running `toybox` with no arguments prints every command name it supports (210 in
 *   measurement), and most commands under `/system/bin` are symlinks to toybox, so that
 *   list is nearly the full set;
 * - The remaining Android-specific ones (`am`/`pm`/`dumpsys`…) and third-party tools are
 *   covered by the hard-coded [EXTRA] list, probed along the full PATH one by one.
 *   **The full PATH must be walked, not just `/system/bin`**: in practice `ssh`/`scp`/
 *   `ssh-keygen` live under `/product/bin`.
 *
 * Hits are linked with the same name into `filesDir/bin`; `TerminalActivity.localEnv`
 * then prepends this directory to PATH — the app's private directory is readable by
 * itself, so completion now has candidates.
 *
 * symlinks are **not subject to API 29+'s W^X restriction**: after kernel resolution
 * execve targets the real binary under `/system/bin`, not a file under `/data` (verified:
 * `files/bin/mkdir --help` runs fine).
 *
 * What remains unenumerated is only "vendor-private tools not in [EXTRA]"; users can add
 * a symlink to this directory themselves, and [ensure] does not delete non-dangling links.
 */
object CmdShims {
    private const val TAG = "twig"
    private const val DIR = "bin"

    /** Bump this when [EXTRA] changes so already-generated devices re-probe. */
    private const val VERSION = 1

    /**
     * Commands toybox does not report but that are worth completing. Each one is probed for
     * existence; misses are silently skipped — vendor ROMs vary wildly (this machine hit
     * 41 out of 51, with no `wget`/`mksh`).
     */
    private val EXTRA = listOf(
        // Android platform tools
        "am", "pm", "cmd", "dumpsys", "settings", "content", "svc", "input", "monkey",
        "logcat", "getprop", "setprop", "getenforce", "setenforce", "bmgr", "wm", "ime",
        "screencap", "screenrecord", "dpm", "telecom", "requestsync", "sm", "vdc", "ndc",
        "getevent", "sendevent", "bu", "bugreport", "run-as", "start", "stop", "reboot",
        "app_process", "dalvikvm", "dex2oat", "atrace", "simpleperf", "media",
        // network / filesystem
        "ip", "iptables", "ip6tables", "ping", "ping6", "tcpdump", "pppd",
        "resize2fs", "e2fsck", "tune2fs", "make_f2fs", "fsck.f2fs", "sqlite3",
        // third party (mostly under /product/bin or /vendor/bin)
        "ssh", "scp", "sftp", "ssh-keygen", "ssh-add", "ssh-agent", "rsync",
        "curl", "wget", "zip", "unzip", "zstd", "bzip2", "xz",
        "strace", "ltrace", "busybox", "bash", "mksh", "toolbox", "toybox", "sh",
    )

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    /** Generate in the background. Cheap: when the stamp matches, only one file is read and toybox does not run. */
    fun ensureAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ runCatching { ensure(app) }.onFailure { Log.w(TAG, "shims", it) } }, "twig-shims")
            .start()
    }

    private fun ensure(ctx: Context) {
        val dir = dir(ctx)
        val stamp = File(dir, ".stamp")
        // After a system upgrade commands may have been added or removed; if the fingerprint
        // changed, re-probe from scratch
        val want = Build.FINGERPRINT + "|" + VERSION
        if (stamp.isFile && runCatching { stamp.readText() }.getOrNull() == want) return
        if (!dir.isDirectory && !dir.mkdirs()) return

        // Dangling links (target removed after an upgrade) are cleaned up; valid ones are
        // always preserved — they may be user-added
        dir.listFiles()?.forEach { f ->
            if (f.name != ".stamp" && !f.exists()) runCatching { f.delete() }
        }

        val paths = (System.getenv("PATH") ?: "/system/bin").split(':').filter { it.isNotEmpty() }
        var made = 0
        for (name in (toyboxNames() + EXTRA).distinct()) {
            val link = File(dir, name)
            if (link.exists()) continue // already present (including user-created ones), do not touch
            val target = paths.asSequence()
                .map { File(it, name) }
                .firstOrNull { runCatching { it.canExecute() }.getOrDefault(false) }
                ?: continue
            if (runCatching { Os.symlink(target.absolutePath, link.absolutePath) }.isSuccess) made++
        }
        runCatching { stamp.writeText(want) }
        Log.i(TAG, "shims: +$made in $dir")
    }

    /**
     * `toybox` run with no arguments prints a bare command-name list (whitespace-separated,
     * with no banner — the banner only appears with `toybox --help`). Still filter, so that
     * unexpected output is not treated as a command name and linked.
     */
    private fun toyboxNames(): List<String> = runCatching {
        val p = ProcessBuilder("/system/bin/toybox").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out.split(Regex("\\s+")).filter { n ->
            n.length in 1..32 && n.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        }
    }.getOrElse { emptyList() }
}
