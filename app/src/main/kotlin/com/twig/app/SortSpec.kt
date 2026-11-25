package com.twig.app

import android.content.Context
import com.twig.core.XFile

/** File sort keys. */
enum class FileSortKey { NAME, SIZE, EXT, DATE, NAME_ALPHABETICAL }

/** Folder sort keys are independent of file keys. */
enum class FolderSortKey { NAME, DATE, NAME_ALPHABETICAL }

/** Global preferences store ordinals, and directory overrides store names. Append new orders. */
internal enum class StoredFolderOrder(val key: FolderSortKey, val reversed: Boolean) {
    NAME(FolderSortKey.NAME, false),
    DATE_OLD(FolderSortKey.DATE, false),
    DATE_NEW(FolderSortKey.DATE, true),
    NAME_REVERSED(FolderSortKey.NAME, true),
    NAME_ALPHABETICAL(FolderSortKey.NAME_ALPHABETICAL, false),
    NAME_ALPHABETICAL_REVERSED(FolderSortKey.NAME_ALPHABETICAL, true),
}

/**
 * Sort specification: folders always group first; files sort by [by] (+ [reversed]);
 * folders sort by [folderBy] (+ [folderReversed]).
 */
data class SortSpec(
    val by: FileSortKey = FileSortKey.NAME,
    val reversed: Boolean = false,
    val folderBy: FolderSortKey = FolderSortKey.NAME,
    val folderReversed: Boolean = false,
) {
    internal val storedFolderOrder: StoredFolderOrder
        get() = StoredFolderOrder.entries.first { it.key == folderBy && it.reversed == folderReversed }

    fun comparator(dateOf: (XFile) -> Long = { it.lastModified }): Comparator<XFile> {
        val fileBase: Comparator<XFile> = when (by) {
            FileSortKey.NAME -> nameOrder
            FileSortKey.NAME_ALPHABETICAL -> alphabeticalNameOrder
            FileSortKey.SIZE -> compareBy { it.size }
            FileSortKey.EXT -> compareBy<XFile> { it.extension }.then(nameOrder)
            FileSortKey.DATE -> compareBy { dateOf(it) }
        }
        val fileCmp = if (reversed) fileBase.reversed() else fileBase
        val folderBase: Comparator<XFile> = when (folderBy) {
            FolderSortKey.NAME -> nameOrder
            FolderSortKey.NAME_ALPHABETICAL -> alphabeticalNameOrder
            FolderSortKey.DATE -> compareBy { dateOf(it) }
        }
        val folderCmp = if (folderReversed) folderBase.reversed() else folderBase
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
        private val nameOrder = compareBy<XFile, String>(NaturalNameOrder) { it.name.lowercase() }
        private val alphabeticalNameOrder = compareBy<XFile> { it.name.lowercase() }

        fun load(ctx: Context): SortSpec {
            val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val folder = StoredFolderOrder.entries.getOrElse(sp.getInt("folderBy", 0)) { StoredFolderOrder.NAME }
            return SortSpec(
                by = FileSortKey.entries.getOrElse(sp.getInt("by", 0)) { FileSortKey.NAME },
                reversed = sp.getBoolean("reversed", false),
                folderBy = folder.key,
                folderReversed = folder.reversed,
            )
        }

        fun save(ctx: Context, spec: SortSpec) {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("by", spec.by.ordinal)
                .putBoolean("reversed", spec.reversed)
                .putInt("folderBy", spec.storedFolderOrder.ordinal)
                .apply()
        }
    }
}
