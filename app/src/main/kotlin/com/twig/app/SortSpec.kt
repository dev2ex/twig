package com.twig.app

import android.content.Context
import com.twig.core.XFile

/** File sort keys. */
enum class FileSortKey { NAME, SIZE, EXT, DATE }

/** Folder sort keys (independent of file keys, X-plore style). */
enum class FolderSortKey { NAME, DATE_OLD, DATE_NEW }

/**
 * Sort specification: folders always group first; files sort by [by] (+ [reversed]);
 * folders sort by [folderBy].
 */
data class SortSpec(
    val by: FileSortKey = FileSortKey.NAME,
    val reversed: Boolean = false,
    val folderBy: FolderSortKey = FolderSortKey.NAME,
) {
    fun comparator(): Comparator<XFile> {
        val fileBase: Comparator<XFile> = when (by) {
            FileSortKey.NAME -> compareBy { it.name.lowercase() }
            FileSortKey.SIZE -> compareBy { it.size }
            FileSortKey.EXT -> compareBy<XFile> { it.extension }.thenBy { it.name.lowercase() }
            FileSortKey.DATE -> compareBy { it.lastModified }
        }
        val fileCmp = if (reversed) fileBase.reversed() else fileBase
        val folderCmp: Comparator<XFile> = when (folderBy) {
            FolderSortKey.NAME -> compareBy { it.name.lowercase() }
            FolderSortKey.DATE_OLD -> compareBy { it.lastModified }
            FolderSortKey.DATE_NEW -> compareByDescending { it.lastModified }
        }
        return Comparator { a, b ->
            when {
                a.isDir != b.isDir -> if (a.isDir) -1 else 1 // folders first
                a.isDir -> folderCmp.compare(a, b)
                else -> fileCmp.compare(a, b)
            }
        }
    }

    fun sort(list: List<XFile>): List<XFile> = list.sortedWith(comparator())

    companion object {
        private const val FILE = "twig_sort"

        fun load(ctx: Context): SortSpec {
            val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return SortSpec(
                by = FileSortKey.entries.getOrElse(sp.getInt("by", 0)) { FileSortKey.NAME },
                reversed = sp.getBoolean("reversed", false),
                folderBy = FolderSortKey.entries.getOrElse(sp.getInt("folderBy", 0)) { FolderSortKey.NAME },
            )
        }

        fun save(ctx: Context, spec: SortSpec) {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("by", spec.by.ordinal)
                .putBoolean("reversed", spec.reversed)
                .putInt("folderBy", spec.folderBy.ordinal)
                .apply()
        }
    }
}
