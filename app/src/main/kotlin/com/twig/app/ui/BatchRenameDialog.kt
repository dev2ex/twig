package com.twig.app.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isMutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

/** Name generation has no filesystem side effects; preview and execution use the same plan. */
internal object BatchRenameNames {
    enum class Part { NAME, EXTENSION, FULL }
    enum class LetterCase { KEEP, LOWER, UPPER, TITLE }

    private val titleWords = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*(?:[’'][\\p{L}\\p{M}\\p{N}]+)*")
    private val titleMinorWords = setOf(
        "a", "an", "the", "and", "but", "or", "nor", "for", "as", "at", "by", "down", "from",
        "in", "into", "like", "near", "of", "off", "on", "onto", "out", "over", "past", "per",
        "till", "to", "up", "upon", "via", "vs", "with",
    )

    /** English title casing with a fixed minor-word list, not grammatical analysis. */
    private fun titleCase(text: String): String {
        val words = titleWords.findAll(text).toList()
        fun subtitleBoundary(gap: String) = gap.contains(" - ") ||
            gap.any { it == ':' || it == '—' || it == '–' || it == '\n' || it == '\r' }
        var index = 0
        return titleWords.replace(text) { match ->
            val i = index++
            val word = match.value.lowercase(Locale.ROOT)
            val first = i == 0 || subtitleBoundary(text.substring(words[i - 1].range.last + 1, match.range.first))
            val last = i == words.lastIndex || subtitleBoundary(text.substring(match.range.last + 1, words[i + 1].range.first))
            if (first || last || word !in titleMinorWords) word.replaceFirstChar { it.titlecase(Locale.ROOT) }
            else word
        }
    }

    fun generate(
        files: List<XFile>, part: Part, numbering: Boolean, find: String, replacement: String,
        regex: Boolean, matchCase: Boolean, width: Int, start: String, numberFirst: Boolean,
        insertion: String? = null, offset: String = "0", fromEnd: Boolean = false, letterCase: LetterCase = LetterCase.KEEP,
        template: String? = null, overwrite: Boolean = false,
    ): List<String> {
        val pattern = if (!numbering && regex && find.isNotEmpty()) {
            Regex(find, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } else null
        val tokens = Regex("\\{([^{}]*)\\}")
        if (template != null) require(tokens.replace(template, "").none { it == '{' || it == '}' })
        val insertOffset = if (insertion != null) {
            require(offset.isNotEmpty() && offset.all { it in '0'..'9' })
            requireNotNull(offset.toIntOrNull())
        } else 0
        val usesNumber = numbering || template?.contains("{number}") == true
        val first = if (usesNumber) {
            require(start.isNotEmpty() && start.all { it in '0'..'9' })
            BigInteger(start)
        } else BigInteger.ZERO
        return files.mapIndexed { index, file ->
            val name = file.name
            val dot = if (file.isDir) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
            val stem = if (dot < 0) name else name.substring(0, dot)
            val extension = if (dot < 0) "" else name.substring(dot + 1)
            val old = when (part) { Part.NAME -> stem; Part.EXTENSION -> extension; Part.FULL -> name }
            fun number() = (first + BigInteger.valueOf(index.toLong())).toString().padStart(width, '0')
            if (template != null) {
                // One pass: tokens inside a filename are literal, never expanded again.
                return@mapIndexed tokens.replace(template) { token ->
                    when (token.groupValues[1]) {
                        "name" -> stem
                        "ext" -> if (dot < 0) "" else ".$extension"
                        "number" -> number()
                        "date" -> {
                            require(file.lastModified > 0)
                            SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(file.lastModified))
                        }
                        else -> error("Unknown rename token")
                    }
                }
            }
            val changed = if (insertion != null) {
                // Count Unicode code points so neither boundary splits a surrogate pair.
                val length = old.codePointCount(0, old.length)
                val distance = insertOffset.coerceAtMost(length)
                val index = if (fromEnd) length - distance else distance
                val position = old.offsetByCodePoints(0, index)
                val end = if (overwrite) old.offsetByCodePoints(position,
                    minOf(insertion.codePointCount(0, insertion.length), length - index)) else position
                old.substring(0, position) + insertion + old.substring(end)
            } else when (letterCase) {
                LetterCase.LOWER -> old.lowercase(Locale.ROOT)
                LetterCase.UPPER -> old.uppercase(Locale.ROOT)
                LetterCase.TITLE -> titleCase(old)
                LetterCase.KEEP -> when {
                    numbering -> {
                        val number = number()
                        if (old.isEmpty()) number else if (numberFirst) "$number - $old" else "$old - $number"
                    }
                    find.isEmpty() -> old
                    pattern != null -> pattern.replace(old, replacement)
                    else -> old.replace(find, replacement, ignoreCase = !matchCase)
                }
            }
            // Directories have no extension; extension-only leaves them unchanged.
            when (part) {
                Part.FULL -> changed
                Part.NAME -> changed + if (dot < 0) "" else ".$extension"
                Part.EXTENSION -> if (file.isDir) name else stem + if (changed.isEmpty()) "" else ".$changed"
            }
        }
    }

    fun valid(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }
}

internal object BatchRenameDialog {
    fun show(
        context: Context, owner: LifecycleOwner, files: List<XFile>,
        parentInTree: (XFile) -> XFile?, selectExtension: Boolean = false, onChanged: () -> Unit,
    ) {
        val dp = context.resources.displayMetrics.density
        fun px(n: Int) = (n * dp).toInt()
        fun color(id: Int) = ContextCompat.getColor(context, id)
        fun label(id: Int) = TextView(context).apply {
            setText(id); textSize = 12f; setTextColor(color(R.color.text_secondary))
            setPadding(0, px(8), 0, px(2))
        }
        fun heading(id: Int) = TextView(context).apply {
            setText(id); textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.action_text)); setPadding(0, 0, 0, px(4))
        }
        fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun spinner(ids: List<Int>) = Spinner(context).apply {
            minimumHeight = px(48)
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ids.map(context::getString))
        }
        fun divider(parent: LinearLayout) {
            parent.addView(View(context).apply { setBackgroundColor(color(R.color.divider)) },
                LinearLayout.LayoutParams(-1, px(1)).apply { topMargin = px(8); bottomMargin = px(8) })
        }
        val form = column().apply { setPadding(px(20), px(12), px(20), 0) }
        val method = spinner(listOf(R.string.batch_replace, R.string.batch_numbering,
            R.string.batch_insert_overwrite, R.string.batch_change_case, R.string.batch_template))
        val methodColumn = column()
        methodColumn.addView(heading(R.string.batch_method)); methodColumn.addView(method)
        val part = spinner(listOf(R.string.batch_name_only, R.string.batch_ext_only, R.string.batch_name_ext))
        if (selectExtension) part.setSelection(BatchRenameNames.Part.EXTENSION.ordinal)
        val partLabel = heading(R.string.batch_apply_to)
        val scopeColumn = column()
        scopeColumn.addView(partLabel); scopeColumn.addView(part)
        val topFields = NameColumns(context)
        methodColumn.setPadding(0, 0, px(8), 0)
        topFields.addView(methodColumn, LinearLayout.LayoutParams(0, -2))
        topFields.addView(scopeColumn, LinearLayout.LayoutParams(0, -2))
        form.addView(topFields)
        divider(form)
        form.addView(heading(R.string.batch_rules))
        val replaceBox = column()
        val find = EditText(context).apply { setHint(R.string.batch_find); setSingleLine() }
        val replacement = EditText(context).apply { setHint(R.string.batch_replacement); setSingleLine() }
        val regex = CheckBox(context).apply { setText(R.string.batch_regex); minHeight = px(48) }
        val matchCase = CheckBox(context).apply { setText(R.string.batch_match_case); minHeight = px(48) }
        val replaceFields = NameColumns(context)
        val findColumn = column().apply { setPadding(0, 0, px(8), 0) }
        findColumn.addView(label(R.string.batch_find)); findColumn.addView(find)
        val replacementColumn = column()
        replacementColumn.addView(label(R.string.batch_replacement)); replacementColumn.addView(replacement)
        replaceFields.addView(findColumn, LinearLayout.LayoutParams(0, -2))
        replaceFields.addView(replacementColumn, LinearLayout.LayoutParams(0, -2))
        replaceBox.addView(replaceFields)
        val checks = LinearLayout(context).apply { isBaselineAligned = false }
        checks.addView(regex, LinearLayout.LayoutParams(0, -2, 1f))
        checks.addView(matchCase, LinearLayout.LayoutParams(0, -2, 1f))
        replaceBox.addView(checks); form.addView(replaceBox)
        val numberBox = column()
        val width = Spinner(context).apply {
            minimumHeight = px(48)
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("1", "01", "001", "0001"))
        }
        val start = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); setText("1")
        }
        val order = Choices(context, listOf(R.string.batch_old_number, R.string.batch_number_old))
        val numberFields = NameColumns(context)
        val formatColumn = column().apply { setPadding(0, 0, px(8), 0) }
        formatColumn.addView(label(R.string.batch_number_format)); formatColumn.addView(width)
        val startColumn = column()
        startColumn.addView(label(R.string.batch_start)); startColumn.addView(start)
        numberFields.addView(formatColumn, LinearLayout.LayoutParams(0, -2))
        numberFields.addView(startColumn, LinearLayout.LayoutParams(0, -2))
        numberBox.addView(numberFields); numberBox.addView(order); form.addView(numberBox)
        val insertBox = column()
        val operation = Choices(context, listOf(R.string.batch_insert, R.string.batch_overwrite))
        val insertion = EditText(context).apply { setHint(R.string.batch_insert_text); setSingleLine() }
        val offset = EditText(context).apply {
            setHint(R.string.batch_insert_offset)
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); setText("0")
        }
        val direction = Choices(context, listOf(R.string.batch_insert_begin, R.string.batch_insert_end))
        insertBox.addView(operation)
        val insertFields = NameColumns(context)
        val textColumn = column().apply { setPadding(0, 0, px(8), 0) }
        textColumn.addView(label(R.string.batch_insert_text)); textColumn.addView(insertion)
        val offsetColumn = column()
        offsetColumn.addView(label(R.string.batch_insert_offset)); offsetColumn.addView(offset)
        insertFields.addView(textColumn, LinearLayout.LayoutParams(0, -2))
        insertFields.addView(offsetColumn, LinearLayout.LayoutParams(0, -2))
        insertBox.addView(insertFields)
        insertBox.addView(direction); form.addView(insertBox)
        val letterCase = spinner(listOf(R.string.batch_lowercase, R.string.batch_uppercase, R.string.batch_titlecase))
        form.addView(letterCase)
        val templateBox = column()
        val template = EditText(context).apply { setSingleLine(); setText("{name}{ext}") }
        templateBox.addView(template)
        templateBox.addView(label(R.string.batch_template_hint).apply { textSize = 12f })
        form.addView(templateBox)

        val previewBox = column().apply {
            setPadding(0, 0, 0, px(8))
        }
        previewBox.addView(heading(R.string.batch_preview))
        val summary = TextView(context).apply { textSize = 12f; setTextColor(color(R.color.text_secondary)) }
        previewBox.addView(summary)
        val status = TextView(context).apply {
            textSize = 12f; setTextColor(color(R.color.menu_icon_danger)); visibility = View.GONE
        }
        previewBox.addView(status)
        val header = NameColumns(context)
        header.addView(label(R.string.batch_before), LinearLayout.LayoutParams(0, -2))
        header.addView(label(R.string.batch_after), LinearLayout.LayoutParams(0, -2))
        previewBox.addView(header)
        val preview = PreviewAdapter(files, ::px)
        divider(form); form.addView(previewBox)
        val content = DialogContent(context, form, preview)
        val dialog = AlertDialog.Builder(context).setTitle(R.string.batch_rename).setView(content)
            .setNegativeButton(R.string.dialog_cancel, null).setPositiveButton(R.string.action_rename, null).create()
        var names = emptyList<String>()
        var busy = false
        fun update() {
            if (busy) return
            val mode = method.selectedItemPosition
            val numbering = mode == 1
            replaceBox.visibility = if (mode == 0) View.VISIBLE else View.GONE
            numberBox.visibility = if (numbering || mode == 4) View.VISIBLE else View.GONE
            order.visibility = if (numbering) View.VISIBLE else View.GONE
            insertBox.visibility = if (mode == 2) View.VISIBLE else View.GONE
            letterCase.visibility = if (mode == 3) View.VISIBLE else View.GONE
            templateBox.visibility = if (mode == 4) View.VISIBLE else View.GONE
            part.visibility = if (mode == 4) View.GONE else View.VISIBLE
            partLabel.visibility = part.visibility
            scopeColumn.visibility = part.visibility
            val result = runCatching {
                BatchRenameNames.generate(files, BatchRenameNames.Part.entries[part.selectedItemPosition], numbering,
                    if (mode == 0) find.text.toString() else "", replacement.text.toString(), regex.isChecked, matchCase.isChecked,
                    width.selectedItemPosition + 1, start.text.toString(), order.selectedIndex == 1,
                    insertion = if (mode == 2) insertion.text.toString() else null,
                    offset = offset.text.toString(), fromEnd = direction.selectedIndex == 1,
                    letterCase = if (mode == 3) BatchRenameNames.LetterCase.entries[letterCase.selectedItemPosition + 1]
                        else BatchRenameNames.LetterCase.KEEP,
                    template = if (mode == 4) template.text.toString() else null,
                    overwrite = operation.selectedIndex == 1)
            }
            names = result.getOrDefault(emptyList())
            preview.names = names; preview.notifyDataSetChanged()
            summary.text = context.getString(R.string.batch_preview_count,
                names.indices.count { names[it] != files[it].name }, files.size)
            val invalid = names.firstOrNull { !BatchRenameNames.valid(it) }
            status.text = when {
                result.isFailure -> context.getString(when (mode) {
                    2 -> R.string.batch_insert_invalid_offset
                    4 -> R.string.batch_template_error
                    else -> R.string.batch_invalid_rule
                })
                invalid != null -> context.getString(R.string.batch_invalid_name, invalid)
                else -> ""
            }
            status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = result.isSuccess && invalid == null &&
                names.indices.any { names[it] != files[it].name }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = update()
            override fun afterTextChanged(s: Editable?) {}
        }
        listOf(find, replacement, start, insertion, offset, template).forEach { it.addTextChangedListener(watcher) }
        listOf(part, method, width, letterCase).forEach {
            it.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = update()
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        listOf(order, direction, operation).forEach { it.setOnCheckedChangeListener { _, _ -> update() } }
        regex.setOnCheckedChangeListener { _, _ -> update() }
        matchCase.setOnCheckedChangeListener { _, _ -> update() }
        dialog.setOnShowListener {
            update()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val requestedNames = names.toList()
                // Capture tree parents on the UI thread; opaque document URIs cannot be sliced.
                val parents = HashMap<XFile, XFile?>()
                for (file in files) {
                    var entry: XFile? = file
                    while (entry != null && !parents.containsKey(entry)) {
                        val parent = parentInTree(entry)
                        parents[entry] = parent
                        entry = parent
                    }
                }
                fun enableInputs(group: ViewGroup, enabled: Boolean) {
                    for (i in 0 until group.childCount) {
                        val child = group.getChildAt(i)
                        child.isEnabled = enabled
                        if (child is ViewGroup) enableInputs(child, enabled)
                    }
                }
                busy = true
                dialog.setCancelable(false)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                enableInputs(form, false)
                status.visibility = View.VISIBLE
                status.setText(R.string.batch_working)
                owner.lifecycleScope.launch {
                    var completed = 0
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            execute(context, files, requestedNames, parents) { completed++ }
                        }
                    }
                    if (completed > 0) onChanged()
                    val message = result.fold(
                        onSuccess = { context.getString(R.string.batch_done, completed) },
                        onFailure = { context.getString(R.string.batch_failed, completed, it.message ?: context.getString(R.string.err_failed)) },
                    )
                    if (result.isFailure && completed == 0) {
                        busy = false
                        enableInputs(form, true)
                        dialog.setCancelable(true)
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        update()
                        status.text = message
                        status.visibility = View.VISIBLE
                    } else {
                        dialog.dismiss()
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    /** Preflight the entire selection before mutating, and report each completed rename. */
    internal fun execute(
        context: Context, files: List<XFile>, names: List<String>, parents: Map<XFile, XFile?>,
        onRenamed: () -> Unit,
    ) {
        require(files.size == names.size)
        check(names.all(BatchRenameNames::valid)) { context.getString(R.string.batch_invalid_rule) }
        val plan = files.zip(names).filter { (file, name) -> file.name != name }
        check(files.all { it.isMutable() }) { context.getString(R.string.err_failed) }
        val directories = files.associateWith { file ->
            parents[file] ?: FsRegistry.of(file).parentOf(file)
                ?: error(context.getString(R.string.err_failed))
        }
        val grouped = files.zip(names).groupBy { (file, _) ->
            directories.getValue(file).let { it.scheme to it.path }
        }
        // Reject duplicate destinations and existing names before the first mutation.
        for ((_, entries) in grouped) {
            val parent = directories.getValue(entries.first().first)
            val existing = FsRegistry.of(parent).list(parent).groupBy { it.name.lowercase(Locale.ROOT) }
            val destinations = HashSet<String>()
            for ((file, name) in entries) {
                check(destinations.add(name.lowercase(Locale.ROOT))) {
                    context.getString(R.string.batch_conflict, name)
                }
                if (name == file.name) continue
                check(existing[name.lowercase(Locale.ROOT)].orEmpty().none { it.path != file.path }) {
                    context.getString(R.string.batch_conflict, name)
                }
            }
        }
        // Renaming an ancestor would invalidate a selected descendant's handle.
        for (file in files) {
            var parent = parents[file] ?: parentInTreeFallback(file)
            val seen = HashSet<XFile>()
            while (parent != null && seen.add(parent)) {
                val ancestor = parent
                check(plan.none { it.first.scheme == ancestor.scheme && it.first.path == ancestor.path }) {
                    context.getString(R.string.batch_nested)
                }
                parent = parents[parent] ?: parentInTreeFallback(parent)
            }
        }
        for ((file, name) in plan) {
            FsRegistry.of(file).rename(file, name)
            onRenamed()
        }
    }

    private fun parentInTreeFallback(file: XFile): XFile? = FsRegistry.of(file).parentOf(file)

    private class PreviewAdapter(val files: List<XFile>, val px: (Int) -> Int) : RecyclerView.Adapter<PreviewAdapter.Holder>() {
        var names: List<String> = emptyList()
        class Holder(val row: LinearLayout, val before: TextView, val after: TextView) : RecyclerView.ViewHolder(row)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val row = NameColumns(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                setPadding(px(20), px(6), px(20), px(6))
            }
            fun cell() = TextView(parent.context).apply {
                gravity = Gravity.TOP or Gravity.START
                setPadding(0, 0, px(8), 0)
                setTextIsSelectable(true)
            }
            val before = cell(); val after = cell()
            row.addView(before, LinearLayout.LayoutParams(0, -2))
            row.addView(after, LinearLayout.LayoutParams(0, -2))
            return Holder(row, before, after)
        }
        override fun getItemCount() = files.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.before.text = files[position].name
            holder.before.setTextColor(ContextCompat.getColor(holder.row.context, R.color.text_primary))
            val name = names.getOrNull(position)
            val changed = name != null && name != files[position].name
            holder.after.text = name ?: ""
            holder.after.typeface = if (changed) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            holder.after.setTextColor(ContextCompat.getColor(holder.row.context, when {
                name != null && !BatchRenameNames.valid(name) -> R.color.menu_icon_danger
                changed -> R.color.action_text
                else -> R.color.text_primary
            }))
        }
    }

    /** Settings stay in place; only the preview rows consume the remaining scrollable area. */
    internal class DialogContent(context: Context, form: View, preview: RecyclerView.Adapter<*>) : LinearLayout(context) {
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = preview
            itemAnimator = null
            isVerticalScrollBarEnabled = true
        }

        init {
            orientation = VERTICAL
            addView(form, LayoutParams(-1, -2))
            addView(list, LayoutParams(-1, 0, 1f))
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val available = if (View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED)
                resources.displayMetrics.heightPixels else View.MeasureSpec.getSize(heightSpec)
            val height = minOf((600 * resources.displayMetrics.density).toInt(), available)
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        }
    }

    /** Standard radio choices with equal touch targets and room for wrapping labels. */
    internal class Choices(context: Context, labels: List<Int>) : RadioGroup(context) {
        val selectedIndex: Int get() = (0 until childCount).firstOrNull { getChildAt(it).id == checkedRadioButtonId } ?: 0

        init {
            orientation = HORIZONTAL
            isBaselineAligned = false
            val dp = resources.displayMetrics.density
            fun px(n: Int) = (n * dp).toInt()
            for ((index, label) in labels.withIndex()) {
                addView(RadioButton(context).apply {
                    id = View.generateViewId(); setText(label)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START; textSize = 14f
                    minHeight = px(48); setPadding(0, px(8), px(4), px(8))
                }, LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = px(8); topMargin = px(8) })
            }
            check(getChildAt(0).id)
        }
    }

    /** Equal columns even during the dialog's initial AT_MOST measurement. */
    internal class NameColumns(context: Context) : LinearLayout(context) {
        init {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            isBaselineAligned = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (childCount == 2 && View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
                val width = (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
                getChildAt(0).layoutParams.width = width / 2
                getChildAt(1).layoutParams.width = width - width / 2
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
