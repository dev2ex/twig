package com.twig.app.ui

import android.app.Activity
import android.content.Context
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.twig.app.Format
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isWritableDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Shared foreground Save as flow for bounded range readers and streaming producers. */
internal class SaveAsDialog private constructor(caller: ActivityResultCaller,
    private val getContext: () -> Context, private val getScope: () -> CoroutineScope,
    private val isAlive: () -> Boolean) {
    constructor(fragment: Fragment) : this(fragment, { fragment.requireContext() },
        { fragment.viewLifecycleOwner.lifecycleScope }, { fragment.view != null })
    constructor(activity: AppCompatActivity) : this(activity, { activity },
        { activity.lifecycleScope }, { !activity.isFinishing && !activity.isDestroyed })

    data class Saved(val file: XFile, val directory: XFile)
    private data class Draft(val name: String, val size: Long?, val write: suspend (OutputStream, (Long) -> Unit) -> Unit,
        val onSaved: (Saved) -> Unit)
    private var pending: Draft? = null
    private var prompt: AlertDialog? = null
    private var progress: AlertDialog? = null
    private var saving: Job? = null
    private val picker = caller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val draft = pending ?: return@registerForActivityResult
        pending = null
        if (result.resultCode != Activity.RESULT_OK || !isAlive()) return@registerForActivityResult
        val data = result.data
        val scheme = data?.getStringExtra(PickerActivity.EXTRA_PICKED_SCHEME) ?: return@registerForActivityResult
        val path = data.getStringExtra(PickerActivity.EXTRA_PICKED_PATH) ?: return@registerForActivityResult
        save(draft, XFile(scheme, path, true, displayName = data.getStringExtra(PickerActivity.EXTRA_PICKED_NAME)))
    }

    val isSaving get() = saving?.isCompleted == false

    fun offerBytes(name: String, bytes: ByteArray, onSaved: (Saved) -> Unit = {}) =
        offer(name, bytes.size.toLong(), { offset, count ->
            bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }, onSaved)

    fun offer(name: String, size: Long, read: suspend (Long, Int) -> ByteArray, onSaved: (Saved) -> Unit = {}) {
        require(size >= 0)
        val failure = getContext().getString(R.string.transfer_failed)
        offerStream(name, size, { output, progress -> copy(size, read, output, progress, failure) }, onSaved)
    }

    fun offerStream(name: String, size: Long? = null,
        write: suspend (OutputStream, (Long) -> Unit) -> Unit, onSaved: (Saved) -> Unit = {}) {
        val context = getContext()
        if (saving?.isCompleted == false) {
            Toast.makeText(context, R.string.transfer_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(context).apply {
            setSingleLine()
            hint = context.getString(R.string.hint_name)
            setText(suggestedName(name))
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.save_as).setView(input)
            .setNegativeButton(R.string.dialog_cancel, null).setPositiveButton(R.string.dialog_ok, null).create()
        prompt = dialog
        dialog.setOnDismissListener { if (prompt === dialog) prompt = null }
        dialog.showNameInput(input, selectStem = true)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (!validName(name)) {
                input.error = context.getString(R.string.batch_invalid_name, name)
            } else {
                pending = Draft(name, size, write, onSaved)
                dialog.dismiss()
                picker.launch(PickerActivity.dirIntent(context, context.getString(R.string.save_as)))
            }
        }
    }

    private fun save(draft: Draft, directory: XFile) {
        val context = getContext()
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val status = TextView(context).apply {
            setText(R.string.viewer_saving)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.save_as).setView(status)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> saving?.cancel() }.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { saving?.cancel() }
        progress = dialog
        dialog.show()
        saving = getScope().launch {
            try {
                val target = writeStream(context, directory, draft.name) { output ->
                    draft.write(output) { done ->
                        status.post { status.text = draft.size?.let { "${Format.size(done)} / ${Format.size(it)}" }
                            ?: Format.size(done) }
                    }
                }
                Toast.makeText(context, R.string.transfer_done, Toast.LENGTH_SHORT).show()
                draft.onSaved(Saved(target, directory))
            } catch (e: CancellationException) {
                if (isActive) Toast.makeText(context, R.string.transfer_failed, Toast.LENGTH_LONG).show()
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: context.getString(R.string.transfer_failed), Toast.LENGTH_LONG).show()
            } finally {
                dialog.dismiss()
                if (progress === dialog) progress = null
            }
        }
    }

    fun destroy() {
        pending = null
        prompt?.dismiss(); prompt = null
        saving?.cancel(); saving = null
        progress?.dismiss(); progress = null
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        fun suggestedName(title: String): String = title.map {
            if (it.code < 32 || it.code == 127 || it in "/\\:*?\"<>|") '_' else it
        }.joinToString("").trim().trimEnd('.').ifEmpty { "file" }

        fun validName(name: String) = name.isNotBlank() && name !in setOf(".", "..") &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

        /** Stream a new file; failed listings never grant permission to overwrite, and failed writes are removed. */
        suspend fun write(context: Context, directory: XFile, name: String, size: Long,
            read: suspend (Long, Int) -> ByteArray, progress: (Long) -> Unit = {}): XFile {
            require(size >= 0)
            return writeStream(context, directory, name) { output -> copy(size, read, output, progress, context.getString(R.string.transfer_failed)) }
        }

        private suspend fun copy(size: Long, read: suspend (Long, Int) -> ByteArray,
            output: OutputStream, progress: (Long) -> Unit, failure: String) {
            var offset = 0L
            while (offset < size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(BUFFER_SIZE.toLong(), size - offset).toInt()
                val bytes = read(offset, count)
                check(bytes.size == count) { failure }
                currentCoroutineContext().ensureActive()
                output.write(bytes)
                offset += bytes.size
                progress(offset)
            }
        }

        suspend fun writeStream(context: Context, directory: XFile, name: String,
            write: suspend (OutputStream) -> Unit): XFile = withContext(Dispatchers.IO) {
            require(validName(name))
            currentCoroutineContext().ensureActive()
            require(directory.isWritableDir()) { context.getString(R.string.msg_pick_dir_first) }
            val fs = FsRegistry.of(directory)
            check(fs.list(directory).none { it.name.equals(name, ignoreCase = true) }) {
                context.getString(R.string.new_text_exists, name)
            }
            currentCoroutineContext().ensureActive()
            val target = fs.createFile(directory, name)
            try {
                fs.openOutput(target).use { output ->
                    write(output)
                    currentCoroutineContext().ensureActive()
                }
                target
            } catch (error: Throwable) {
                withContext(NonCancellable) { runCatching { fs.delete(target) } }
                throw error
            }
        }
    }
}
