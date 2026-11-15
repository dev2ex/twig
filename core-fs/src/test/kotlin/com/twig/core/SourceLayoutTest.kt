package com.twig.core

import org.junit.Assert.*
import org.junit.Test

class SourceLayoutTest {
    private fun file(path: String, dir: Boolean = false, scheme: String = "file") = XFile(scheme, path, dir)

    @Test fun `paths begin at selected containing directories and never their ancestors`() {
        val a = file("/photos/trip/a.jpg")
        val b = file("/downloads/docs/b.pdf")
        assertEquals(listOf("trip/a.jpg", "docs/b.pdf"), SourceLayout.paths(listOf(a, b)).values.toList())
    }

    @Test fun `highest selected containing directory includes selected descendants`() {
        val a = file("/storage/emulated/0/photos/trip/a.jpg")
        val b = file("/storage/emulated/0/photos/trip/day2/b.jpg")
        val dir = file("/storage/emulated/0/photos/trip/day3", true)
        assertEquals(listOf("trip/a.jpg", "trip/day2/b.jpg", "trip/day3"),
            SourceLayout.paths(listOf(a, b, dir)).values.toList())
    }

    @Test fun `normalization removes duplicates and descendants independently of order`() {
        val dir = file("/photos/trip", true)
        val child = file("/photos/trip/a.jpg")
        val other = file("/photos/trip2/a.jpg")
        assertEquals(listOf(other, dir), SourceLayout.normalize(listOf(child, other, dir, child)))
        assertEquals(listOf("trip2/a.jpg", "trip"), SourceLayout.paths(listOf(other, dir)).values.toList())
    }

    @Test fun `same named source folders have distinct stable output prefixes`() {
        val sources = listOf(file("/photos/trip/a.jpg"), file("/backup/trip/a.jpg"), file("/photos/trip/a.jpg", scheme = "sftp1"))
        assertEquals(listOf("trip/a.jpg", "trip (2)/a.jpg", "trip (3)/a.jpg"), SourceLayout.paths(sources).values.toList())
    }

    @Test fun `opaque ancestry uses learned parent identities and display names`() {
        val root = file("content://provider/root%2Fid", true, "saf").copy(displayName = "Grant")
        val folder = file("content://provider/folder%2Fid", true, "saf").copy(displayName = "Photos")
        val child = file("content://provider/file%2Fid", scheme = "saf").copy(displayName = "a.jpg")
        val parents = mapOf(child.toUri() to folder, folder.toUri() to root)
        val parent: (XFile) -> XFile? = { parents[it.toUri()] }
        assertEquals("Photos/a.jpg", SourceLayout.paths(listOf(child), parent)[child.toUri()])
        assertEquals(listOf(folder), SourceLayout.normalize(listOf(child, folder), parent))
        assertNull(SourceLayout.lexicalParent(child))
    }

    @Test fun `root containing files do not produce absolute output paths`() {
        val a = file("/a.jpg")
        assertEquals("a.jpg", SourceLayout.paths(listOf(a))[a.toUri()])
        val b = file("/docs/b.pdf")
        assertEquals(listOf("a.jpg", "docs/b.pdf"), SourceLayout.paths(listOf(a, b)).values.toList())
    }

    @Test fun `relative output paths reject traversal empty segments and platform separators`() {
        for (path in listOf("/a", "../a", "a/../b", "a/./b", "a//b", "a/", "a\\b", "a\u0000b", "."))
            assertFalse(path, SourceLayout.isSafePath(path))
        assertTrue(SourceLayout.isSafePath("trip (2)/a.jpg"))
    }
}
