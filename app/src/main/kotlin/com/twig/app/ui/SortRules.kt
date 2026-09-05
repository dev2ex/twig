package com.twig.app.ui

import com.twig.core.XFile

/**
 * The list's **grouped sorting rules**.
 *
 * Extracted from [PaneViewModel.sortList] so it can be covered by plain JVM unit
 * tests: the original would read `Prefs` (SharedPreferences) and call
 * `Thumbs.canThumb` (the `Thumbs` object has an `android.util.LruCache` in its
 * initialization, so a plain JVM test in :app loads as `Stub!`).
 * Here "should we group" / "is it an expandable archive" / "can it produce a
 * thumbnail" are all turned into **parameters**, so the decision logic has no
 * Android dependency; [PaneViewModel] still decides where to ask those switches.
 */
object SortRules {

    /**
     * Which group an entry belongs to. Lower number sorts earlier:
     *
     * | Group | Contents | Why |
     * |---|---|---|
     * | 0 | Directories | Standard for a file manager. |
     * | 1 | Expandable archives (only when [archivesFirst]) | They render as full rows in grid "All files"; wedged between cells, they would split the grid. |
     * | 2 | Files that can produce a thumbnail | With thumbnails on, mixed image/text rows are hard to scan; grouping them feels more like a gallery. |
     * | 3 | Everything else | |
     */
    fun groupOf(
        isDir: Boolean,
        isExpandableArchive: Boolean,
        canThumb: Boolean,
        archivesFirst: Boolean,
    ): Int = when {
        isDir -> 0
        archivesFirst && isExpandableArchive -> 1
        canThumb -> 2
        else -> 3
    }

    /**
     * Sort by [cmp]; when [groupOf] is non-null, group first then sort within group
     * by [cmp] (pass null for no grouping, matching the long-standing behavior when
     * both thumbnails and the grid are off).
     *
     * **Each entry's group is computed exactly once.** The old version computed it
     * inside the Comparator — that's O(n log n) times, while the group only depends
     * on the entry itself and has nothing to do with who it's compared against.
     * `canThumb` even contains a `split('/')`, so a directory of a few thousand
     * entries paid for ten thousand extra splits.
     */
    fun sorted(list: List<XFile>, cmp: Comparator<XFile>, groupOf: ((XFile) -> Int)?): List<XFile> {
        if (groupOf == null) return list.sortedWith(cmp)
        return list.map { it to groupOf(it) }
            .sortedWith(
                Comparator { a, b ->
                    val g = a.second - b.second
                    if (g != 0) g else cmp.compare(a.first, b.first)
                },
            )
            .map { it.first }
    }
}
