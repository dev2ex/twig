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
            FolderSortKey.NAME to listOf("alpha", "Beta", "Gamma"),
            FolderSortKey.NAME_REVERSED to listOf("Gamma", "Beta", "alpha"),
            FolderSortKey.DATE_OLD to listOf("Beta", "alpha", "Gamma"),
            FolderSortKey.DATE_NEW to listOf("Gamma", "alpha", "Beta"),
        )
        for ((folderBy, folders) in expected) for (reversed in listOf(false, true)) {
            val files = if (reversed) listOf("z.txt", "a.txt") else listOf("a.txt", "z.txt")
            assertEquals("folder=$folderBy, file reversal=$reversed", folders + files,
                SortSpec(reversed = reversed, folderBy = folderBy).sort(entries).map { it.name })
        }
    }
}
