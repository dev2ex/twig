package com.twig.app.ui

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File

/**
 * 本地 shell 的命令补全垫片。
 *
 * **为什么需要**:Android 的 PATH 目录权限是 `drwxr-x--x root:shell`
 * (`/system/xbin` 更狠,`drwxr-x---`),应用 uid 只有 `x`(按名字进入)没有 `r`
 * (列目录)。mksh 的命令补全要 `opendir` 每个 PATH 目录,一个候选都读不出来——
 * 敲 `mkd` 按 Tab 补不出 `mkdir`。`adb shell` 里能补是因为那个用户在 `shell` 组,
 * **别拿 adb 下的表现当准**。只有 `/apex/…/bin` 是 0755,所以从前"有些能补有些
 * 不能补"。
 *
 * **绕法是不列目录,改成按名字问**——`x` 权限足够 stat/执行单个文件:
 * - `toybox` 无参运行会自报它支持的全部命令名(实测 210 个),而 `/system/bin`
 *   下绝大多数命令本来就是指向 toybox 的 symlink,这一份几乎就是全集;
 * - 剩下 Android 特有的(`am`/`pm`/`dumpsys`…)和第三方的靠 [EXTRA] 这份写死的
 *   名单沿 PATH 逐个探测。**必须走完整 PATH 而不是只看 `/system/bin`**:
 *   `ssh`/`scp`/`ssh-keygen` 实测在 `/product/bin`。
 *
 * 命中的在 `filesDir/bin` 里建同名 symlink,再由 `TerminalActivity.localEnv`
 * 把这个目录前置到 PATH——应用私有目录自己可读,补全就有候选了。
 *
 * symlink **不受 API 29+ 的 W^X 限制**:内核解析后 execve 的是 `/system/bin`
 * 下的真身,不是 `/data` 上的文件(实测 `files/bin/mkdir --help` 正常)。
 *
 * 枚举不到的只剩「不在 [EXTRA] 里的厂商私有工具」,用户自己往这个目录补一个
 * symlink 即可,[ensure] 不会删非悬空的链接。
 */
object CmdShims {
    private const val TAG = "twig"
    private const val DIR = "bin"

    /** 改了 [EXTRA] 就 +1,让已经生成过的设备重新探测一遍。 */
    private const val VERSION = 1

    /**
     * toybox 报不出、又值得补全的命令。存在与否逐个探测,不存在的静默跳过——
     * 各家 ROM 差异很大(这台机器 51 个里命中 41 个,没有 `wget`/`mksh`)。
     */
    private val EXTRA = listOf(
        // Android 平台工具
        "am", "pm", "cmd", "dumpsys", "settings", "content", "svc", "input", "monkey",
        "logcat", "getprop", "setprop", "getenforce", "setenforce", "bmgr", "wm", "ime",
        "screencap", "screenrecord", "dpm", "telecom", "requestsync", "sm", "vdc", "ndc",
        "getevent", "sendevent", "bu", "bugreport", "run-as", "start", "stop", "reboot",
        "app_process", "dalvikvm", "dex2oat", "atrace", "simpleperf", "media",
        // 网络 / 文件系统
        "ip", "iptables", "ip6tables", "ping", "ping6", "tcpdump", "pppd",
        "resize2fs", "e2fsck", "tune2fs", "make_f2fs", "fsck.f2fs", "sqlite3",
        // 第三方(多半在 /product/bin 或 /vendor/bin)
        "ssh", "scp", "sftp", "ssh-keygen", "ssh-add", "ssh-agent", "rsync",
        "curl", "wget", "zip", "unzip", "zstd", "bzip2", "xz",
        "strace", "ltrace", "busybox", "bash", "mksh", "toolbox", "toybox", "sh",
    )

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    /** 后台生成。便宜:命中 stamp 时只读一个文件,不跑 toybox。 */
    fun ensureAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ runCatching { ensure(app) }.onFailure { Log.w(TAG, "shims", it) } }, "twig-shims")
            .start()
    }

    private fun ensure(ctx: Context) {
        val dir = dir(ctx)
        val stamp = File(dir, ".stamp")
        // 系统升级后命令可能增删,fingerprint 变了重新探测一遍
        val want = Build.FINGERPRINT + "|" + VERSION
        if (stamp.isFile && runCatching { stamp.readText() }.getOrNull() == want) return
        if (!dir.isDirectory && !dir.mkdirs()) return

        // 悬空链接(升级后目标没了)清掉;有效的一律保留——可能是用户自己加的
        dir.listFiles()?.forEach { f ->
            if (f.name != ".stamp" && !f.exists()) runCatching { f.delete() }
        }

        val paths = (System.getenv("PATH") ?: "/system/bin").split(':').filter { it.isNotEmpty() }
        var made = 0
        for (name in (toyboxNames() + EXTRA).distinct()) {
            val link = File(dir, name)
            if (link.exists()) continue // 已有(含用户自建),不动
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
     * `toybox` 无参数运行打印的是纯命令名列表(空白分隔,没有 banner——banner 只在
     * `toybox --help` 时才有)。仍然过滤一道,别把意外输出当命令名建成链接。
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
