package com.twig.app.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.twig.app.Connections
import com.twig.app.R
import com.twig.app.RemoteCmd
import com.twig.core.FsRegistry
import com.twig.fs.network.SftpFileSystem
import java.util.concurrent.atomic.AtomicInteger

/**
 * 静默执行远程命令的前台服务。
 *
 * 为什么是前台服务而不是随手起个线程:命令可能跑几十秒到几分钟,而发起它的
 * 快捷方式中转页(`RunCommandActivity`)立刻就 finish 了——没有前台通知的话,
 * 进程随时可能在命令跑完前被回收,用户既看不到结果也不知道跑没跑。
 *
 * 结果的呈现分两级:短输出直接 Toast,完整输出进通知(可展开);失败一定进
 * 通知,并把 stderr 带上——静默执行最怕的就是"点了没反应也不知道为什么"。
 */
class CmdService : Service() {

    private lateinit var nm: NotificationManager
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.cmd_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent?.let { RemoteCmd.from(it) }
        if (cmd == null) {
            if (running.get() == 0) stopSelf()
            return START_NOT_STICKY
        }
        val id = NEXT_ID.getAndIncrement()
        startForeground(RUNNING_ID, runningNotification(cmd.label))
        running.incrementAndGet()

        Thread({
            val result = runCatching { execute(cmd) }
            main.post { finishOne(cmd, id, result) }
        }, "twig-cmd").start()
        return START_NOT_STICKY
    }

    /** 现连现取 scheme:快捷方式存的是连接标签,冷启动时这台服务器还没注册过。 */
    private fun execute(cmd: RemoteCmd): SftpFileSystem.ExecResult {
        val conn = Connections.find(this, cmd.connLabel)
            ?: throw IllegalStateException(getString(R.string.cmd_conn_missing))
        val scheme = Connections.ensure(this, conn)
        val fs = FsRegistry.of(scheme) as? SftpFileSystem
            ?: throw IllegalStateException(getString(R.string.cmd_conn_missing))
        return fs.execFull(RemoteCmd.shellLine(cmd))
    }

    private fun finishOne(
        cmd: RemoteCmd,
        id: Int,
        result: Result<SftpFileSystem.ExecResult>,
    ) {
        val (title, body) = result.fold(
            onSuccess = { r ->
                val text = (r.stdout.trim() + "\n" + r.stderr.trim()).trim()
                val head = if (r.ok) {
                    getString(R.string.cmd_done, cmd.label)
                } else {
                    getString(R.string.cmd_failed_code, cmd.label, r.code)
                }
                head to text
            },
            onFailure = { getString(R.string.cmd_failed, cmd.label) to (it.message ?: "") },
        )
        val ok = result.getOrNull()?.ok == true

        // 短输出且成功:Toast 就够了,不留通知打扰;其余一律留通知(可展开看全文)
        if (ok && body.length <= TOAST_MAX && !body.contains('\n')) {
            Toast.makeText(this, if (body.isEmpty()) title else body, Toast.LENGTH_LONG).show()
        } else {
            nm.notify(id, resultNotification(title, body))
        }

        if (running.decrementAndGet() <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun runningNotification(label: String): Notification =
        baseBuilder()
            .setContentTitle(getString(R.string.cmd_running, label))
            .setSmallIcon(R.drawable.ic_terminal)
            .setOngoing(true)
            .build()

    private fun resultNotification(title: String, body: String): Notification =
        baseBuilder()
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(body.takeLast(NOTIF_MAX)))
            .setSmallIcon(R.drawable.ic_terminal)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, TerminalActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    @Suppress("DEPRECATION")
    private fun baseBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)

    companion object {
        private const val CHANNEL = "twig_cmd"
        private const val RUNNING_ID = 4201
        private const val TOAST_MAX = 120
        private const val NOTIF_MAX = 4000
        private val NEXT_ID = AtomicInteger(4210)

        fun start(ctx: Context, cmd: RemoteCmd) {
            val intent = cmd.putInto(Intent(ctx, CmdService::class.java))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
