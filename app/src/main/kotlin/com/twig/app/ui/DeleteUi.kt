package com.twig.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.TrashStore
import com.twig.core.CopyEngine
import com.twig.core.XFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One confirmation and execution path for manual deletion from every file viewer. */
object DeleteUi {
    internal fun confirmation(context: Context, names: List<String>): AlertDialog.Builder {
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.mutate()?.apply {
            setTint(ContextCompat.getColor(context, R.color.menu_icon_danger))
        }
        return AlertDialog.Builder(context)
            .setTitle(R.string.strip_delete)
            .setIcon(icon)
            .setMessage(if (names.size == 1) names.single() else context.getString(R.string.title_selected_count, names.size))
            .setNegativeButton(R.string.dialog_cancel, null)
    }

    fun confirm(
        context: Context, owner: LifecycleOwner, files: List<XFile>,
        onDismiss: (() -> Unit)? = null, skipTrashConfirmation: Boolean = false, onFinished: (Boolean) -> Unit,
    ) = confirm(context, owner.lifecycleScope, files, onDismiss, skipTrashConfirmation, onFinished)

    fun confirm(
        context: Context, scope: CoroutineScope, files: List<XFile>,
        onDismiss: (() -> Unit)? = null, skipTrashConfirmation: Boolean = false, onFinished: (Boolean) -> Unit,
    ) {
        val app = context.applicationContext
        scope.launch {
            val store = TrashStore(app)
            val planned = withContext(Dispatchers.IO) { runCatching { store.plan(files) } }
            val plan = planned.getOrElse {
                Toast.makeText(context, it.message ?: context.getString(R.string.err_failed), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (plan.isEmpty()) return@launch
            if (context is LifecycleOwner && !context.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                return@launch
            val enabled = Prefs.trashEnabled(app)
            if (skipTrashConfirmation && enabled && plan.all { it.volume != null }) {
                runConfirmed(context, scope, Transfers.Work.Delete(plan, true), onFinished)
                return@launch
            }
            val useTrash = if (enabled && plan.any { it.volume != null }) CheckBox(context).apply {
                id = android.R.id.checkbox
                text = context.getString(R.string.trash_use)
                isChecked = true
            } else null
            val content = useTrash?.let { checkbox ->
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                    val dp = resources.displayMetrics.density
                    val pad = (24 * dp).toInt()
                    setPadding(pad, (8 * dp).toInt(), pad, 0)
                    addView(checkbox)
                }
            }
            confirmation(context, plan.map { it.file.name })
                .setView(content)
                .setOnDismissListener { onDismiss?.invoke() }
                .setPositiveButton(R.string.strip_delete) { _, _ ->
                    val recycle = useTrash?.isChecked == true
                    runConfirmed(context, scope, Transfers.Work.Delete(plan, recycle), onFinished)
                }.show()
        }
    }

    /** Start only after a deletion or purge confirmation; execution owns no screen references. */
    internal fun runConfirmed(
        context: Context, scope: CoroutineScope, work: Transfers.Work, onFinished: (Boolean) -> Unit,
    ) {
        require(work is Transfers.Work.Delete || work is Transfers.Work.Purge)
        if (work.items.isEmpty()) return
        val app = context.applicationContext
        val session = Transfers.Session(
            work, R.string.strip_delete,
            context.getString(R.string.title_selected_count, work.items.size), R.drawable.ic_delete,
            CopyEngine.Plan(0, work.items.count { !it.isDir }, work.items.count { it.isDir }),
        )
        val started = runCatching { Transfers.start(app, session) }.getOrElse {
            Toast.makeText(context, it.message ?: context.getString(R.string.err_failed), Toast.LENGTH_LONG).show()
            return
        }
        if (!started) {
            Toast.makeText(context, R.string.transfer_busy, Toast.LENGTH_SHORT).show()
            return
        }
        // Only observation belongs to the host's scope. The service owns execution.
        scope.launch {
            val box = TransferBox(
                context, LayoutInflater.from(context), session,
                alive = { true },
                onBackground = {
                    val activity = context as? android.app.Activity
                    if (android.os.Build.VERSION.SDK_INT >= 33 && activity != null &&
                        activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) runCatching { activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
                    Toast.makeText(context, R.string.transfer_background_hint, Toast.LENGTH_SHORT).show()
                },
                onDetach = {}, onFinished = {},
            )
            try {
                session.completion.await()
                // Refresh even after cancellation or a partial batch failure.
                onFinished(session.finished?.isSuccess == true && !session.cancelled.get())
            } finally { box.detach() }
        }
    }
}
