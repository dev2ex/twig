package com.twig.app.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.databinding.DialogCopyConfirmBinding
import com.twig.core.XFile
import kotlinx.coroutines.launch

/** A pane-owned, in-memory draft survives rotation while choosing a destination. */
class CopyConfirmDialog : DialogFragment() {
    private val pane get() = requireParentFragment() as PaneFragment
    private val state get() = pane.destinationState
    private var binding: DialogCopyConfirmBinding? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val draft = state.draft
        // Pending sources depend on live filesystem registrations. After process death,
        // discard the draft rather than reconstructing or operating on another selection.
        if (draft == null) return AlertDialog.Builder(requireContext()).create()
        val form = DialogCopyConfirmBinding.inflate(layoutInflater)
        binding = form
        form.cbSourceDirectory.isSaveEnabled = false
        form.cbSourceDirectory.visibility = if (draft.extraction == null) View.GONE else View.VISIBLE
        form.cbSourceDirectory.isChecked = draft.extraction?.saveToSource == true
        form.cbSourceDirectory.isEnabled = draft.extraction?.sourceDirectory?.let { draft.blockReason(it) == null } == true
        form.cbSourceDirectory.setOnCheckedChangeListener { _, checked ->
            draft.extraction?.saveToSource = checked
            updateDestination()
        }
        form.cbKeepStructure.isSaveEnabled = false
        form.cbKeepStructure.isChecked = draft.keepStructure
        form.cbKeepStructure.visibility = if (draft.extraction == null) View.VISIBLE else View.GONE
        form.tvDestinationLabel.setText(if (draft.extraction == null) R.string.copy_to else R.string.extract_to)
        form.tvStructurePreview.text = draft.preview()
        form.cbKeepStructure.setOnCheckedChangeListener { _, checked ->
            draft.keepStructure = checked
            form.tvStructurePreview.text = draft.preview()
            updateDestination()
        }
        form.tvSrc.text = draft.extraction?.archive?.name ?: if (draft.sources.size == 1) draft.sources.single().name
            else getString(R.string.copy_items, draft.sources.size)
        form.cbMove.isChecked = draft.move
        form.cbMove.visibility = if (draft.movable) View.VISIBLE else View.GONE
        form.cbMove.setOnCheckedChangeListener { _, checked -> draft.move = checked }
        lifecycleScope.launch {
            state.plan.collect { binding?.tvSize?.text = it?.let { Format.size(it.bytes) } ?: "…" }
        }
        return AlertDialog.Builder(requireContext())
            .setTitle(if (draft.extraction == null) R.string.copy_title else R.string.extract_to)
            .setView(form.root)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> state.clear() }
            .setPositiveButton(R.string.dialog_ok, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        if (state.draft == null) { dismiss(); return }
        val form = binding ?: return
        val dialog = requireDialog() as AlertDialog
        form.destinationPicker.root.setOnClickListener {
            pane.chooseDestination()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val draft = state.draft ?: return@setOnClickListener
            // Recheck on confirmation; the connection may have disappeared in the picker.
            val destination = updateDestination() ?: return@setOnClickListener
            val plan = state.plan.value
            state.clear()
            dismiss()
            pane.startTransfer(draft.sources, destination, draft.movable && draft.move, plan,
                targetPaths = draft.outputPaths(), extraction = draft.extraction != null,
                destinationFolder = draft.extraction?.folderName, streamingTar = draft.extraction?.streamingTar)
        }
        updateDestination()
    }

    internal fun updateDestination(): XFile? {
        val form = binding ?: return null
        val draft = state.draft ?: return null
        val destination = draft.effectiveDestination()
        form.destinationPicker.tvDest.text = destination?.let(pane::displayPath) ?: getString(R.string.compress_destination_missing)
        form.destinationPicker.ivDest.setImageResource(destination?.let { FileIcons.sourceIconRes(it.scheme) } ?: R.drawable.ic_folder)
        form.destinationPicker.root.contentDescription = getString(R.string.copy_choose_destination) + ": " + form.destinationPicker.tvDest.text
        val reason = destination?.let(draft::blockReason)
        form.tvDestError.text = reason?.let(::getString)
        form.tvDestError.visibility = if (reason == null) View.GONE else View.VISIBLE
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            destination != null && reason == null
        return destination?.takeIf { reason == null }
    }

    override fun onCancel(dialog: DialogInterface) {
        state.clear()
        super.onCancel(dialog)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = DestinationState.CONFIRMATION_TAG
    }
}
