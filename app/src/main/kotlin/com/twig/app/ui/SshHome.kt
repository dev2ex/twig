package com.twig.app.ui

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

/**
 * The local shell's ssh home directory (`filesDir/.ssh`).
 *
 * **Why prepare it at the App layer**: `ssh` / `ssh-keygen` resolve `~` via
 * `getpwuid(getuid())->pw_dir`, **ignoring `$HOME`**; on Android the app uid's
 * `pw_dir` is always the unwritable `/data`, so `ssh-keygen` would try to create
 * `/data/.ssh` and then fail with `Permission denied`. The workaround is to
 * explicitly supply absolute paths everywhere, so the `.mkshrc` aliases give
 * `ssh`/`scp`/`sftp` a `-F <this config>` flag.
 *
 * And **`ssh` exits with an error if the file pointed to by `-F` does not exist**
 * (unlike the default `~/.ssh/config`, which is silently skipped when missing),
 * so this file has to exist upfront — otherwise the aliases die on the first call.
 *
 * Permissions need an explicit chmod: ssh refuses to use any config or private key
 * that's group/world-writable. The app-private directory's default umask is strict
 * enough already, this is insurance (the user might copy one in from elsewhere).
 */
object SshHome {
    private const val TAG = "twig"

    fun dir(ctx: Context): File = File(ctx.filesDir, ".ssh")

    fun ensureAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ runCatching { ensure(app) }.onFailure { Log.w(TAG, "sshhome", it) } }, "twig-sshhome")
            .start()
    }

    private fun ensure(ctx: Context) {
        val dir = dir(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) return
        runCatching { Os.chmod(dir.absolutePath, "700".toInt(8)) }

        val cfg = File(dir, "config")
        if (!cfg.exists()) {
            val p = dir.absolutePath
            // Write every path as absolute: a '~' inside config hits exactly the same pitfall
            // as one on the command line.
            // ssh silently skips IdentityFiles that don't exist, so listing them all is fine.
            runCatching {
                cfg.writeText(
                    "# Twig local shell — ssh client config.\n" +
                        "# ssh resolves '~' via getpwuid(), which is /data on Android and not\n" +
                        "# writable, so the usual ~/.ssh is unusable. Twig's aliases pass\n" +
                        "# -F <this file> instead. Keep every path below absolute — a '~' in\n" +
                        "# here hits exactly the same problem.\n" +
                        "\n" +
                        "Host *\n" +
                        "    UserKnownHostsFile $p/known_hosts\n" +
                        "    IdentityFile $p/id_ed25519\n" +
                        "    IdentityFile $p/id_rsa\n",
                )
            }
        }
        runCatching { Os.chmod(cfg.absolutePath, "600".toInt(8)) }
    }
}
