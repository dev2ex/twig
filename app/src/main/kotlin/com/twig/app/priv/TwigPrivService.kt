package com.twig.app.priv

import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * 跑在 Shizuku 特权进程里的助手(经 `Shizuku.bindUserService` 拉起)。
 *
 * 它存在的唯一理由是**在特权那一侧分配 PTY**:终端要有提示符、行编辑、作业控制、
 * 全屏程序,靠的就是 shell 那头是个真终端;而 `Shizuku.newProcess` 只给管道。
 * Shizuku 用 app_process 把**我们自己的 APK** 拉起来跑这个类,APK 在 `/data/app`
 * (标签 `apk_data_file`),因此不受"`untrusted_app` 不许加载 `app_data_file`"
 * 那条限制——rish 那条路正是死在这上面。
 *
 * ★ 这个类**不能碰任何需要 Context 的东西**:这个进程没有 Application,
 * 也不是一个真正的 Android 组件,只有一个 binder。
 */
class TwigPrivService : ITwigPrivService.Stub() {

    override fun getUid(): Int = Process.myUid()

    /**
     * 绑上 app 的存活凭据。app 进程一没,这个 binder 就死,我们跟着退出。
     *
     * ★ 光有 [destroy] 不够:app 被升级或被杀时 binder 已经断开,Shizuku 那个
     * destroy() 送不到,于是**一个 root 进程留在机器上**(2026-08-17 实测撞到:
     * Shizuku 日志说 "Remove service record ... all connections are gone",
     * 而 `ps` 里 com.twig.app:priv 仍然活着)。
     */
    override fun attach(token: android.os.IBinder?) {
        token ?: return
        runCatching {
            token.linkToDeath({
                Log.i(TAG, "client died, exiting")
                System.exit(0)
            }, 0)
        }.onFailure {
            // 已经死了:那就没有什么好等的
            Log.w(TAG, "client token already dead", it)
            System.exit(0)
        }
    }

    override fun start(
        apkPath: String,
        abi: String,
        cmd: String,
        cwd: String?,
        env: Array<String>,
        rows: Int,
        cols: Int,
        pid: IntArray,
    ): ParcelFileDescriptor? {
        val so = runCatching { ensureLib(apkPath, abi) }.getOrElse {
            Log.e(TAG, "cannot prepare libtwigpty", it)
            return null
        }
        runCatching { Pty.load(so) }.onFailure {
            Log.e(TAG, "cannot load $so", it)
            return null
        }
        val fd = Pty.nativeOpen(cmd, cwd, arrayOf(), env, pid, rows, cols)
        if (fd < 0) {
            Log.e(TAG, "forkpty failed for $cmd")
            return null
        }
        Log.i(TAG, "started $cmd pid=${pid[0]} uid=${Process.myUid()}")
        // adoptFd:所有权交给 PFD,跨 binder 送到 app 侧后这边不再持有。
        return ParcelFileDescriptor.adoptFd(fd)
    }

    override fun kill(pid: Int) {
        runCatching { Pty.nativeKill(pid) }
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    /**
     * 把 `libtwigpty.so` 从 APK 里抠出来,返回可 `System.load` 的绝对路径。
     *
     * 为什么要抠:APK 里的 .so 是**不解压**存储的(`extractNativeLibs=false`,
     * 现代打包默认),`nativeLibraryDir` 是个空目录,而这个进程没有 app classloader
     * 帮它从 APK 内部映射,`System.loadLibrary` 必然失败。
     *
     * 落到 `/data/local/tmp`:shell 与 root 都写得动,且**不是** `app_data_file`
     * 标签,不会撞上 W^X。按大小判断是否要重解,升级换库自动更新。
     */
    private fun ensureLib(apkPath: String, abi: String): String {
        val entryName = "lib/$abi/$LIB"
        val dir = File(TMP_DIR).apply { mkdirs() }
        val out = File(dir, LIB)
        ZipFile(apkPath).use { zip ->
            val e = zip.getEntry(entryName) ?: error("no $entryName in $apkPath")
            if (!out.isFile || out.length() != e.size) {
                // ★ 写临时名 → 先收权限 → 原子改名。直接写目标文件的话,从创建到
                // chmod 之间有一个**全局可写**的窗口,而这个 .so 随后会被加载进
                // 一个 root 进程 —— 那就是一条本地提权路径。
                // (2026-08-17 实测:早先那版落地就是 -rwxrwxrwx。)
                val tmp = File(dir, "$LIB.tmp")
                tmp.delete()
                zip.getInputStream(e).use { ins ->
                    tmp.outputStream().use { ins.copyTo(it) }
                }
                harden(tmp)
                // 上一轮的成品是只读的,但删除靠的是**目录**的写权限,与文件自身
                // 的权限位无关,所以删得掉。
                out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.delete()
                    error("could not put $LIB in place")
                }
            }
        }
        // 已存在、不需要重解的那条路径也要走一遍:老版本留下的可写文件要收回来。
        harden(out)
        return out.absolutePath
    }

    /**
     * 可读可执行、**任何人都不可写**。
     *
     * 不写位是硬要求:系统已经在警告 `Attempt to load writable file ... will throw
     * on a future Android version`,而且可写意味着加载进 root 进程的代码能被换掉。
     * 不设成"仅属主"是因为 Shizuku 可能这次以 root、下次以 shell 起来,属主换了
     * 就读不到自己上一轮留下的文件。
     */
    private fun harden(f: File) {
        f.setReadable(true, false)
        f.setExecutable(true, false)
        f.setWritable(false, false)
    }

    private companion object {
        const val TAG = "twig-privsvc"
        const val LIB = "libtwigpty.so"
        const val TMP_DIR = "/data/local/tmp/twig"
    }
}
