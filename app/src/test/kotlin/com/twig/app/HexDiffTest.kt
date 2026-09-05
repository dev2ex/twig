package com.twig.app

import com.twig.app.ui.DiffRange
import com.twig.app.ui.HexDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The byte comparison behind the hex compare page. Everything here is pure JVM — the scan takes two
 * streams and the merging takes plain positions, deliberately, because the parts that are easy to get
 * wrong (merging, the range cap, the tail when one side is longer, a difference straddling the read
 * buffer boundary) are invisible on screen: a missed difference just looks like two identical files.
 */
class HexDiffTest {

    private fun scan(a: ByteArray, b: ByteArray) =
        HexDiff.scan(ByteArrayInputStream(a), ByteArrayInputStream(b), a.size.toLong(), b.size.toLong())

    @Test
    fun `identical files produce no ranges`() {
        val a = ByteArray(1000) { (it % 251).toByte() }
        val r = scan(a, a.copyOf())
        assertTrue(r.identical)
        assertEquals(0, r.ranges.size)
        assertEquals(1000L, r.compared)
    }

    @Test
    fun `one changed byte is one range`() {
        val a = ByteArray(100)
        val b = a.copyOf().also { it[42] = 7 }
        val r = scan(a, b)
        assertEquals(listOf(DiffRange(42, 43)), r.ranges)
        assertFalse(r.identical)
    }

    @Test
    fun `differences within the merge gap become one site`() {
        val a = ByteArray(100)
        // 40 and 44 are 3 equal bytes apart, inside MERGE_GAP: one edited field, not two
        val b = a.copyOf().also { it[40] = 1; it[44] = 1 }
        assertEquals(listOf(DiffRange(40, 45)), scan(a, b).ranges)
    }

    @Test
    fun `differences beyond the merge gap stay separate`() {
        val a = ByteArray(100)
        val b = a.copyOf().also { it[10] = 1; it[10 + HexDiff.MERGE_GAP.toInt() + 2] = 1 }
        assertEquals(listOf(DiffRange(10, 11), DiffRange(20, 21)), scan(a, b).ranges)
    }

    @Test
    fun `the tail of the longer side is one range`() {
        val a = ByteArray(100)
        val b = ByteArray(150)
        val r = scan(a, b)
        assertEquals(listOf(DiffRange(100, 150)), r.ranges)
        assertEquals("only the shared prefix is compared byte by byte", 100L, r.compared)
    }

    @Test
    fun `a tail difference merges into a difference just before the end`() {
        val a = ByteArray(100).also { it[98] = 3 }
        val b = ByteArray(150)
        // The last differing byte and the tail are adjacent; they should read as one site, not two
        assertEquals(listOf(DiffRange(98, 150)), scan(a, b).ranges)
    }

    /**
     * A difference straddling the 64KB read buffer is the one case a naive "compare each buffer"
     * implementation gets wrong, and it needs a file bigger than one buffer to show up at all.
     */
    @Test
    fun `a difference past the first read buffer is found`() {
        val a = ByteArray(200_000) { (it % 97).toByte() }
        val b = a.copyOf().also { it[65_535] = 0; it[65_536] = 0; it[199_999] = 0 }
        val r = scan(a, b)
        assertEquals(listOf(DiffRange(65_535, 65_537), DiffRange(199_999, 200_000)), r.ranges)
    }

    @Test
    fun `the range cap stops the scan and is reported`() {
        val m = HexDiff.Merger(limit = 3)
        assertTrue(m.add(0))
        assertTrue(m.add(100))
        assertTrue(m.add(200))
        assertFalse("the fourth site hits the cap", m.add(300))
        assertTrue(m.full)
        assertEquals(listOf(DiffRange(0, 1), DiffRange(100, 101), DiffRange(200, 201)), m.finish())
    }

    @Test
    fun `cancellation is reported instead of a bogus identical result`() {
        val a = ByteArray(300_000)
        val b = ByteArray(300_000)
        var calls = 0
        val r = HexDiff.scan(
            ByteArrayInputStream(a), ByteArrayInputStream(b), a.size.toLong(), b.size.toLong(),
            active = { calls++ < 2 },
        )
        assertTrue(r.cancelled)
        assertFalse("cancelled must never read as identical", r.identical)
    }

    // ---- row slicing ----

    private val ranges = listOf(DiffRange(10, 20), DiffRange(64, 65), DiffRange(300, 301))

    @Test
    fun `a range is sliced to the rows it covers`() {
        val first = HexDiff.hitsInRow(ranges, 0, 16, current = -1)
        assertEquals(1, first.size)
        assertEquals(10, first[0].start)
        assertEquals(16, first[0].end)

        val second = HexDiff.hitsInRow(ranges, 16, 16, current = -1)
        assertEquals(1, second.size)
        assertEquals("the row-relative start of a range that began earlier", 0, second[0].start)
        assertEquals(4, second[0].end)
    }

    @Test
    fun `rows with no difference get no hits`() {
        assertTrue(HexDiff.hitsInRow(ranges, 32, 16, current = -1).isEmpty())
        assertTrue(HexDiff.hitsInRow(ranges, 400, 16, current = -1).isEmpty())
        assertTrue(HexDiff.hitsInRow(emptyList(), 0, 16, current = 0).isEmpty())
    }

    @Test
    fun `only the current range is marked current`() {
        assertTrue(HexDiff.hitsInRow(ranges, 64, 16, current = 1).single().current)
        assertFalse(HexDiff.hitsInRow(ranges, 64, 16, current = 0).single().current)
    }
}
