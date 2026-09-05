package com.twig.app.ui

import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SortRules]: grouped sorting for the list.
 *
 * Getting it wrong does not crash anything, it just produces "images not clustered
 * together" or "an archive lands in the middle of the grid, splitting it" -- the kind of
 * thing that looks off at a glance but is hard to pin down in words, exactly the sort of
 * problem assertions are good at nailing down.
 */
class SortRulesTest {

    private fun dir(name: String) = XFile("file", "/$name", isDir = true)
    private fun file(name: String) = XFile("file", "/$name", isDir = false)

    /** Sorted by name, unrelated to the real SortSpec used in the app -- this only verifies grouping and "still ordered by cmp within a group". */
    private val byName = Comparator<XFile> { a, b -> a.name.compareTo(b.name) }

    private fun names(list: List<XFile>) = list.map { it.name }

    // ---- groupOf lookup table ----

    @Test
    fun directoriesAlwaysFirst() {
        // a directory ignores the later conditions, always 0
        assertEquals(0, SortRules.groupOf(isDir = true, isExpandableArchive = true, canThumb = true, archivesFirst = true))
        assertEquals(0, SortRules.groupOf(isDir = true, isExpandableArchive = false, canThumb = false, archivesFirst = false))
    }

    @Test
    fun expandableArchivesOnlySplitOutWhenArchivesFirst() {
        // grid "all files" mode: archives get their own group, right after directories
        assertEquals(1, SortRules.groupOf(isDir = false, isExpandableArchive = true, canThumb = false, archivesFirst = true))
        // every other mode: an archive is just a plain row, no group of its own
        assertEquals(3, SortRules.groupOf(isDir = false, isExpandableArchive = true, canThumb = false, archivesFirst = false))
    }

    @Test
    fun thumbablesComeBeforePlainFiles() {
        assertEquals(2, SortRules.groupOf(isDir = false, isExpandableArchive = false, canThumb = true, archivesFirst = false))
        assertEquals(3, SortRules.groupOf(isDir = false, isExpandableArchive = false, canThumb = false, archivesFirst = false))
    }

    /** When something is both an expandable archive and thumbnailable (an apk is exactly this), it joins the archive group under archivesFirst. */
    @Test
    fun archiveBranchWinsOverThumbBranch() {
        assertEquals(1, SortRules.groupOf(isDir = false, isExpandableArchive = true, canThumb = true, archivesFirst = true))
        assertEquals(2, SortRules.groupOf(isDir = false, isExpandableArchive = true, canThumb = true, archivesFirst = false))
    }

    // ---- sorted ----

    @Test
    fun withoutGroupingItIsJustTheComparator() {
        val list = listOf(file("b.txt"), dir("z"), file("a.jpg"))
        assertEquals(listOf("a.jpg", "b.txt", "z"), names(SortRules.sorted(list, byName, null)))
    }

    @Test
    fun groupsInOrderAndSortsWithinEachGroup() {
        val list = listOf(
            file("zz.txt"), // group 3
            file("b.jpg"), // group 2
            dir("m"), // group 0
            file("a.zip"), // group 1
            dir("a"), // group 0
            file("a.txt"), // group 3
            file("a.jpg"), // group 2
        )
        val sorted = SortRules.sorted(list, byName) { f ->
            SortRules.groupOf(
                isDir = f.isDir,
                isExpandableArchive = f.name.endsWith(".zip"),
                canThumb = f.name.endsWith(".jpg"),
                archivesFirst = true,
            )
        }
        assertEquals(
            listOf("a", "m", "a.zip", "a.jpg", "b.jpg", "a.txt", "zz.txt"),
            names(sorted),
        )
    }

    /** Keeps the original order when the group is the same and the comparator considers items equal (stable sort). */
    @Test
    fun equalItemsKeepOriginalOrder() {
        val list = listOf(file("x1"), file("x2"), file("x3"))
        val allEqual = Comparator<XFile> { _, _ -> 0 }
        assertEquals(
            listOf("x1", "x2", "x3"),
            names(SortRules.sorted(list, allEqual) { 3 }),
        )
    }

    /**
     * Each item's group is computed **only once**. It used to be computed on the fly inside
     * the Comparator (O(n log n) times), and canThumb does a `split('/')`, so a directory
     * with a few thousand entries would run it tens of thousands of times for nothing.
     */
    @Test
    fun groupIsComputedOncePerItem() {
        val list = (1..64).map { file("f$it.txt") }
        var calls = 0
        SortRules.sorted(list, byName) { calls++; 3 }
        assertEquals(64, calls)
    }

    @Test
    fun emptyAndSingleton() {
        assertEquals(emptyList<String>(), names(SortRules.sorted(emptyList(), byName) { 0 }))
        assertEquals(listOf("a"), names(SortRules.sorted(listOf(file("a")), byName) { 0 }))
    }
}
