package com.twig.app

import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Test

class SortSpecTest {
    private val entries = listOf(
        XFile("file", "/z.txt", false),
        XFile("file", "/Beta", true, lastModified = 10),
        XFile("file", "/Gamma", true, lastModified = 30),
        XFile("file", "/a.txt", false),
        XFile("file", "/alpha", true, lastModified = 20),
    )

    @Test
    fun `four folder orders stay independent of file reversal and keep folders first`() {
        val expected = mapOf(
            SortSpec() to listOf("alpha", "Beta", "Gamma"),
            SortSpec(folderReversed = true) to listOf("Gamma", "Beta", "alpha"),
            SortSpec(folderBy = FolderSortKey.DATE) to listOf("Beta", "alpha", "Gamma"),
            SortSpec(folderBy = FolderSortKey.DATE, folderReversed = true) to listOf("Gamma", "alpha", "Beta"),
        )
        for ((spec, folders) in expected) for (reversed in listOf(false, true)) {
            val files = if (reversed) listOf("z.txt", "a.txt") else listOf("a.txt", "z.txt")
            assertEquals("$spec, file reversal=$reversed", folders + files,
                spec.copy(reversed = reversed).sort(entries).map { it.name })
        }
    }

    @Test
    fun `files and folders use natural name order in both directions`() {
        val names = listOf(
            "2", "02", "002",
            "2 xxx", "2-xxx", "2.xxx", "2_xxx",
            "10", "12 xxx", "12-xxx", "12.xxx", "12_xxx",
            "99999999999999999999", "100000000000000000000",
            "S01E2.mkv", "s1e10.mkv", "S02E1.mkv", "第2章", "第12章",
        )
        val entries = names.flatMapIndexed { i, name ->
            listOf(XFile("file", "/files/$name", false), XFile("saf", "id$i", true, displayName = name))
        }.shuffled(kotlin.random.Random(74))

        assertEquals(names + names, SortSpec().sort(entries).map { it.name })
        assertEquals(names.reversed() + names, SortSpec(folderReversed = true)
            .sort(entries).map { it.name })
        assertEquals(names + names.reversed(), SortSpec(reversed = true).sort(entries).map { it.name })
    }

    @Test
    fun `alphabetical order can be selected independently for files and folders`() {
        val names = listOf("1.2", "1.12", "IMG_2", "img_12")
        val entries = names.flatMap { name ->
            listOf(XFile("file", "/file/$name", false), XFile("file", "/folder/$name", true))
        }
        val alphabetical = listOf("1.12", "1.2", "img_12", "IMG_2")
        val filesAlphabetical = SortSpec(by = FileSortKey.NAME_ALPHABETICAL)
        val foldersAlphabetical = SortSpec(folderBy = FolderSortKey.NAME_ALPHABETICAL, folderReversed = true)
        assertEquals(names + alphabetical, filesAlphabetical.sort(entries).map { it.name })
        assertEquals(alphabetical.reversed() + names, foldersAlphabetical.sort(entries).map { it.name })
    }

    @Test
    fun `extension sorting orders names naturally within each extension`() {
        val names = listOf("img_12.png", "img_12.jpg", "img_2.png", "IMG_2.JPG")
        val files = names.map { XFile("file", "/$it", false) }
        assertEquals(listOf("IMG_2.JPG", "img_12.jpg", "img_2.png", "img_12.png"),
            SortSpec(by = FileSortKey.EXT).sort(files).map { it.name })
    }
}
