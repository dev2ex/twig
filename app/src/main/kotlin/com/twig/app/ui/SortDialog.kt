package com.twig.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.twig.app.DirectoryView
import com.twig.app.DirectoryViewStore
import com.twig.app.FileSortKey
import com.twig.app.Format
import com.twig.app.FolderSortKey
import com.twig.app.Prefs
import com.twig.app.SortSpec
import com.twig.app.R
import com.twig.app.databinding.DialogSortBinding
import com.twig.core.XFile

/** Default and directory-specific sorting/view settings, shared with the destination picker. */
object SortDialog {
    fun show(activity: AppCompatActivity, directory: XFile?,
        parentOf: (XFile) -> XFile? = DirectoryViewStore::pathParent, onChanged: () -> Unit) =
        showOptions(activity, directory, viewOnly = false, parentOf, onChanged)

    fun showView(activity: AppCompatActivity, directory: XFile?,
        parentOf: (XFile) -> XFile? = DirectoryViewStore::pathParent, onChanged: () -> Unit) =
        showOptions(activity, directory, viewOnly = true, parentOf, onChanged)

    private fun showOptions(activity: AppCompatActivity, directory: XFile?, viewOnly: Boolean,
        parentOf: (XFile) -> XFile?, onChanged: () -> Unit) {
        val views = DirectoryViewStore.load(activity)
        val inherited = DirectoryViewStore.resolve(views, directory, includeOwn = false, parentOf = parentOf)
        val d = DialogSortBinding.inflate(activity.layoutInflater)
        val drafts = arrayOf(
            DirectoryView(SortSpec.load(activity), Prefs.thumbsGrid(activity)),
            DirectoryViewStore.get(views, directory) ?: DirectoryView(),
        )
        d.sortOptions.visibility = if (viewOnly) View.GONE else View.VISIBLE
        d.viewOptions.visibility = if (viewOnly) View.VISIBLE else View.GONE
        var scope = 0
        var filling = false
        lateinit var dialog: AlertDialog

        fun enable(view: View, enabled: Boolean) {
            view.isEnabled = enabled
            if (view is ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i), enabled)
        }
        fun enableOptions() {
            val sort = scope == 0 || d.cbCustomSort.isChecked
            enable(d.rgBy, sort)
            enable(d.rgFolder, sort)
            d.cbReversed.isEnabled = sort
            enable(d.rgView, scope == 0 || d.cbCustomView.isChecked)
            d.cbRecursive.isEnabled = if (viewOnly) d.cbCustomView.isChecked else d.cbCustomSort.isChecked
        }
        fun read(): DirectoryView = drafts[scope].copy(
            sort = if (viewOnly) drafts[scope].sort else if (scope == 0 || d.cbCustomSort.isChecked) SortSpec(
                by = when (d.rgBy.checkedRadioButtonId) {
                    R.id.rb_size -> FileSortKey.SIZE
                    R.id.rb_ext -> FileSortKey.EXT
                    R.id.rb_date -> FileSortKey.DATE
                    else -> FileSortKey.NAME
                },
                reversed = d.cbReversed.isChecked,
                folderBy = when (d.rgFolder.checkedRadioButtonId) {
                    R.id.rf_name_reversed -> FolderSortKey.NAME_REVERSED
                    R.id.rf_date_old -> FolderSortKey.DATE_OLD
                    R.id.rf_date_new -> FolderSortKey.DATE_NEW
                    else -> FolderSortKey.NAME
                },
            ) else null,
            gridMode = if (!viewOnly) drafts[scope].gridMode else if (scope == 0 || d.cbCustomView.isChecked) when (d.rgView.checkedRadioButtonId) {
                R.id.view_media -> 1
                R.id.view_all -> 2
                else -> 0
            } else null,
            sortRecursive = if (viewOnly) drafts[scope].sortRecursive
                else scope == 1 && d.cbCustomSort.isChecked && d.cbRecursive.isChecked,
            viewRecursive = if (!viewOnly) drafts[scope].viewRecursive
                else scope == 1 && d.cbCustomView.isChecked && d.cbRecursive.isChecked,
        )
        fun fill() {
            filling = true
            val cur = drafts[scope]
            val sort = cur.sort ?: inherited.sort ?: requireNotNull(drafts[0].sort)
            d.rgBy.check(when (sort.by) {
                FileSortKey.NAME -> R.id.rb_name
                FileSortKey.SIZE -> R.id.rb_size
                FileSortKey.EXT -> R.id.rb_ext
                FileSortKey.DATE -> R.id.rb_date
            })
            d.cbReversed.isChecked = sort.reversed
            d.rgFolder.check(when (sort.folderBy) {
                FolderSortKey.NAME -> R.id.rf_name
                FolderSortKey.NAME_REVERSED -> R.id.rf_name_reversed
                FolderSortKey.DATE_OLD -> R.id.rf_date_old
                FolderSortKey.DATE_NEW -> R.id.rf_date_new
            })
            d.rgView.check(when (cur.gridMode ?: inherited.gridMode ?: drafts[0].gridMode) {
                1 -> R.id.view_media
                2 -> R.id.view_all
                else -> R.id.view_list
            })
            d.cbCustomSort.isChecked = cur.sort != null
            d.cbCustomView.isChecked = cur.gridMode != null
            val visibility = if (scope == 1) View.VISIBLE else View.GONE
            d.cbRecursive.visibility = visibility
            d.cbRecursive.isChecked = if (viewOnly) cur.viewRecursive else cur.sortRecursive
            d.directoryPath.visibility = visibility
            d.cbCustomSort.visibility = if (scope == 1 && !viewOnly) View.VISIBLE else View.GONE
            d.cbCustomView.visibility = if (scope == 1 && viewOnly) View.VISIBLE else View.GONE
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = visibility
            enableOptions()
            filling = false
        }

        d.scopeDirectory.isEnabled = directory != null
        d.directoryPath.text = directory?.let { Format.pathLabel(it) }
        d.rgScope.check(R.id.scope_default)
        dialog = AlertDialog.Builder(activity)
            .setTitle(if (viewOnly) R.string.action_view_mode else R.string.action_sort)
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(R.string.directory_view_reset, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                drafts[scope] = read()
                if (viewOnly) Prefs.setThumbsGrid(activity, requireNotNull(drafts[0].gridMode))
                else SortSpec.save(activity, requireNotNull(drafts[0].sort))
                directory?.let { DirectoryViewStore.save(activity, it, drafts[1]) }
                onChanged()
            }
            .create()
        d.rgScope.setOnCheckedChangeListener { _, checked ->
            drafts[scope] = read()
            scope = if (checked == R.id.scope_directory) 1 else 0
            fill()
        }
        d.cbCustomSort.setOnCheckedChangeListener { _, _ ->
            if (!filling) { drafts[scope] = read(); fill() }
        }
        d.cbCustomView.setOnCheckedChangeListener { _, _ ->
            if (!filling) { drafts[scope] = read(); fill() }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            drafts[1] = if (viewOnly) drafts[1].copy(gridMode = null, viewRecursive = false)
                else drafts[1].copy(sort = null, sortRecursive = false)
            fill()
        }
        fill()
    }
}
