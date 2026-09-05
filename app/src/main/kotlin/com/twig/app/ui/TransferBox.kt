package com.twig.app.ui

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.databinding.DialogConflictBinding
import com.twig.app.databinding.DialogCopyProgressBinding
import com.twig.app.databinding.DialogProgressTitleBinding
import com.twig.core.CopyEngine
import com.twig.core.XFile

/**
 * Progress dialog shared by copy/move/compress: current file + two progress bars +
 * remaining directories/files count + speed/ETA.
 *
 * **Just an observer of a [Transfers] session**, not the owner of the job: closing it
 * ("Run in background" / the screen is destroyed) only removes the observer, the
 * transport continues via the foreground service; tapping the notification brings
 * it back and reattaches, refilling from the session snapshot in one go, no need to
 * wait for the next callback.
 *
 * Pulled out of `PaneFragment` because the directory-compare page also needs to
 * copy/sync — same session mechanism, same progress/conflict interactions, no
 * reason to write a second one. Host differences are funnelled through these callbacks:
 * @param alive whether the host UI is still alive (a Fragment's view can be destroyed
 *   before its session)
 * @param onBackground "Run in background" pressed; the host asks for notification
 *   permission and shows the persistent notification
 * @param onDetach dialog got unhooked; the host clears its own reference
 * @param onFinished transfer is done (host refreshes the list, surfaces the result)
 */
class TransferBox(
    private val ctx: Context,
    private val inflater: LayoutInflater,
    private val session: Transfers.Session,
    private val alive: () -> Boolean,
    private val onBackground: () -> Unit,
    private val onDetach: () -> Unit,
    private val onFinished: (Transfers.Session) -> Unit,
) : Transfers.Ui {

    private val pb = DialogCopyProgressBinding.inflate(inflater)
    // Custom title: "Run in background" needs to sit at the right end of this title row.
    private val tb = DialogProgressTitleBinding.inflate(inflater)
    private val dialog = AlertDialog.Builder(ctx)
        .setCustomTitle(tb.root)
        .setView(pb.root)
        .setCancelable(false)
        .setNegativeButton(R.string.dialog_cancel) { _, _ -> session.cancel() }
        .create()

    init {
        tb.tvTitle.setText(session.titleRes)
        pb.tvDest.text = session.destLabel
        pb.ivDest.setImageResource(session.destIcon)
        tb.btnBackground.setOnClickListener {
            detach()
            onBackground()
        }
        dialog.show()
        Transfers.attach(this) // On attach, re-fill progress once (including any conflict dialog already waiting).
    }

    /** Detach the observer and dismiss the dialog (the task itself is unaffected). */
    fun detach() {
        if (Transfers.ui === this) Transfers.attach(null)
        onDetach()
        runCatching { dialog.dismiss() }
    }

    override fun onProgress(s: Transfers.Session) {
        if (!alive()) return
        pb.tvFile.text =
            if (s.pendingConflict != null) ctx.getString(R.string.transfer_waiting) else s.fileName
        pb.barFile.progress = s.fileProgress
        pb.barTotal.progress = s.totalProgress
        val plan = s.plan
        pb.tvDirs.text = if (plan == null) "…" else s.dirsLeft.toString()
        pb.tvFiles.text = if (plan == null) "…" else s.filesLeft.toString()
        pb.tvTotalSize.text =
            if (plan == null) "…" else ctx.getString(R.string.copy_total_size, Format.size(plan.bytes))
        pb.tvSpeed.text =
            if (s.speed > 1) ctx.getString(R.string.copy_speed, Format.size(s.speed.toLong())) else ""
        pb.tvEta.text = if (s.etaSeconds >= 0) formatEta(s.etaSeconds) else ""
    }

    override fun onConflict(s: Transfers.Session, c: Transfers.Conflict) {
        if (!alive()) return
        showConflictDialog(ctx, inflater, c.src, c.existing) { d, all ->
            if (d != null && all) s.applyAll = d
            c.answer(d)
        }
    }

    override fun onFinished(s: Transfers.Session) {
        onDetach()
        runCatching { dialog.dismiss() }
        if (!alive()) return
        onFinished.invoke(s)
    }
}

/** Same-name conflict dialog: overwrite / skip / rename + apply to all; cancel aborts the transfer. */
fun showConflictDialog(
    ctx: Context,
    inflater: LayoutInflater,
    src: XFile,
    existing: XFile,
    onResult: (CopyEngine.Decision?, Boolean) -> Unit,
) {
    val cb = DialogConflictBinding.inflate(inflater)
    cb.tvName.text = src.name
    cb.tvDetail.text = ctx.getString(
        R.string.conflict_detail, Format.size(src.size), Format.size(existing.size),
    )
    AlertDialog.Builder(ctx)
        .setTitle(R.string.conflict_title)
        .setView(cb.root)
        .setCancelable(false)
        .setNegativeButton(R.string.dialog_cancel) { _, _ -> onResult(null, false) }
        .setPositiveButton(R.string.dialog_ok) { _, _ ->
            val d = when (cb.rgDecision.checkedRadioButtonId) {
                R.id.rb_skip -> CopyEngine.Decision.SKIP
                R.id.rb_rename -> CopyEngine.Decision.RENAME
                else -> CopyEngine.Decision.OVERWRITE
            }
            onResult(d, cb.cbAll.isChecked)
        }
        .show()
}

/** Remaining time: -mm:ss / -h:mm:ss. */
private fun formatEta(seconds: Double): String {
    val t = seconds.toLong().coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "-%d:%02d:%02d".format(h, m, s) else "-%d:%02d".format(m, s)
}
