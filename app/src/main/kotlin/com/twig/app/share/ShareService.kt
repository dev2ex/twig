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
 * In-process singleton for the share session: the HTTP server, the discovery
 * responder, and the various "please don't kill me" locks all live here;
 * [ShareService] is just its foreground-service ticket in the system's eyes.
 *
 * Same division of labour as `Transfers` / `TransferService`: **the session
 * owns the state, the service is just here to stay alive**. The benefit is
 * that failures like "port already in use" surface synchronously inside
 * [start] and can be shown to the user immediately — if the bind were
 * stuffed into `onStartCommand`, the user would only see the service come
 * up and silently disappear.
 */
object WebShare {

    /** A running share session; null = none. */
    class Session(
        val cfg: ShareConfig,
        val scopeLabel: String,
        internal val server: HttpServer,
        internal val beacon: Discovery.Beacon,
    ) {
        /** Addresses the user can type into their computer, with the WiFi interface first (see [Net.addresses]). */
        fun urls(): List<String> = Net.addresses().map { "http://$it:${cfg.port}" }

        fun primaryUrl(): String = urls().firstOrNull() ?: "http://0.0.0.0:${cfg.port}"
    }

    @Volatile
    var session: Session? = null
        private set

    val isRunning: Boolean get() = session != null

    /** Notify the UI to refresh when the state changes (if the share dialog is open). */
    @Volatile var onStateChanged: (() -> Unit)? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Start the service. **Blocking I/O** (may need to reconnect the server
     * the scope points at first), so it must be called on a worker thread.
     * A failed port bind, etc., throws here and the caller is expected to
     * display it.
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
        server.start() // a failed bind throws right here

        val beacon = Discovery.Beacon(cfg, scopeLabel)
        beacon.start()

        session = Session(cfg, scopeLabel, server, beacon)
        acquireWifi(app)
        try {
            ShareService.start(app)
        } catch (e: Exception) {
            stop(app) // if the foreground service cannot start, roll back the whole thing; never leave a bare service running with no notification to back it
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

    // ---- The three layers of staying alive ----

    /**
     * WiFi lock: once the screen goes off the system may put WiFi into a
     * power-save state or even drop it, and the device would "disappear"
     * from the LAN. Hold it for the entire share session — all it does is
     * keep WiFi awake, which is far cheaper than cutting a half-finished
     * file transfer in two.
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
     * CPU wake lock: **held only while a request is being handled** (see
     * onActive in [HttpServer]). Holding a partial wake lock while idle is
     * pure battery drain — the foreground service already keeps the process
     * alive, so awake or asleep makes no difference; but having doze cut the
     * CPU mid-transfer would break the file in half.
     */
    @Synchronized
    private fun acquireWake(ctx: Context) {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "twig:share-io").apply {
                setReferenceCounted(false)
                // Failsafe timeout: if onActive(false) never comes back due
                // to an exception, the lock does not stay held forever
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
 * The foreground service that runs while a share is active.
 *
 * It does not do any work itself ([WebShare] owns the socket and threads);
 * its only purpose is to **keep the system from reclaiming this process**:
 * a share may sit idle for hours, and a background process is fair game
 * for cleanup at any time, which would abruptly disconnect the computer
 * on the LAN in the middle of a copy. The notification is also the only
 * visible hint that a share is still on, plus a one-tap entry point for
 * stopping it.
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
                    NotificationManager.IMPORTANCE_LOW, // persistent hint; should not make sound
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
        // START_STICKY is pointless: if the process really was killed, the
        // sockets and threads are gone, and the system restarting the service
        // would only produce an empty shell with session == null (the
        // branch above). Sharing is something the user turned on
        // explicitly; having it die with the process is more honest than
        // quietly resurrecting a service that nobody can reach.
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
        // When the system ends the service (user swipes the task, memory
        // reclaim), do not leave a zombie service still listening on the port
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
