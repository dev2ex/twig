package com.twig.app.priv

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * app 这一侧对 [TwigPrivService] 的绑定管理。
 *
 * 一个进程只保留一条连接,多条终端会话共用 —— 每条会话只是让它再 forkpty 一次,
 * 而拉起一个特权进程要几百毫秒、还要走一次 Shizuku 的鉴权。
 */
object PrivService {

    private const val TAG = "twig-priv"

    /**
     * `version` 变了 Shizuku 会重启那个进程。**改了 [TwigPrivService] 的行为就要 +1**,
     * 否则用户机上可能还连着旧代码跑起来的常驻进程。
     */
    private const val VERSION = 3

    private val args = Shizuku.UserServiceArgs(
        ComponentName("com.twig.app", TwigPrivService::class.java.name),
    )
        // daemon(false):跟着 app 走,退出就收掉,不在用户机上留常驻进程
        .daemon(false)
        .processNameSuffix("priv")
        .debuggable(false)
        .version(VERSION)

    /**
     * 一个只为"还活着"这件事存在的 binder:它随本进程一起消失,助手 linkToDeath
     * 到它身上就能在 app 没了的时候自杀。
     */
    private val aliveToken = android.os.Binder()

    @Volatile
    private var service: ITwigPrivService? = null

    private val lock = Any()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = if (binder != null && binder.pingBinder()) {
                ITwigPrivService.Stub.asInterface(binder)
            } else {
                null
            }
            // 立刻把存活凭据交过去,免得 app 意外退出时留下一个 root 进程
            runCatching { s?.attach(aliveToken) }
                .onFailure { Log.w(TAG, "attach failed", it) }
            synchronized(lock) {
                service = s
                (pending ?: return@synchronized).countDown()
            }
            Log.i(TAG, "user service connected: ${runCatching { s?.uid }.getOrNull()}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "user service disconnected")
            synchronized(lock) { service = null }
        }
    }

    @Volatile
    private var pending: CountDownLatch? = null

    /**
     * 取到助手接口,必要时先把它拉起来。**阻塞,只能在后台线程调用** ——
     * Shizuku 起那个进程要跑一遍 app_process,几百毫秒起步。
     */
    fun get(timeoutMs: Long = 20_000): ITwigPrivService? {
        service?.let { if (runCatching { it.asBinder().pingBinder() }.getOrDefault(false)) return it }
        val latch = synchronized(lock) {
            pending ?: CountDownLatch(1).also { pending = it }
        }
        runCatching { Shizuku.bindUserService(args, conn) }.onFailure {
            Log.w(TAG, "bindUserService threw", it)
            synchronized(lock) { pending = null }
            return null
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(lock) { pending = null }
        return service
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        synchronized(lock) { service = null }
    }

    /** APK 路径与当前 ABI —— 助手要靠它们把 libtwigpty.so 从包里抠出来。 */
    fun apkPath(ctx: Context): String = ctx.applicationInfo.sourceDir

    fun abi(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
}
