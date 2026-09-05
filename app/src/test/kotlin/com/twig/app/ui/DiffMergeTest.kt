package com.twig.app.ui

import com.twig.git.Diff
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The logic behind "move this diff block to the other side". A merge result gets **written
 * back to the real file**, so getting even one spot wrong turns "edit one block" into
 * "corrupt the whole file" — which is why even invisible bytes like line endings and a
 * trailing newline are pinned down here.
 */
class DiffMergeTest {

    private fun rowsOf(left: String, right: String) = Diff.rows(toLines(left), toLines(right))

    /** Walk through the actual on-screen flow: split into lines -> diff -> merge block [block] -> join back into text. */
    private fun merge(left: String, right: String, toRight: Boolean, block: Int = 0): String {
        val rows = rowsOf(left, right)
        val starts = rows.indices.filter { rows[it].changed && (it == 0 || !rows[it - 1].changed) }
        val range = blockRange(rows, starts[block])
        val target = if (toRight) right else left
        return joinLines(mergedLines(rows, range, toRight), target.endsWith("\n"))
    }

    @Test
    fun `splitting into lines and joining back are strict inverses`() {
        // missing even one would turn "only one block changed" into "the whole file moved"
        for (t in listOf("", "a", "a\n", "a\nb", "a\nb\n", "\n", "\n\n", "a\n\n", "a\r\nb\r\n", "a\r\nb")) {
            assertEquals(t, joinLines(toLines(t), t.endsWith("\n")))
        }
    }

    @Test
    fun `CRLF needs no line-ending detection, the carriage return already stays in the line`() {
        // splitting on '\n' leaves CRLF's \r at the end of each line; joining on '\n' again reproduces it exactly.
        // Trimming the \r before joining would silently rewrite the whole file's line endings to LF
        assertEquals(listOf("a\r", "b\r"), toLines("a\r\nb\r\n"))
        assertEquals("a\r\nb\r\n", joinLines(listOf("a\r", "b\r"), finalNewline = true))
    }

    @Test
    fun `a file with no trailing newline does not get one added`() {
        // conjuring up a newline out of nowhere is, in git, a change to the file's last line
        assertEquals("x\ny", merge(left = "x\ny", right = "x\nz", toRight = true))
        // the destination side has no trailing newline while the source does; the source's must not be carried over either
        assertEquals("x\nz", merge(left = "x\ny", right = "x\nz\n", toRight = false))
    }

    @Test
    fun `moving a changed block to the right`() {
        assertEquals("a\nX\nc\n", merge(left = "a\nX\nc\n", right = "a\nY\nc\n", toRight = true))
    }

    @Test
    fun `moving a changed block to the left`() {
        assertEquals("a\nY\nc\n", merge(left = "a\nX\nc\n", right = "a\nY\nc\n", toRight = false))
    }

    @Test
    fun `an extra line on the left, moved over, becomes an addition`() {
        assertEquals("a\nb\nc\n", merge(left = "a\nb\nc\n", right = "a\nc\n", toRight = true))
    }

    @Test
    fun `a placeholder on the source side amounts to deleting those lines on the destination side`() {
        // the left has no b; moving this block to the right = the right's b disappears
        assertEquals("a\nc\n", merge(left = "a\nc\n", right = "a\nb\nc\n", toRight = true))
    }

    @Test
    fun `only the specified block is touched, other diffs are left exactly as they are`() {
        val left = "1\nA\n3\nB\n5\n"
        val right = "1\nx\n3\ny\n5\n"
        // merge the first block: A replaces x, the second block's y is untouched
        assertEquals("1\nA\n3\ny\n5\n", merge(left, right, toRight = true, block = 0))
        // merge the second block: the other way around
        assertEquals("1\nx\n3\nB\n5\n", merge(left, right, toRight = true, block = 1))
    }

    @Test
    fun `one whole block of adjacent changed lines is moved in a single go`() {
        val rows = rowsOf("1\nA\nB\n4\n", "1\nx\ny\n4\n")
        // A/B and x/y pair up into two "modified" lines, which are adjacent and belong to the same block
        assertEquals(1..2, blockRange(rows, 1))
        assertEquals("1\nA\nB\n4\n", merge("1\nA\nB\n4\n", "1\nx\ny\n4\n", toRight = true))
    }

    @Test
    fun `merging every block over amounts to copying the whole file`() {
        var right = "a\nq\nc\nq\n"
        val left = "a\nb\nc\nd\n"
        // merge block by block, re-diffing after each merge — the block's line indices all shift
        repeat(2) {
            val rows = rowsOf(left, right)
            val start = rows.indices.first { rows[it].changed }
            right = joinLines(
                mergedLines(rows, blockRange(rows, start), toRight = true),
                finalNewline = true,
            )
        }
        assertEquals(left, right)
    }
}
