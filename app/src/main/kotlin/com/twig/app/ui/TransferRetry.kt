package com.twig.app.ui

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.twig.app.R

/** The failed operation itself offers retry; dismissing it releases its partial files. */
internal fun showTransferRetry(
    ctx: Context,
    session: Transfers.Session,
    onStarted: () -> Unit,
    onClosed: () -> Unit = {},
): Boolean {
    if (session.cancelled.get() || session.finished?.isFailure != true ||
        (session.work !is Transfers.Work.Copy && session.work !is Transfers.Work.Sync)
    ) return false
    var retried = false
    val failed = session.checkpoint.failure
    val target = failed?.let {
        val directory = if (it.directory.scheme == "saf") it.directory.path
            else com.twig.app.Format.pathLabel(it.directory)
        if (it.directory.scheme == "saf") it.targetName + "\n" + directory
        else directory.trimEnd('/') + "/" + it.targetName.trimStart('/')
    }
    val dialog = AlertDialog.Builder(ctx)
        .setTitle(R.string.transfer_failed)
        .setMessage(listOf(target?.let { ctx.getString(R.string.transfer_failed_target, it) }.orEmpty(),
            session.finished?.exceptionOrNull()?.message.orEmpty(),
            ctx.getString(R.string.transfer_retry_detail)).filter { it.isNotEmpty() }.joinToString("\n\n"))
        .setPositiveButton(R.string.transfer_retry, null)
        .setNegativeButton(R.string.dialog_cancel, null)
        .apply { if (failed != null) setNeutralButton(R.string.transfer_skip_failed, null) }
        .create()
    dialog.setCanceledOnTouchOutside(false)
    dialog.setOnDismissListener {
        if (!retried) {
            Thread({ Transfers.discardPartials(session) }, "twig-copy-cleanup").start()
            onClosed()
        }
    }
    dialog.setOnShowListener {
        fun continueTransfer(skipFailed: Boolean) {
            runCatching { Transfers.start(ctx.applicationContext, Transfers.retrySession(session, skipFailed)) }
                .onSuccess { started ->
                    if (started) {
                        retried = true
                        dialog.dismiss()
                        onStarted()
                    } else Toast.makeText(ctx, R.string.transfer_busy, Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { continueTransfer(false) }
        if (failed != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { continueTransfer(true) }
    }
    dialog.show()
    return true
}
