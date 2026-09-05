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
 * The app-side binding management for [TwigPrivService].
 *
 * Only one connection is kept per process, shared by all terminal sessions —
 * each session simply asks it to forkpty again, whereas starting a privileged
 * process takes several hundred milliseconds and goes through one round of
 * Shizuku's permission check.
 */
object PrivService {

    private const val TAG = "twig-priv"

    /**
     * Changing `version` makes Shizuku restart that process. **Bump it by +1
     * whenever [TwigPrivService]'s behaviour changes**, otherwise the user's
     * device may still be running a resident process started from the old code.
     */
    private const val VERSION = 3

    private val args = Shizuku.UserServiceArgs(
        ComponentName("com.twig.app", TwigPrivService::class.java.name),
    )
        // daemon(false): lives with the app — goes away when it exits, no
        // resident process left on the user's device
        .daemon(false)
        .processNameSuffix("priv")
        .debuggable(false)
        .version(VERSION)

    /**
     * A binder that exists only to be "alive": it disappears with this process,
     * and the helper links to its death so it can kill itself when the app is
     * gone.
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
            // Hand over the liveness token right away, so that if the app
            // crashes unexpectedly we don't leave a root process behind
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
     * Get the helper interface, starting it if necessary. **Blocks, must be
     * called on a background thread** — Shizuku starting that process runs
     * through app_process, which takes several hundred milliseconds.
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

    /** APK path and current ABI — the helper uses these to extract libtwigpty.so from the package. */
    fun apkPath(ctx: Context): String = ctx.applicationInfo.sourceDir

    fun abi(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
}
