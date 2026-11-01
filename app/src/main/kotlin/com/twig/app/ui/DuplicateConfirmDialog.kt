package com.twig.app.ui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import com.twig.core.XFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Edit each copy's suggested name; selected originals are never renamed by this dialog. */
internal object DuplicateConfirmDialog {
    fun show(context: Context, owner: LifecycleOwner, pairs: List<Pair<XFile, XFile>>,
             suggested: Map<String, String>, onConfirmed: (Map<String, String>) -> Unit) {
        val names = suggested.toMutableMap()
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun sourceLabel() = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 14f
        }
        fun nameInput() = EditText(context).apply {
            setSingleLine()
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        var singleInput: EditText? = null
        if (pairs.size == 1) {
            val file = pairs.single().first
            form.addView(sourceLabel().apply { text = file.name })
            singleInput = nameInput().apply {
                setText(names.getValue(file.toUri()))
                contentDescription = file.name
                doAfterTextChanged { names[file.toUri()] = it.toString() }
            }
            form.addView(singleInput)
        } else {
            class Holder(val row: LinearLayout, val label: TextView, val input: EditText) : RecyclerView.ViewHolder(row) {
                var index = 0
            }
            val list = object : RecyclerView(context) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    val available = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) dp(400)
                        else minOf(dp(400), MeasureSpec.getSize(heightSpec))
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
                }
            }.apply {
                layoutManager = LinearLayoutManager(context)
                itemAnimator = null
                adapter = object : RecyclerView.Adapter<Holder>() {
                    override fun getItemCount() = pairs.size
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = RecyclerView.LayoutParams(-1, -2)
                            setPadding(0, dp(4), 0, dp(4))
                        }
                        val label = sourceLabel()
                        val input = nameInput()
                        row.addView(label); row.addView(input)
                        return Holder(row, label, input).also { holder ->
                            input.doAfterTextChanged { names[pairs[holder.index].first.toUri()] = it.toString() }
                        }
                    }
                    override fun onBindViewHolder(holder: Holder, position: Int) {
                        holder.index = position
                        val file = pairs[position].first
                        holder.label.text = file.name
                        holder.input.contentDescription = file.name
                        holder.input.setText(names.getValue(file.toUri()))
                    }
                }
            }
            form.addView(list)
        }
        val error = sourceLabel().apply {
            visibility = View.GONE
            setTextColor(ContextCompat.getColor(context, R.color.menu_icon_danger))
        }
        form.addView(error)
        val dialog = AlertDialog.Builder(context).setTitle(R.string.strip_duplicate).setView(form)
            .setNegativeButton(R.string.dialog_cancel, null).setPositiveButton(R.string.dialog_ok, null).create()
        var validating: Job? = null
        dialog.setOnDismissListener { validating?.cancel() }
        if (singleInput != null) dialog.showNameInput(singleInput, selectStem = !pairs.single().first.isDir)
        else dialog.show()
        val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        confirm.setOnClickListener {
            if (validating?.isActive == true) return@setOnClickListener
            val chosen = names.mapValues { it.value.trim() }
            confirm.isEnabled = false
            validating = owner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { DuplicateEntries.validate(context, pairs, chosen) }
                    if (dialog.isShowing) {
                        onConfirmed(chosen)
                        dialog.dismiss()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error.text = failure.message ?: context.getString(R.string.err_failed)
                    error.visibility = View.VISIBLE
                } finally {
                    confirm.isEnabled = true
                }
            }
        }
    }
}
