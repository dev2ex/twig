package com.twig.app.ui

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

/**
 * 本地 shell 的 ssh 家目录(`filesDir/.ssh`)。
 *
 * **为什么要 App 层准备**:`ssh`/`ssh-keygen` 解析 `~` 走
 * `getpwuid(getuid())->pw_dir`,**不看 `$HOME`**;Android 上应用 uid 的 `pw_dir`
 * 恒为不可写的 `/data`,于是 `ssh-keygen` 会去建 `/data/.ssh` 然后
 * `Permission denied`。绕法是处处显式给绝对路径,`.mkshrc` 的 alias 因此给
 * `ssh`/`scp`/`sftp` 都带上 `-F <这里的 config>`。
 *
 * 而 **`-F` 指向的文件不存在时 ssh 直接报错退出**(不像默认 `~/.ssh/config`
 * 缺失时静默跳过),所以这个文件必须先存在——alias 才不会一上来就废掉。
 *
 * 权限得自己 chmod:ssh 拒绝使用组/其他可写的 config 和私钥。应用私有目录默认
 * umask 已经够严,这里是保险(用户可能从别处 cp 进来一份)。
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
            // 路径全写绝对的:config 里的 '~' 和命令行上的 '~' 是同一个坑。
            // IdentityFile 指向不存在的文件 ssh 会静默跳过,所以可以先都列上。
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
