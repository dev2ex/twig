package com.twig.app.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.twig.app.Format
import com.twig.app.OpenFiles
import com.twig.app.R
import com.twig.app.databinding.DialogInstallProgressBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.ZipFileSystem
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs a multi-apk bundle — `.xapk` (APKPure, and what [com.twig.app.XapkPack]
 * writes when a split app is copied out of the "Apps" tree), `.apks` (bundletool / SAI)
 * or `.apkm` (APKMirror). All three are a zip holding one app's base.apk plus its
 * `split_config.*` apks; they disagree about metadata (`manifest.json` / `toc.pb` /
 * `info.json`) and about which subdirectory the apks live in, and none of that matters
 * here — every `.apk` entry goes into the session, everything else is ignored.
 *
 * The system installer takes exactly **one** apk per intent, so `ACTION_VIEW` — the way
 * plain `.apk` files are opened — cannot install a bundle. [PackageInstaller] can: one
 * session, one `openWrite` per apk, one `commit`, and the system then shows its usual
 * confirmation screen. That is the whole reason this class exists.
 *
 * Two things worth knowing before changing it:
 * - **The bundle is read through [ZipFileSystem], not through `ZipInputStream`.**
 *   ★ This is not a preference. APKPure writes its apk entries **STORED with a data
 *   descriptor**, which leaves all three size fields in the local header zero, so a
 *   single-pass reader cannot tell where an uncompressed entry ends — the JDK refuses
 *   outright ("only DEFLATED entries can have EXT descriptor") and every such bundle
 *   failed to install. Sizes and offsets have to come from the **central directory**.
 *   Going through the project's own zip layer also keeps the "no local copy" property:
 *   it reads over a seekable channel, so a bundle on SMB/WebDAV only transfers the
 *   directory plus the apk bytes themselves (see `ArchiveFileSystem.rootOf`).
 * - **The status callback is a broadcast**, not a return value: `commit()` only queues
 *   the session, and the outcome (including "the user still has to confirm") arrives at
 *   [receiver]. It is registered once for the life of the process; unregistering per
 *   session would race with the user taking their time on the confirmation screen.
 *
 * Entries whose name does not end in `.apk` (`manifest.json`, `icon.png`, an `obb`
 * payload) are skipped — an obb still has to be placed by hand, we deliberately do not
 * write into `Android/obb` behind the user's back.
 */
object ApkBundleInstall {

    private const val ACTION_STATUS = "com.twig.app.INSTALL_STATUS"
    private const val BUF = 1 shl 20
    /** Don't post a progress update for every buffer — 10/s is already more than the eye resolves. */
    private const val UI_INTERVAL_MS = 100L

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-install").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** Cancelled by the user in the progress dialog; distinguished from a real failure so we stay quiet. */
    private class Cancelled : Exception()

    /**
     * Streams [file] into a fresh install session and commits it. Shows a progress dialog
     * ([ctx] must be an Activity context — the dialog and, on Android O+, the
     * "allow this source" prompt are attached to it).
     */
    fun start(ctx: Context, file: XFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            promptUnknownSources(ctx)
            return
        }
        val app = ctx.applicationContext
        val b = DialogInstallProgressBinding.inflate(LayoutInflater.from(ctx))
        val cancelled = AtomicBoolean(false)
        b.tvFile.text = file.name
        b.bar.max = 1000
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.install_title)
            .setView(b.root)
            .setCancelable(false)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> cancelled.set(true) }
            .create()
        dialog.show()

        var lastUi = 0L
        exec.execute {
            val r = runCatching {
                writeAndCommit(app, file, cancelled) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi < UI_INTERVAL_MS) return@writeAndCommit
                    lastUi = now
                    main.post {
                        if (!dialog.isShowing) return@post
                        if (total > 0) b.bar.progress = ((done * 1000) / total).toInt()
                        b.tvSize.text = "${Format.size(done)} / ${Format.size(total)}"
                    }
                }
            }
            main.post {
                runCatching { dialog.dismiss() }
                val e = r.exceptionOrNull()
                if (e != null && e !is Cancelled) {
                    toast(app, app.getString(R.string.install_failed, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /**
     * Reads the bundle's directory, then streams every apk in it into one session. On any
     * failure (including cancellation) the session is abandoned, so a half-written one is
     * never left behind for the system to garbage-collect later.
     */
    private fun writeAndCommit(
        ctx: Context,
        file: XFile,
        cancelled: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
    ) {
        val zip = FsRegistry.of(ZipFileSystem.SCHEME) as ArchiveFileSystem
        val apks = apkEntries(zip, zip.rootOf(source(ctx, file)))
        // Knowable before a byte is written, because the directory was read first.
        if (apks.isEmpty()) throw IllegalStateException(ctx.getString(R.string.install_no_apk))
        val total = apks.sumOf { it.size }
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        var committed = false
        val session = installer.openSession(sessionId)
        try {
            var done = 0L
            val used = HashSet<String>()
            for (apk in apks) {
                // Session file names are labels, not identity — the split name comes from
                // each apk's own manifest. They only have to be unique, and two
                // subdirectories of an .apks can hold the same base name.
                var name = apk.name
                while (!used.add(name)) name = "${used.size}_$name"
                session.openWrite(name, 0, apk.size.takeIf { it > 0 } ?: -1L).use { out ->
                    zip.openInput(apk).use { ins ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            if (cancelled.get()) throw Cancelled()
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                    session.fsync(out)
                }
            }
            session.commit(statusSender(ctx, sessionId))
            committed = true
        } finally {
            session.close()
            if (!committed) runCatching { installer.abandonSession(sessionId) }
        }
    }

    /**
     * What actually gets mounted. Same rule as `PaneViewModel.archiveTarget`: mount the
     * file where it lies — local, SMB, WebDAV, SFTP all seek well enough to read a
     * directory and a few slices — and only fall back to a local copy for a bundle
     * sitting *inside* another archive that cannot seek cheaply, where every seek would
     * otherwise re-inflate the outer stream.
     */
    private fun source(ctx: Context, file: XFile): XFile {
        val host = runCatching { FsRegistry.of(file) }.getOrNull()
        if (host !is ArchiveFileSystem || host.fastRandom(file)) return file
        val local = OpenFiles.materialize(ctx, file)
        return XFile(
            ArchiveFileSystem.HOST_SCHEME, local.absolutePath, isDir = false,
            size = local.length(), lastModified = local.lastModified(),
        )
    }

    /** Every apk in the bundle, wherever the format buried it (`.apks` puts them under `splits/`). */
    internal fun apkEntries(zip: ArchiveFileSystem, root: XFile): List<XFile> {
        val found = ArrayList<XFile>()
        val dirs = ArrayDeque<XFile>()
        dirs += root
        while (dirs.isNotEmpty()) {
            for (entry in zip.list(dirs.removeLast())) {
                if (entry.isDir) dirs += entry else if (installable(inner(entry))) found += entry
            }
        }
        return found
    }

    /** An entry's path inside the archive: XFile paths here are "<archive>!/<inner path>". */
    private fun inner(entry: XFile): String =
        entry.path.substringAfterLast(ArchiveFileSystem.SEP)

    /**
     * Whether this zip entry is one of the apks to install.
     *
     * ★ `standalones/` is skipped: a full `bundletool build-apks` archive carries both
     * `splits/` (one base + config splits, for Android 5+) and `standalones/` (whole
     * self-contained apks for older devices). Installing both hands the session two base
     * apks and the commit fails. Universal-mode output has no `standalones/` — its single
     * `universal.apk` sits at the root — so nothing legitimate is lost.
     */
    internal fun installable(path: String): Boolean =
        path.endsWith(".apk", ignoreCase = true) &&
            !path.startsWith("standalones/") &&
            !path.contains("/standalones/")

    /**
     * Where the session reports back. ★ [PendingIntent.FLAG_MUTABLE] is required from
     * API 31: the system fills the status extras into this intent, and an immutable one
     * would arrive empty.
     */
    private fun statusSender(ctx: Context, sessionId: Int): IntentSender {
        val app = ctx.applicationContext
        registerReceiver(app)
        val intent = Intent(ACTION_STATUS).setPackage(app.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(app, sessionId, intent, flags).intentSender
    }

    private var registered = false

    @Synchronized
    private fun registerReceiver(app: Context) {
        if (registered) return
        val filter = IntentFilter(ACTION_STATUS)
        // Only our own PendingIntent fires this; exporting it would let any app forge a status.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        registered = true
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                // Not an error: the session is written and the system now wants the user to
                // confirm. A BroadcastReceiver has no task of its own, hence NEW_TASK.
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = confirmIntent(intent) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { app.startActivity(confirm) }
                }
                PackageInstaller.STATUS_SUCCESS ->
                    toast(app, app.getString(R.string.install_done))
                // The user pressed cancel on the system's own confirmation screen; they know.
                PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    toast(app, app.getString(R.string.install_failed, msg))
                }
            }
        }
    }

    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /**
     * Android O+ gates installing on a per-app "install unknown apps" grant. The system
     * would otherwise silently refuse the commit, so ask up front and point at the
     * settings page for this package.
     */
    private fun promptUnknownSources(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.install_title)
            .setMessage(R.string.install_need_permission)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.install_settings) { _, _ ->
                val i = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                )
                if (runCatching { ctx.startActivity(i) }.isFailure) {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                }
            }
            .show()
    }

    private fun toast(ctx: Context, msg: String) =
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}
