package com.twig.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffTest {

    private fun render(rows: List<Diff.Row>): List<String> = rows.map {
        val l = it.left ?: "·"
        val r = it.right ?: "·"
        if (it.changed) "[$l|$r]" else "=$l"
    }

    @Test
    fun `modification aligns with insertions and deletions`() {
        val old = listOf("a", "b", "c", "d")
        val new = listOf("a", "B", "d", "e")
        val rows = Diff.rows(old, new)
        // a is context; b/c pair with B plus a placeholder; d is context; e is an insertion
        assertEquals("=a", render(rows)[0])
        assertTrue(render(rows).contains("[b|B]"))
        assertTrue(render(rows).contains("[c|·]"))
        assertEquals("=d", render(rows)[3])
        assertEquals("[·|e]", render(rows)[4])
        // line numbers line up
        assertEquals(4, rows[3].leftNo)
        assertEquals(3, rows[3].rightNo)
    }

    @Test
    fun `fully identical and fully different`() {
        assertTrue(Diff.rows(listOf("x"), listOf("x")).single().let { !it.changed })
        val rows = Diff.rows(listOf("1", "2"), listOf("3"))
        assertTrue(rows.all { it.changed })
        assertEquals(2, rows.size)
    }

    @Test
    fun `empty file`() {
        assertEquals(2, Diff.rows(emptyList(), listOf("a", "b")).size)
        assertEquals(0, Diff.rows(emptyList(), emptyList()).size)
    }

    @Test
    fun `duplicate lines are located via anchors`() {
        val old = listOf("{", "x", "}", "{", "y", "}")
        val new = listOf("{", "x", "}", "{", "z", "}")
        val rows = Diff.rows(old, new)
        assertEquals(6, rows.size)
        assertEquals(1, rows.count { it.changed })
        assertTrue(render(rows).contains("[y|z]"))
    }
}
