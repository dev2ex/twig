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
import com.twig.app.Format
import com.twig.app.MainActivity
import com.twig.app.R
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台传输(复制/移动/压缩)的会话状态,进程内单例。
 *
 * 为什么要有这一层:进度框原来自己持有整个任务——引擎回调直接写它的 View,任务
 * 生命周期挂在 `viewLifecycleOwner.lifecycleScope` 上。于是进度框一关任务就没了着落,
 * 而且没有前台通知,复制期间切走应用随时可能被系统回收(大文件传网络盘尤其久)。
 *
 * 现在:**任务归会话,进度框只是它的一个观察者**。会话由 [TransferService] 这个前台
 * 服务跑着(通知栏有进度条,进程不会被回收),界面可以随时挂上来([ui])或摘掉
 * (「转到后台」按钮 / 界面销毁),摘掉时任务照跑不误。
 *
 * 同时只跑一个会话:进度框原来是 `setCancelable(false)` 的模态框,本来就不可能并发
 * 发起第二个;能转后台之后才有这种可能,这里明确挡掉(见 [start] 返回值)。
 */
object Transfers {

    /** 一次传输要干的活儿。**纯数据**——会话会比发起它的界面活得久,不能捕获 Fragment。 */
    sealed class Work {
        abstract val items: List<XFile>

        class Copy(
            override val items: List<XFile>,
            val dest: XFile,
            val move: Boolean,
            val fromClipboard: Boolean,
        ) : Work()

        /**
         * 目录对比页的复制/同步:**每一项有自己的目标目录**(源在树里的位置决定它该落到
         * 对侧的哪个子目录),而不是 [Copy] 那样一批共用一个 dest。目标目录由发起方
         * 保证已存在(对比页在构造 pairs 时逐级建好,建不出来就不会走到这里)。
         */
        class Sync(
            val pairs: List<Pair<XFile, XFile>>,
            val move: Boolean,
        ) : Work() {
            override val items: List<XFile> get() = pairs.map { it.first }
        }

        class Compress(
            override val items: List<XFile>,
            val destDir: XFile,
            val target: XFile,
            val format: ArchiveWriter.Format,
            val move: Boolean,
            /** 非空则加密(AES-256);对话框里没填就是 null。 */
            val password: String? = null,
        ) : Work()
    }

    /**
     * 一次同名冲突的问答:传输线程 [await] 阻塞等着,界面那边 [answer] 放行。
     * 界面没挂着的时候就一直等——通知会改成「等待确认…」,点回来即弹框。
     */
    class Conflict(val src: XFile, val existing: XFile) {
        private val slot = ArrayBlockingQueue<Array<CopyEngine.Decision?>>(1)
        fun await(): CopyEngine.Decision? = slot.take()[0]
        fun answer(d: CopyEngine.Decision?) { slot.offer(arrayOf(d)) }
    }

    /** 界面观察者;全部在主线程回调。 */
    interface Ui {
        fun onProgress(s: Session)
        fun onConflict(s: Session, c: Conflict)
        fun onFinished(s: Session)
    }

    /**
     * 一次传输的全部状态。进度字段由传输线程写、主线程读(@Volatile 够用:每个字段
     * 独立、读到稍旧的值只是少刷一帧),界面挂上来时照着快照渲染即可,不必等下一次回调。
     */
    class Session(
        val work: Work,
        val titleRes: Int,
        val destLabel: String,
        val destIcon: Int,
        plan0: CopyEngine.Plan?,
    ) {
        @Volatile var plan: CopyEngine.Plan? = plan0
        @Volatile var fileName = ""
        @Volatile var fileProgress = 0 // 0..1000
        @Volatile var totalProgress = 0 // 0..1000
        @Volatile var dirsLeft = 0
        @Volatile var filesLeft = 0
        @Volatile var speed = 0.0 // B/s,指数滑动平均
        @Volatile var etaSeconds = -1.0 // <0 = 还算不出来
        @Volatile var pendingConflict: Conflict? = null
        @Volatile var finished: Result<Unit>? = null

        /** 通知栏刷新钩子(由 [TransferService] 装上),等冲突这种"没有进度事件"的时刻也要能刷。 */
        @Volatile internal var tick: (() -> Unit)? = null

        val cancelled = AtomicBoolean(false)

        /** "全部同样处理"记住的选择(冲突框勾了才有)。 */
        @Volatile var applyAll: CopyEngine.Decision? = null

        fun cancel() {
            cancelled.set(true)
            pendingConflict?.answer(null) // 正卡在冲突框上:放行,让传输线程看到取消标志
        }

        val isCompress: Boolean get() = work is Work.Compress
    }

    @Volatile var active: Session? = null
        private set

    /** 跑完但还没被界面消费掉的会话(切后台时完成的);见 [consumeFinished]。 */
    @Volatile private var pendingResult: Session? = null

    @Volatile var ui: Ui? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    /**
     * 起一个会话并拉起前台服务。已有会话在跑时返回 false(调用方 toast 提示)。
     */
    @Synchronized
    fun start(ctx: Context, session: Session): Boolean {
        if (active != null) return false
        active = session
        try {
            TransferService.start(ctx)
        } catch (e: Exception) {
            active = null // 服务起不来就回滚,否则 active 卡住、以后再也开不了传输
            throw e
        }
        return true
    }

    /**
     * 界面挂上来/摘掉。挂上来时若有等待中的冲突立刻补一次回调——否则任务会一直
     * 卡在那儿,而用户刚点通知回来看到的是一个不动的进度条。
     */
    fun attach(u: Ui?) {
        ui = u
        val s = active ?: return
        if (u == null) return
        u.onProgress(s)
        s.pendingConflict?.let { u.onConflict(s, it) }
    }

    /** 取走"已完成但界面没在场"的会话,由 [MainActivity] 回到前台时收尾(刷新面板 + 提示)。 */
    @Synchronized
    fun consumeFinished(): Session? = pendingResult.also { pendingResult = null }

    // ---- 以下由 TransferService 的传输线程调用 ----

    // ★ 别把这个方法叫 run:匿名内部类里 `run { }` 会解析成标准库的作用域函数,
    // 悄悄绕过这里(与 NativeSmbClient.exec 改名同一个坑,见 CLAUDE.md)
    internal fun execute(ctx: Context, s: Session, onTick: () -> Unit) {
        s.tick = onTick
        val listener = object : CopyEngine.ProgressListener {
            private var lastUi = 0L
            private var lastBytes = 0L
            private var lastT = android.os.SystemClock.elapsedRealtime()

            override fun onFile(file: XFile) {
                s.fileName = file.name
                s.fileProgress = 0
                post(s)
            }

            override fun onFileBytes(copied: Long, size: Long) {
                s.fileProgress = if (size > 0) ((copied * 1000) / size).toInt() else 0
            }

            override fun onBytes(copiedTotal: Long, totalBytes: Long) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastUi < 200) return // 节流 ~5Hz:速度平均与界面刷新都够用了
                val dt = (now - lastT) / 1000.0
                if (dt > 0) {
                    val inst = (copiedTotal - lastBytes) / dt
                    s.speed = if (s.speed == 0.0) inst else s.speed * 0.7 + inst * 0.3
                }
                lastUi = now; lastT = now; lastBytes = copiedTotal
                s.totalProgress = if (totalBytes > 0) ((copiedTotal * 1000) / totalBytes).toInt() else 0
                s.etaSeconds = if (s.speed > 1) (totalBytes - copiedTotal) / s.speed else -1.0
                post(s)
            }

            override fun onItemDone(isDir: Boolean) {
                if (isDir) { if (s.dirsLeft > 0) s.dirsLeft-- } else { if (s.filesLeft > 0) s.filesLeft-- }
                post(s)
            }
        }

        val result = runCatching {
            val plan = s.plan
                ?: runCatching { CopyEngine.plan(s.work.items) }.getOrNull()
                ?: CopyEngine.Plan(0, 0, 0)
            s.plan = plan
            s.dirsLeft = plan.dirs
            s.filesLeft = plan.files
            post(s)

            when (val w = s.work) {
                is Work.Copy -> CopyEngine.transfer(
                    w.items, w.dest, w.move, listener,
                    { s.cancelled.get() }, resolverFor(s), plan.bytes,
                )
                is Work.Sync -> {
                    // 按目标目录分组,逐组搬。总进度会被每组从 0 重新报一次,靠 base
                    // 偏移把它接成一条连续的线——否则进度条每换一个目录就往回跳。
                    var base = 0L
                    var lastCopied = 0L
                    val offset = object : CopyEngine.ProgressListener by listener {
                        override fun onBytes(copiedTotal: Long, totalBytes: Long) {
                            lastCopied = copiedTotal
                            listener.onBytes(base + copiedTotal, plan.bytes)
                        }
                    }
                    for ((dest, srcs) in w.pairs.groupBy({ it.second }, { it.first })) {
                        if (s.cancelled.get()) break
                        lastCopied = 0L
                        CopyEngine.transfer(
                            srcs, dest, w.move, offset,
                            { s.cancelled.get() }, resolverFor(s), plan.bytes,
                        )
                        base += lastCopied
                    }
                }
                is Work.Compress -> {
                    ArchiveWriter.compress(
                        w.items, w.destDir, w.target, w.format, listener,
                        { s.cancelled.get() }, plan.bytes, ctx.cacheDir, w.password,
                    )
                    // 打包成功才动源文件;取消/失败时半成品已被删掉,源当然要留着
                    if (w.move) w.items.forEach { FsRegistry.of(it).delete(it) }
                }
            }
        }.map { }

        s.finished = result
        finish(s)
    }

    /** 冲突决策:传输线程阻塞等,界面(在场的话)弹框回答。 */
    private fun resolverFor(s: Session) = CopyEngine.ConflictResolver { src, existing ->
        s.applyAll ?: run {
            val c = Conflict(src, existing)
            s.pendingConflict = c
            post(s) // 通知栏改显示「等待确认…」——卡在这儿时没有别的进度事件会来
            main.post { ui?.onConflict(s, c) }
            val d = c.await()
            s.pendingConflict = null
            if (d == null) s.cancelled.set(true)
            d
        }
    }

    private fun post(s: Session) {
        s.tick?.invoke() // 通知栏(服务自己节流)
        main.post { if (active === s) ui?.onProgress(s) }
    }

    private fun finish(s: Session) {
        active = null
        main.post {
            val u = ui
            if (u != null) {
                if (active == null) ui = null // 已经有下一个会话挂上来了就别摘人家的
                u.onFinished(s)
            } else {
                // 界面不在场:留着结果,等 MainActivity 回到前台再刷新面板并提示
                pendingResult = s
            }
        }
    }
}

/**
 * 跑传输会话的前台服务。
 *
 * 单纯起个线程是不够的:复制大目录(尤其到 SMB/SFTP 这类慢速来源)可能几十分钟,
 * 期间用户会切走应用——没有前台通知的话进程随时可能被回收,复制就断在半路。
 * 通知栏那条进度条同时也是「转到后台」之后唯一的入口:点它回到 [MainActivity]
 * 重新弹出进度框(见 [MainActivity.transferIntent])。
 */
class TransferService : Service() {

    private lateinit var nm: NotificationManager
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.transfer_channel),
                    NotificationManager.IMPORTANCE_LOW, // 进度条不该出声/浮动打断
                ).apply { setShowBadge(false) },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Transfers.active?.cancel()
            return START_NOT_STICKY
        }
        val session = Transfers.active ?: run { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification(session))
        Thread({
            Transfers.execute(this, session) { tick(session) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            doneNotification(session)?.let { nm.notify(DONE_ID, it) }
            stopSelf()
        }, "twig-transfer").start()
        return START_NOT_STICKY
    }

    /**
     * 进度变化时刷通知——1s 一次足够,更密只是白白唤醒系统 UI。
     * 唯一不节流的是"等着用户决定冲突":那之后不会再有进度事件来触发刷新了。
     */
    private fun tick(s: Session) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (s.pendingConflict == null && now - lastNotify < 1000) return
        lastNotify = now
        runCatching { nm.notify(NOTIF_ID, buildNotification(s)) }
    }

    private fun buildNotification(s: Session): Notification {
        val waiting = s.pendingConflict != null
        val text = when {
            waiting -> getString(R.string.transfer_waiting)
            s.fileName.isNotEmpty() -> s.fileName
            else -> ""
        }
        val speed = if (waiting || s.speed <= 1) "" else getString(
            R.string.copy_speed, Format.size(s.speed.toLong()),
        )
        return baseBuilder()
            .setContentTitle(getString(s.titleRes))
            .setContentText(text)
            .setSubText(speed.ifEmpty { null })
            .setSmallIcon(if (s.isCompress) R.drawable.ic_compress else R.drawable.ic_copy)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(1000, s.totalProgress, s.plan == null)
            .setContentIntent(contentIntent())
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.dialog_cancel),
                    cancelIntent(),
                ).build(),
            )
            .build()
    }

    /**
     * 完成/失败的收尾通知;界面还在场的话它自己会 toast,不必再打扰。
     * **取消不发**:那是用户自己按的,结果他当场就知道,再补一条只是噪音。
     */
    private fun doneNotification(s: Session): Notification? {
        if (Transfers.ui != null) return null
        if (s.cancelled.get()) return null
        val r = s.finished ?: return null
        val failed = r.isFailure && r.exceptionOrNull() !is ArchiveWriter.Cancelled
        if (r.isFailure && !failed) return null // ArchiveWriter.Cancelled 也是取消
        return baseBuilder()
            .setContentTitle(
                if (failed) getString(R.string.transfer_failed) else getString(R.string.transfer_done),
            )
            .setContentText(if (failed) r.exceptionOrNull()?.message.orEmpty() else s.destLabel)
            .setSmallIcon(if (s.isCompress) R.drawable.ic_compress else R.drawable.ic_copy)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, MainActivity.transferIntent(this),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Suppress("DEPRECATION")
    private fun baseBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)

    companion object {
        private const val CHANNEL = "twig_transfer"
        private const val NOTIF_ID = 4301
        private const val DONE_ID = 4302
        private const val ACTION_CANCEL = "com.twig.app.TRANSFER_CANCEL"

        fun start(ctx: Context) {
            val intent = Intent(ctx, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}

private typealias Session = Transfers.Session
