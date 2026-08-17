package com.twig.app.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.twig.app.MainActivity
import com.twig.app.R

/**
 * 共享会话的进程内单例:HTTP 服务 + 发现应答器 + 各种"别被杀"的锁都归它管,
 * [ShareService] 只是它在系统眼里的那张前台服务通行证。
 *
 * 与 `Transfers`/`TransferService` 同一套分工:**状态归会话,服务只负责活着**。
 * 好处是"端口被占用"这类失败在 [start] 里当场同步抛出来,能直接摆给用户看——
 * 要是把绑定塞进 `onStartCommand`,用户只会看到服务起来又悄悄没了。
 */
object WebShare {

    /** 正在跑的一次共享;null = 没开。 */
    class Session(
        val cfg: ShareConfig,
        val scopeLabel: String,
        internal val server: HttpServer,
        internal val beacon: Discovery.Beacon,
    ) {
        /** 电脑上该输的地址,WiFi 网卡的排在最前(见 [Net.addresses])。 */
        fun urls(): List<String> = Net.addresses().map { "http://$it:${cfg.port}" }

        fun primaryUrl(): String = urls().firstOrNull() ?: "http://0.0.0.0:${cfg.port}"
    }

    @Volatile
    var session: Session? = null
        private set

    val isRunning: Boolean get() = session != null

    /** 状态变化时通知界面刷新(共享对话框开着的话)。 */
    @Volatile var onStateChanged: (() -> Unit)? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 起服务。**阻塞 IO**(可能要先把 scope 指向的那台服务器重连上),须在工作线程调用。
     * 端口绑定失败等原因会抛异常,由调用方展示。
     */
    @Synchronized
    fun start(ctx: Context, cfg: ShareConfig, scopeLabel: String) {
        if (session != null) throw IllegalStateException("already running")
        val app = ctx.applicationContext
        val root = ShareRoot(app, cfg.scope)
        root.ensureReady()

        val auth = if (cfg.needsAuth) HttpServer.BasicAuth(cfg.authUser, cfg.password) else null
        val server = HttpServer(cfg.port, auth, ShareHandler(app, cfg, root)) { active ->
            if (active) acquireWake(app) else releaseWake()
        }
        server.start() // 绑不上端口就在这儿抛

        val beacon = Discovery.Beacon(cfg, scopeLabel)
        beacon.start()

        session = Session(cfg, scopeLabel, server, beacon)
        acquireWifi(app)
        try {
            ShareService.start(app)
        } catch (e: Exception) {
            stop(app) // 前台服务起不来就整个回滚,别留一个没有通知栏保护的裸服务
            throw e
        }
        onStateChanged?.invoke()
    }

    @Synchronized
    fun stop(ctx: Context) {
        val s = session ?: return
        session = null
        runCatching { s.beacon.stop() }
        runCatching { s.server.stop() }
        releaseWake()
        releaseWifi()
        runCatching { ctx.applicationContext.stopService(Intent(ctx.applicationContext, ShareService::class.java)) }
        onStateChanged?.invoke()
    }

    // ---- 保活的三层 ----

    /**
     * WiFi 锁:息屏后系统会让 WiFi 进省电态甚至断开,那样局域网里这台设备就"消失"了。
     * 整个共享期间一直持有——它只是不让 WiFi 睡,代价远小于把传到一半的文件掐断。
     */
    private fun acquireWifi(ctx: Context) {
        if (wifiLock != null) return
        runCatching {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = wm.createWifiLock(mode, "twig:share").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifi() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    /**
     * CPU 唤醒锁:**只在有请求正在处理时持有**(见 [HttpServer] 的 onActive)。
     * 空闲时死攥着 partial wake lock 是纯耗电——那时候进程有前台服务保着不会被杀,
     * 醒不醒着无所谓;而正在往外传文件时被 doze 掐掉 CPU,传输就断在半路。
     */
    @Synchronized
    private fun acquireWake(ctx: Context) {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "twig:share-io").apply {
                setReferenceCounted(false)
                // 兜底超时:万一 onActive(false) 因为异常没回来,锁也不会永远挂着
                acquire(10 * 60 * 1000L)
            }
        }
    }

    @Synchronized
    private fun releaseWake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}

/**
 * 共享期间的前台服务。
 *
 * 它自己不干活([WebShare] 才持有 socket 和线程),存在的唯一意义是**让系统别回收这个
 * 进程**:共享一开就可能几小时没人动,后台进程随时会被清理,而那会让局域网里正在
 * 拷贝的电脑端直接断线。通知栏那条同时也是"共享还开着"的唯一可见提示,以及一步停止的入口。
 */
class ShareService : Service() {

    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.share_channel),
                    NotificationManager.IMPORTANCE_LOW, // 常驻提示,不该出声
                ).apply { setShowBadge(false) },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WebShare.stop(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val s = WebShare.session ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification(s))
        // START_STICKY 没有意义:进程真被杀了,socket 和线程都没了,系统重启服务只会
        // 拿到一个 session == null 的空壳(上面那条分支)。共享是用户显式开的,
        // 让它跟着进程一起结束比"自己悄悄复活一个连不上的服务"诚实。
        return START_NOT_STICKY
    }

    private fun notification(s: WebShare.Session): Notification {
        val mode = getString(
            if (s.cfg.readOnly) R.string.share_mode_readonly else R.string.share_mode_writable,
        )
        return baseBuilder()
            .setContentTitle(getString(R.string.share_notif_title, s.primaryUrl()))
            .setContentText(getString(R.string.share_notif_text, s.scopeLabel, mode))
            .setSmallIcon(R.drawable.ic_share_wifi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, MainActivity.shareIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.share_stop),
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, ShareService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build(),
            )
            .build()
    }

    @Suppress("DEPRECATION")
    private fun baseBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)

    override fun onDestroy() {
        super.onDestroy()
        // 服务被系统结束(用户划掉任务、内存回收)时,别留一个还在监听端口的僵尸服务
        WebShare.stop(this)
    }

    companion object {
        private const val CHANNEL = "twig_share"
        private const val NOTIF_ID = 4401
        private const val ACTION_STOP = "com.twig.app.SHARE_STOP"

        fun start(ctx: Context) {
            val intent = Intent(ctx, ShareService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
