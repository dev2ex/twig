package com.twig.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import com.twig.fs.local.LocalFileSystem

/**
 * 把本地文件的增删改告诉系统媒体库。
 *
 * 为什么需要:MediaStore 只知道自己扫过的东西。`java.io` 写下去的文件系统不会自动
 * 感知——所以「从 SMB 拷一张图到 DCIM」之后,相册里什么都没有,直到某次开机重扫
 * (或者用户自己去别的 App 里翻)。反过来,删掉/改名之后不说一声,相册里会留着一条
 * 点开就报错的死记录。
 *
 * 挂载点是 [LocalFileSystem.changed](所有本地写入的唯一出口),因此复制/移动/解压/
 * 编辑器保存/WiFi 共享上传全都自动覆盖到,各处调用点一行都不用改。
 *
 * 两个刻意的限制:
 * - **只报 `/storage` 下的路径**。应用私有目录、cacheDir 里的临时文件(缩略图、
 *   7z 打包的中转文件)媒体库本来就不收,报过去纯属白跑一趟 IPC。
 * - **攒一批再报**。拷贝一个目录会一个文件一次回调,每次都开一条扫描连接的话,
 *   传输期间光 IPC 就够呛;停手 [QUIET_MS] 之后统一交一次,堆到 [MAX_BATCH] 就先发。
 */
object MediaScan {

    private const val QUIET_MS = 800L
    private const val MAX_BATCH = 500

    private val main = Handler(Looper.getMainLooper())
    private val pending = LinkedHashSet<String>()
    private var app: Context? = null

    /** 装钩子。由 [TwigApp] 在进程启动时调一次。 */
    fun install(ctx: Context) {
        app = ctx.applicationContext
        LocalFileSystem.changed = { path -> enqueue(path) }
    }

    /** 手动补报(钩子覆盖不到的路径,比如别的模块直接用 java.io 写的文件)。 */
    fun notifyChanged(path: String) = enqueue(path)

    private fun enqueue(path: String) {
        if (!scannable(path)) return
        val flushNow: Boolean
        synchronized(pending) {
            pending += path
            flushNow = pending.size >= MAX_BATCH
        }
        main.removeCallbacks(flushTask)
        if (flushNow) main.post(flushTask) else main.postDelayed(flushTask, QUIET_MS)
    }

    private val flushTask = Runnable { flush() }

    private fun flush() {
        val ctx = app ?: return
        val batch: Array<String>
        synchronized(pending) {
            if (pending.isEmpty()) return
            batch = pending.toTypedArray()
            pending.clear()
        }
        // 扫描本身在 MediaProvider 那边跑,这里只是发起;路径不存在时它会把旧记录撤掉,
        // 正是删除/改名想要的效果。失败(没权限、provider 不在)不该影响文件操作本身。
        runCatching { MediaScannerConnection.scanFile(ctx, batch, null, null) }
    }

    /** 媒体库只管外置存储那几个卷;别的路径(应用私有目录、/data、/system)一律不报。 */
    private fun scannable(path: String): Boolean {
        if (!path.startsWith("/storage/") && !path.startsWith("/sdcard/")) return false
        val priv = app?.getExternalFilesDir(null)?.absolutePath
        if (priv != null && path.startsWith(priv)) return false // Android/data 下的自留地
        return true
    }
}
