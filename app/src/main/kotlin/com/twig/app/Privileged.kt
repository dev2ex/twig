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
 * 特权访问的开关与生命周期。
 *
 * 两种来源(root 的 `su`、Shizuku 的 shell)在这一层就合流了 —— 往下都只是一个
 * [PrivilegedShell],再往下 [LocalFileSystem.elevation] 只知道"普通 API 失败时
 * 有个东西可以再试一次"。所以整个 UI、`CopyEngine`、缩略图、搜索一行都不用改。
 *
 * ★ 授权只能在前台、由用户点出来:Android 10+ 禁止后台启动 Activity,
 * Magisk 的授权框弹不出来只会退化成一条通知,用户多半看不到,表现就是"卡住不动"。
 */
object Privileged {

    const val OFF = 0
    const val ROOT = 1
    const val SHIZUKU = 2

    /** Shizuku 管理器的包名;`<queries>` 里也声明了同一个。 */
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    private const val TAG = "twig-priv"

    init {
        // 纯 JVM 模块的诊断出口接到 logcat 上;`adb logcat -s twig-priv` 一条命令看全。
        PrivilegedShell.log = { Log.w(TAG, it) }
    }

    @Volatile
    private var shell: PrivilegedShell? = null

    /** 当前生效的模式;失败或未开启为 [OFF]。 */
    @Volatile
    var active: Int = OFF
        private set

    /** 特权 shell 实际跑在哪个 uid 上:0 = root,2000 = shell(Shizuku)。 */
    val uid: Int get() = shell?.uid ?: -1

    /**
     * 开启特权访问。**阻塞,必须在后台线程调用**(root 那条会一直等到用户在
     * 授权框上点完)。返回 null 表示成功,否则是可直接展示给用户的失败原因
     * (本地化的一句话 + 底层的真实异常)。
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
            // ★ 一定要把真原因带出来。"起不了特权进程"这句话对用户毫无用处:没 root、
            // 授权被拒、Shizuku 服务停了、反射的私有方法解析不到——四种情况长得一模一样,
            // 而只有一种是用户自己能处理的。诊断信息同时进 logcat 和提示框。
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

    /** 一行行摆出来的状态,失败时连同原因写进 logcat / 给用户看。 */
    fun diagnostics(ctx: Context): String {
        fun q(body: () -> Any?): String = runCatching { body()?.toString() ?: "null" }
            .getOrElse { "!" + (it.javaClass.simpleName) }
        return listOf(
            "installed=" + q { shizukuInstalled(ctx) },
            "binder=" + q { Shizuku.pingBinder() },
            "version=" + q { Shizuku.getVersion() },
            "serverUid=" + q { Shizuku.getUid() },
            "preV11=" + q { Shizuku.isPreV11() },
            // ★ 别直接印数值:PERMISSION_GRANTED 恰好是 **0**,"granted=0" 读起来
            // 像"没授权",而它的意思正相反。诊断串是给人在着急的时候看的。
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

    /** 关闭并卸掉回落钩子;本地文件系统立刻恢复成"只用得到自己那份权限"。 */
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
     * 进程启动时按用户上次的选择恢复。
     *
     * Shizuku 的 binder **不是同步就位的**(要等它的 provider 把 binder 递过来),
     * 所以不能在这里直接问"通不通",得挂 sticky 监听 —— 已经到了的话会立刻回调,
     * 没到就等它到。root 那条没有这个问题,直接后台连。
     */
    fun restore(ctx: Context) {
        val mode = Prefs.privilegedMode(ctx)
        if (mode == OFF) return
        val app = ctx.applicationContext
        if (mode == SHIZUKU) {
            Shizuku.addBinderReceivedListenerSticky {
                background {
                    // ★ 每次回调都重新读一遍偏好。这个监听器是**进程级、注册后一直在**的,
                    // 而 binder 不止到达一次 —— Shizuku 服务/管理器重启都会重发。
                    // 写死 SHIZUKU 的话,用户后来在设置里关掉或改成 Root,下一次重发就会
                    // 把模式硬掰回 Shizuku 并写回 Prefs,选择被静默推翻。
                    if (Prefs.privilegedMode(app) == SHIZUKU) enable(app, SHIZUKU)
                }
            }
        } else {
            background { enable(app, mode) }
        }
    }

    // ---- Shizuku 状态查询(全部包 runCatching:没装 Shizuku 时这些类会抛) ----

    /** Shizuku 服务在跑(binder 已就位)。 */
    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku 管理器装没装(与"跑没跑"是两回事,提示文案要分开)。 */
    fun shizukuInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0) != null
    }.getOrDefault(false)

    private fun background(body: () -> Unit) {
        Thread(body, "twig-priv-connect").apply { isDaemon = true }.start()
    }

    /**
     * Shizuku 起进程。和 [SuLauncher] 唯一的区别就是这一行 —— 拿到的同样是一个
     * 带 stdin/stdout/stderr 的进程,只不过跑在 shell(uid 2000)身上而不是 root。
     * `ShizukuRemoteProcess` 本身就是 `java.lang.Process` 的子类,所以直接套用同一个包装。
     *
     * ★ `Shizuku.newProcess` 在 13.x 里被改成了 **private**(上游想把大家往
     * `bindUserService` 那条路上赶),只能反射调。代价是 R8 必须 keep 住
     * `rikka.shizuku.**` 的方法名(已在 proguard-rules.pro 里),否则这里 100% NoSuchMethod。
     *
     * 想换成官方路线的话,那是**另一套东西**:`bindUserService` 把我们自己的代码跑在
     * shell 进程里,能直接用 java.io、还能把 ParcelFileDescriptor 传回来做真随机读,
     * 但要写 AIDL + Service + 绑定生命周期,而且**对 root 完全不适用** —— 那时就得
     * 维护两套毫不相干的后端,现在这一层"都是个特权进程"的抽象也就没了。
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
