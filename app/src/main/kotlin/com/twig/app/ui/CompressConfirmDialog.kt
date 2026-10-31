package com.twig.app.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.twig.app.Format
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.DialogCompressBinding
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveWriter
import kotlinx.coroutines.launch

/** Compression form fields remain in the pane's draft while choosing a different destination. */
class CompressConfirmDialog : DialogFragment() {
    private val pane get() = requireParentFragment() as PaneFragment
    private val state get() = pane.destinationState
    private var binding: DialogCompressBinding? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val draft = state.draft
        val options = draft?.compression
        // Sources and passwords are deliberately not restored after process death.
        if (draft?.destination == null || options == null) return AlertDialog.Builder(requireContext()).create()
        val form = DialogCompressBinding.inflate(layoutInflater)
        binding = form
        form.etName.setText(options.name)
        form.etPassword.setText(options.password)
        form.tvEncryptNote.visibility = if (options.password.isEmpty()) View.GONE else View.VISIBLE
        form.rgFormat.check(if (options.format == ArchiveWriter.Format.SEVEN_Z) R.id.rb_7z else R.id.rb_zip)
        form.cbMove.isChecked = draft.move
        form.cbMove.visibility = if (draft.movable) View.VISIBLE else View.GONE
        // The in-memory draft owns these values, including the password; never put them in a Bundle.
        for (view in listOf(form.etName, form.etPassword, form.rbZip, form.rb7z, form.cbMove)) {
            view.isSaveEnabled = false
        }
        form.cbMove.setOnCheckedChangeListener { _, checked -> draft.move = checked }
        form.etName.doAfterTextChanged {
            options.name = it.toString()
            updateDestination()
        }
        form.etPassword.doAfterTextChanged {
            options.password = it.toString()
            form.tvEncryptNote.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        form.rgFormat.setOnCheckedChangeListener { _, id ->
            options.format = if (id == R.id.rb_7z) ArchiveWriter.Format.SEVEN_Z else ArchiveWriter.Format.ZIP
            Prefs.setCompressFormat(requireContext(), options.format)
            val name = form.etName.text.toString()
            val extension = ArchiveWriter.Format.entries.firstOrNull { name.endsWith(".${it.ext}", ignoreCase = true) }
            val stem = if (extension == null) name else name.dropLast(extension.ext.length + 1)
            form.etName.setText("$stem.${options.format.ext}")
            form.etName.setSelection(stem.length)
        }
        val sourceLabel = if (draft.sources.size == 1) draft.sources.single().name
            else getString(R.string.copy_items, draft.sources.size)
        lifecycleScope.launch {
            state.plan.collect { plan ->
                binding?.tvSrc?.text = sourceLabel + (plan?.let { "  ${Format.size(it.bytes)}" } ?: "")
            }
        }
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.compress_title)
            .setView(form.root)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> state.clear() }
            .setNeutralButton(R.string.copy_choose_destination, null)
            .setPositiveButton(R.string.dialog_ok, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        val draft = state.draft
        val options = draft?.compression
        val form = binding
        if (draft?.destination == null || options == null || form == null) { dismiss(); return }
        val dialog = requireDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(form.root.windowToken, 0)
            pane.chooseDestination()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val destination = updateDestination() ?: return@setOnClickListener
            val name = options.name.trim()
            if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() }) {
                form.etName.error = getString(R.string.batch_invalid_name, name)
                return@setOnClickListener
            }
            val password = options.password
            val plan = state.plan.value
            state.clear()
            dismiss()
            pane.startCompress(draft.sources, destination, name, options.format, draft.movable && draft.move, plan, password)
        }
        updateDestination()
        form.etName.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        val name = form.etName.text.toString()
        form.etName.setSelection(0, name.lastIndexOf('.').takeIf { it > 0 } ?: name.length)
    }

    private fun updateDestination(): XFile? {
        val draft = state.draft ?: return null
        val destination = draft.destination ?: return null
        val form = binding ?: return null
        form.tvDest.text = pane.displayPath(destination)
        form.ivDest.setImageResource(FileIcons.sourceIconRes(destination.scheme))
        val reason = draft.blockReason(destination)
        form.tvDestError.text = reason?.let(::getString)
        form.tvDestError.visibility = if (reason == null) View.GONE else View.VISIBLE
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            reason == null && draft.compression?.name?.isNotBlank() == true
        return destination.takeIf { reason == null }
    }

    override fun onCancel(dialog: DialogInterface) {
        state.clear()
        super.onCancel(dialog)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

}
