package com.twig.app.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.twig.app.R

/**
 * Foreground service that exists for one reason: **keep the process out of the freezer while terminal
 * sessions are open.**
 *
 * A terminal session outlives the Activity by design ([TermManager] is static, sessions keep accumulating
 * output in the background). But an app with no visible Activity and no foreground service becomes a cached
 * process, and a cached process on Android 12+ is frozen: its threads stop running. For a local shell that
 * is harmless — the PTY just waits. For SSH it is fatal: the keepalive thread stops sending, the server and
 * every NAT between here and it see a connection that has gone silent, and by the time the user comes back
 * the connection is long dead. This is why termux keeps a notification up the whole time it has a session,
 * and why a terminal that is "always connected" cannot be built out of an Activity alone.
 *
 * The service follows the session list rather than being started and stopped by hand: [sync] is called after
 * every change to [TermManager], starts the service on the first session and stops it after the last one.
 */
class TerminalService : Service() {

    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.terminal_service_channel),
                    NotificationManager.IMPORTANCE_LOW, // A session that is simply open shouldn't sound or peek
                ).apply { setShowBadge(false) },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END_ALL) {
            // closeAll() runs [sync] itself, which stops this service once the list is empty.
            TermManager.closeAll()
            return START_NOT_STICKY
        }
        val sessions = TermManager.list()
        if (sessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Also the refresh path: startForeground on an already-started service just updates the notification.
        startForeground(NOTIF_ID, buildNotification(sessions))
        return START_NOT_STICKY
    }

    /**
     * What the session list is doing right now. The title of the current session is the useful line — while
     * htop runs, that is what it says — and the count only appears once there is more than one, so the
     * ordinary single-session case reads as a plain label rather than a status report.
     */
    private fun buildNotification(sessions: List<TermSession>): Notification {
        val current = TermManager.current ?: sessions.last()
        val title = if (sessions.size > 1) {
            getString(R.string.terminal_service_title_many, sessions.size)
        } else {
            getString(R.string.terminal_service_title_one)
        }
        return baseBuilder()
            .setContentTitle(title)
            .setContentText(current.title)
            .setSmallIcon(R.drawable.ic_terminal)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent())
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.terminal_end_all),
                    endAllIntent(),
                ).build(),
            )
            .build()
    }

    /** No extras: [TerminalActivity] reads that as "just show me the current session", not "open a new one". */
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun endAllIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_END_ALL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Suppress("DEPRECATION")
    private fun baseBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)

    companion object {
        private const val CHANNEL = "twig_terminal"
        private const val NOTIF_ID = 4401
        private const val ACTION_END_ALL = "com.twig.app.TERMINAL_END_ALL"

        /**
         * Bring the service in line with the session list: running while sessions exist, gone once the last
         * one is closed. Called after every change to [TermManager], from whichever thread made it.
         *
         * A failure to start is logged and swallowed: sessions are only ever created from the terminal screen,
         * so the background-start restriction shouldn't apply, but a session that opens is worth more than the
         * notification that would have kept it warm.
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            val i = Intent(app, TerminalService::class.java)
            runCatching {
                if (TermManager.isEmpty()) {
                    app.stopService(i)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(i)
                } else {
                    app.startService(i)
                }
            }.onFailure { Log.w("TwigTerm", "terminal service", it) }
        }
    }
}
