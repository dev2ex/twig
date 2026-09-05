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
 * Session state for background transfers (copy/move/compress), in-process singleton.
 *
 * Why this layer exists: the progress dialog used to own the whole job itself — engine
 * callbacks wrote straight to its View, and the job's lifecycle was tied to
 * `viewLifecycleOwner.lifecycleScope`. So closing the dialog meant the job lost its
 * footing, and with no foreground notification the process could be reclaimed by the
 * system at any moment while the user had switched away (copying large files to a
 * network drive takes a particularly long time).
 *
 * Now: **the job belongs to the session, the progress dialog is just one of its
 * observers**. The session is run by the [TransferService] foreground service (the
 * notification has a progress bar, so the process won't be killed), and the UI can
 * attach ([ui]) or detach at any time (the "run in background" button / UI destroyed);
 * detached, the job keeps running regardless.
 *
 * Only one session runs at a time: the progress dialog was originally a `setCancelable(false)`
 * modal, so launching a second one concurrently was never possible; being able to send
 * the first one to the background creates that possibility for the first time, and we
 * block it here explicitly (see the return value of [start]).
 */
object Transfers {

    /** What one transfer has to do. **Pure data** — the session outlives the screen that started it, so it can't capture a Fragment. */
    sealed class Work {
        abstract val items: List<XFile>

        class Copy(
            override val items: List<XFile>,
            val dest: XFile,
            val move: Boolean,
            val fromClipboard: Boolean,
        ) : Work()

        /**
         * Copy/sync from the directory-compare screen: **each item has its own destination**
         * (the source's position in the tree decides which subdirectory on the other side
         * it should land in), not a single shared `dest` like [Copy]. The caller guarantees
         * the destination directory exists (the compare screen builds them level by level
         * when constructing pairs; if it can't, we never get here).
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
            /** Non-null means encrypted (AES-256); null means the dialog was left blank. */
            val password: String? = null,
        ) : Work()
    }

    /**
     * A single name-collision Q&A: the transfer thread blocks on [await], the UI side
     * releases it with [answer]. If the UI isn't attached, we just keep waiting — the
     * notification switches to "waiting for confirmation…", and tapping it brings the
     * dialog back up.
     */
    class Conflict(val src: XFile, val existing: XFile) {
        private val slot = ArrayBlockingQueue<Array<CopyEngine.Decision?>>(1)
        fun await(): CopyEngine.Decision? = slot.take()[0]
        fun answer(d: CopyEngine.Decision?) { slot.offer(arrayOf(d)) }
    }

    /** UI observer; all callbacks on the main thread. */
    interface Ui {
        fun onProgress(s: Session)
        fun onConflict(s: Session, c: Conflict)
        fun onFinished(s: Session)
    }

    /**
     * Full state of one transfer. The progress fields are written by the transfer thread
     * and read by the main thread (@Volatile is enough: each field is independent, reading
     * a slightly stale value just means one fewer frame redraw). When the UI attaches,
     * it just renders the snapshot — no need to wait for the next callback.
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
        @Volatile var speed = 0.0 // B/s, exponential moving average
        @Volatile var etaSeconds = -1.0 // <0 = not yet computable
        @Volatile var pendingConflict: Conflict? = null
        @Volatile var finished: Result<Unit>? = null

        /** Notification-bar refresh hook (installed by [TransferService]); even moments without a progress event (e.g. waiting on a conflict) need to refresh. */
        @Volatile internal var tick: (() -> Unit)? = null

        val cancelled = AtomicBoolean(false)

        /** "Apply to all" remembered choice (only when the conflict dialog's checkbox is ticked). */
        @Volatile var applyAll: CopyEngine.Decision? = null

        fun cancel() {
            cancelled.set(true)
            pendingConflict?.answer(null) // If we're stuck on the conflict dialog, release it so the transfer thread sees the cancel flag
        }

        val isCompress: Boolean get() = work is Work.Compress
    }

    @Volatile var active: Session? = null
        private set

    /** A session that's finished but hasn't been consumed by the UI yet (it finished while we were in the background); see [consumeFinished]. */
    @Volatile private var pendingResult: Session? = null

    @Volatile var ui: Ui? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    /**
     * Start a session and bring up the foreground service. Returns false if a session is
     * already running (caller shows a toast).
     */
    @Synchronized
    fun start(ctx: Context, session: Session): Boolean {
        if (active != null) return false
        active = session
        try {
            TransferService.start(ctx)
        } catch (e: Exception) {
            active = null // Roll back if the service can't come up; otherwise `active` sticks and nothing can ever be transferred again
            throw e
        }
        return true
    }

    /**
     * UI attaches/detaches. On attach, if there's a pending conflict, fire one callback
     * immediately — otherwise the job stays stuck and the user, just tapping back from
     * the notification, sees a frozen progress bar.
     */
    fun attach(u: Ui?) {
        ui = u
        val s = active ?: return
        if (u == null) return
        u.onProgress(s)
        s.pendingConflict?.let { u.onConflict(s, it) }
    }

    /** Take the "finished but UI not on stage" session; [MainActivity] wraps this up (refresh pane + toast) when it returns to the foreground. */
    @Synchronized
    fun consumeFinished(): Session? = pendingResult.also { pendingResult = null }

    // ---- The transfer thread in TransferService calls the following ----

    // ★ Don't name this method `run`: in an anonymous inner class, `run { }` resolves to
    // the stdlib scope function and silently bypasses this one (same trap as renaming
    // NativeSmbClient.exec, see CLAUDE.md)
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
                if (now - lastUi < 200) return // Throttle to ~5 Hz: enough for speed averaging and UI refresh
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
                    // Group by destination and move one group at a time. Each group reports
                    // its progress starting from 0; the `base` offset stitches them into a
                    // continuous line — otherwise the bar jumps back every time we switch
                    // directories.
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
                    // Only touch the source files on a successful pack; on cancel/failure
                    // the partial output is already gone, so the sources must stay.
                    if (w.move) w.items.forEach { FsRegistry.of(it).delete(it) }
                }
            }
        }.map { }

        s.finished = result
        finish(s)
    }

    /** Conflict decision: transfer thread blocks; the UI (if attached) answers via dialog. */
    private fun resolverFor(s: Session) = CopyEngine.ConflictResolver { src, existing ->
        s.applyAll ?: run {
            val c = Conflict(src, existing)
            s.pendingConflict = c
            post(s) // Notification switches to "waiting for confirmation…" — nothing else comes in to trigger a refresh
            main.post { ui?.onConflict(s, c) }
            val d = c.await()
            s.pendingConflict = null
            if (d == null) s.cancelled.set(true)
            d
        }
    }

    private fun post(s: Session) {
        s.tick?.invoke() // Notification bar (the service throttles this itself)
        main.post { if (active === s) ui?.onProgress(s) }
    }

    private fun finish(s: Session) {
        active = null
        main.post {
            val u = ui
            if (u != null) {
                if (active == null) ui = null // A new session has already attached; don't steal its observer
                u.onFinished(s)
            } else {
                // UI not on stage: keep the result, let MainActivity refresh the pane and toast when it returns to the foreground
                pendingResult = s
            }
        }
    }
}

/**
 * Foreground service that runs the transfer session.
 *
 * Just spinning up a thread isn't enough: copying a large directory (especially to slow
 * sources like SMB/SFTP) can take dozens of minutes, and during that time the user will
 * switch away from the app — without a foreground notification the process can be killed
 * at any moment, and the copy breaks halfway through. The notification's progress bar is
 * also the only entry point after sending the task to the background: tapping it returns
 * to [MainActivity] and re-pops the progress dialog (see [MainActivity.transferIntent]).
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
                    NotificationManager.IMPORTANCE_LOW, // A progress bar shouldn't sound/peek
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
     * Refresh the notification when progress changes — once per second is enough; any
     * more just wakes the system UI for nothing. The only thing we don't throttle is
     * "waiting for the user to resolve a conflict": after that, no further progress
     * events will come in to trigger a refresh.
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
     * Completion/failure wrap-up notification; if the UI is still on stage it toasts
     * itself, no need to bother again. **No notification on cancel**: that's the user
     * pressing it themselves, they already know the result, and another ping is just noise.
     */
    private fun doneNotification(s: Session): Notification? {
        if (Transfers.ui != null) return null
        if (s.cancelled.get()) return null
        val r = s.finished ?: return null
        val failed = r.isFailure && r.exceptionOrNull() !is ArchiveWriter.Cancelled
        if (r.isFailure && !failed) return null // ArchiveWriter.Cancelled also counts as cancel
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
