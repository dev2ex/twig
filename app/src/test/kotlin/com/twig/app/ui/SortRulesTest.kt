package com.twig.app.ui

import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SortRules]:列表的分组排序。
 *
 * 排错了不会崩,只是"图片没聚到一起""压缩包跑到格子中间把网格切断"——
 * 属于看一眼觉得别扭、但很难说清哪里不对的那类问题,正适合用断言钉死。
 */
class SortRulesTest {

    private fun dir(name: String) = XFile("file", "/$name", isDir = true)
    private fun file(name: String) = XFile("file", "/$name", isDir = false)

    /** 按名字排,和实际用的 SortSpec 无关——这里只验分组与"组内仍按 cmp"。 */
    private val byName = Comparator<XFile> { a, b -> a.name.compareTo(b.name) }

    private fun names(list: List<XFile>) = list.map { it.name }

    // ---- groupOf 判定表 ----

    @Test
    fun directoriesAlwaysFirst() {
        // 目录不看后面几个条件,永远是 0
        assertEquals(0, SortRules.groupOf(isDir = true, isExpandableArchive = true, canThumb = true, archivesFirst = true))
        assertEquals(0, SortRules.groupOf(isDir = true, isExpandableArchive = false, canThumb = false, archivesFirst = false))
    }

    @Test
    fun expandableArchivesOnlySplitOutWhenArchivesFirst() {
        // 网格「全部文件」:压缩包单独成组,紧跟目录
        assertEquals(1, SortRules.groupOf(isDir = false, isExpandableArchive = true, canThumb = false, archivesFirst = true))
        // 其余模式:压缩包就是普通行,不单独成组
        assertEquals(3, SortRules.groupOf(isDir = false, isExpandableArchive = true, canThumb = false, archivesFirst = false))
    }

    @Test
    fun thumbablesComeBeforePlainFiles() {
        assertEquals(2, SortRules.groupOf(isDir = false, isExpandableArchive = false, canThumb = true, archivesFirst = false))
        assertEquals(3, SortRules.groupOf(isDir = false, isExpandableArchive = false, canThumb = false, archivesFirst = false))
    }

    /** 既是可展开压缩包又能出缩略图(apk 就是这样)时,archivesFirst 下归压缩包组。 */
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
            file("zz.txt"), // 3
            file("b.jpg"), // 2
            dir("m"), // 0
            file("a.zip"), // 1
            dir("a"), // 0
            file("a.txt"), // 3
            file("a.jpg"), // 2
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

    /** 分组相同、比较器判等时保持原始顺序(稳定排序)。 */
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
     * 每项的分组**只算一次**。原来是在 Comparator 里现算的(O(n log n) 次),
     * 而 canThumb 里带一次 split('/'),几千条目的目录会白白多跑上万次。
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
