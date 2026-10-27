package com.twig.app.ui

import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.twig.app.R
import com.twig.app.TrashFileSystem
import com.twig.app.secure.Secrets
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Management actions stay in the pane; no second screen or navigation stack. */
class TrashDialogs(private val ctx: Context, private val owner: LifecycleOwner, private val changed: () -> Unit) {
    private val fs get() = FsRegistry.of("trash") as TrashFileSystem
    private val store get() = fs.store
    private fun active() = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !Secrets.locked(ctx)
    private fun AlertDialog.track(): AlertDialog {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { dismiss() }
        }
        owner.lifecycle.addObserver(observer)
        setOnDismissListener { owner.lifecycle.removeObserver(observer) }
        return this
    }
    private fun error(e: Throwable) = Toast.makeText(ctx, e.message ?: ctx.getString(R.string.err_failed), Toast.LENGTH_LONG).show()
    fun show(file: XFile, extraActions: List<MenuAct> = emptyList()) {
        if (file.path == "/") {
            val actions = ArrayList<MenuAct>()
            actions += extraActions
            actions.item(ctx.getString(R.string.action_refresh), R.drawable.ic_refresh) { changed() }
            actions.item(ctx.getString(R.string.trash_empty), R.drawable.ic_delete, DANGER) { confirmEmpty() }
            AlertDialog.Builder(ctx).setTitle(R.string.trash_title)
                .setAdapter(menuAdapter(ctx, actions)) { _, which -> actions[which].run() }.show().track()
        } else {
            owner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { fs.item(file) } }
                if (!active()) return@launch
                result.fold(onSuccess = { item ->
                    val actions = ArrayList<MenuAct>()
                    if (item.entry.original != null) actions.item(ctx.getString(R.string.trash_restore), R.drawable.ic_up) {
                        restoreName(item, file)
                    }
                    actions += extraActions
                    actions.item(ctx.getString(R.string.trash_delete_permanently), R.drawable.ic_delete, DANGER) {
                        DeleteUi.confirmation(ctx, listOf(file.name))
                            .setPositiveButton(R.string.strip_delete) { _, _ ->
                                DeleteUi.runConfirmed(ctx, owner.lifecycleScope, Transfers.Work.Purge(listOf(file to item))) { changed() }
                            }
                            .show().track()
                    }
                    AlertDialog.Builder(ctx).setTitle(file.name)
                        .setAdapter(menuAdapter(ctx, actions)) { _, which -> actions[which].run() }.show().track()
                }, onFailure = ::error)
            }
        }
    }
    /** Remove descendants covered by a selected directory before taking the confirmed snapshot. */
    private suspend fun selectedItems(files: List<XFile>): List<Pair<XFile, TrashFileSystem.Item>> = withContext(Dispatchers.IO) {
        val selected = files.distinctBy { it.path }.filter { file ->
            file.scheme == "trash" && file.path != "/" && files.none { parent ->
                parent.scheme == file.scheme && parent.isDir && file.path.startsWith(parent.path.trimEnd('/') + "/")
            }
        }
        selected.map { it to fs.item(it) }
    }

    fun purgeSelected(files: List<XFile>) {
        owner.lifecycleScope.launch {
            val result = runCatching { selectedItems(files) }
            if (!active()) return@launch
            result.fold(onSuccess = { items ->
                if (items.isEmpty()) return@fold
                DeleteUi.confirmation(ctx, items.map { it.first.name })
                    .setPositiveButton(R.string.trash_delete_permanently) { _, _ ->
                        DeleteUi.runConfirmed(ctx, owner.lifecycleScope, Transfers.Work.Purge(items)) { changed() }
                    }.show().track()
            }, onFailure = ::error)
        }
    }

    fun restoreSelected(files: List<XFile>) {
        owner.lifecycleScope.launch {
            val result = runCatching {
                val items = selectedItems(files)
                withContext(Dispatchers.IO) {
                    // Damaged records fail before any source is moved.
                    val parents = items.map { (_, item) -> File(store.originalPath(item.entry, item.relative)).parentFile!! }
                    items to parents.filterNot { it.isDirectory }.distinctBy { it.path }
                }
            }
            if (!active()) return@launch
            result.fold(onSuccess = { (items, missing) ->
                if (items.isEmpty()) return@fold
                val message = if (missing.isEmpty()) ctx.getString(R.string.title_selected_count, items.size)
                    else ctx.getString(R.string.trash_restore_parent, missing.joinToString("\n") { it.path })
                AlertDialog.Builder(ctx).setTitle(R.string.trash_restore).setMessage(message)
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .setPositiveButton(R.string.trash_restore) { _, _ -> work {
                        val failures = items.mapNotNull { (file, item) ->
                            runCatching { store.restoreItem(item.entry, item.relative, file.name, missing.isNotEmpty()) }
                                .exceptionOrNull()?.let { "${file.name}: ${it.message}" }
                        }
                        if (failures.isNotEmpty()) throw java.io.IOException(failures.joinToString("\n"))
                    } }.show().track()
            }, onFailure = ::error)
        }
    }

    private fun restoreName(item: TrashFileSystem.Item, file: XFile) {
        val input = EditText(ctx).apply { setSingleLine(); setText(file.name) }
        AlertDialog.Builder(ctx).setTitle(R.string.trash_restore_name).setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.trash_restore) { _, _ ->
                val name = input.text.toString()
                owner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val parent = File(store.originalPath(item.entry, item.relative)).parentFile!!
                        parent.takeUnless { it.isDirectory }
                    } }
                    if (!active()) return@launch
                    result.fold(onSuccess = { missingParent ->
                        if (missingParent == null) work { store.restoreItem(item.entry, item.relative, name) }
                        else AlertDialog.Builder(ctx).setMessage(ctx.getString(R.string.trash_restore_parent, missingParent.path))
                            .setNegativeButton(R.string.dialog_cancel, null)
                            .setPositiveButton(R.string.trash_restore) { _, _ -> work { store.restoreItem(item.entry, item.relative, name, true) } }
                            .show().track()
                    }, onFailure = ::error)
                }
            }.create().showNameInput(input, selectStem = !file.isDir).track()
    }
    private fun confirmEmpty() {
        owner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fs.list(fs.root()).map { it to fs.item(it) } } }
            if (!active()) return@launch
            result.fold(onSuccess = { entries ->
                if (entries.isEmpty()) return@fold
                DeleteUi.confirmation(ctx, entries.map { it.first.name })
                    .setPositiveButton(R.string.trash_empty) { _, _ ->
                        DeleteUi.runConfirmed(ctx, owner.lifecycleScope, Transfers.Work.Purge(entries)) { changed() }
                    }.show().track()
            }, onFailure = ::error)
        }
    }
    private fun work(block: () -> Unit) {
        if (!active()) return
        OperationFeedback.clear(ctx)
        val progress = AlertDialog.Builder(ctx).setMessage(R.string.trash_busy).setCancelable(false).show().track()
        owner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching(block) }
                val message = result.exceptionOrNull()?.message ?: ctx.getString(
                    if (result.isSuccess) R.string.msg_done else R.string.err_failed,
                )
                OperationFeedback.finished(ctx, ctx.getString(R.string.trash_title), message)
            } finally { progress.dismiss(); changed() }
        }
    }
}
