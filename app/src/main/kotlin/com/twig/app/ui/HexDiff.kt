package com.twig.app.ui

import java.io.InputStream

/** A differing byte range, absolute file offsets: [start] inclusive, [end] exclusive. */
data class DiffRange(val start: Long, val end: Long)

/**
 * Byte-level comparison for [HexCompareActivity]: **same offset against same offset**, no realignment.
 *
 * That is a deliberate limitation, not an oversight. Realigning after an insertion needs block-level
 * resynchronisation (rolling hash over a sliding window), which is a different order of both algorithm
 * and UI — and the case this page is actually for is "the same structure, which bytes changed":
 * firmware, headers, patched binaries, two dumps of one record. Those keep their offsets. When one side
 * really has bytes inserted, every following byte reads as different, which is honest: the files no
 * longer line up, and the page says so rather than inventing an alignment.
 *
 * Two things make the result usable rather than a wall of red:
 *  - Differences closer together than [MERGE_GAP] merge into one range, so "next difference" steps
 *    between meaningful sites instead of through every individual byte of a changed field.
 *  - The scan stops at [MAX_RANGES] and reports [Result.truncated]; a file pair that differs everywhere
 *    would otherwise build a list with millions of entries and take the process down with it.
 *
 * Reading is **sequential** through [InputStream], never seeking: for sources where seeking means
 * "reopen and skip" (FTP / SFTP / archive entries), one pass over both sides is the only affordable way.
 */
object HexDiff {

    /** Differences separated by fewer than this many equal bytes are treated as one site. */
    const val MERGE_GAP = 8L

    /** Upper bound on ranges kept; beyond this the two files are "different everywhere" and a list adds nothing. */
    const val MAX_RANGES = 20_000

    private const val BUF = 64 * 1024

    /** Progress is reported at most every this many bytes, to keep the UI callback off the hot loop. */
    private const val PROGRESS_STEP = 1L shl 20

    class Result(
        val ranges: List<DiffRange>,
        /** Hit [MAX_RANGES] and stopped early: there are more differences past the last range. */
        val truncated: Boolean,
        /** Scanning was cancelled (the page went away, or the user backed out). */
        val cancelled: Boolean,
        /** Bytes actually compared — where the shorter side ended. */
        val compared: Long,
    ) {
        val identical: Boolean get() = ranges.isEmpty() && !truncated && !cancelled
    }

    /**
     * Collects differing byte positions into merged ranges. Split out from [scan] because the merging
     * (and its interaction with the cap) is the part worth testing directly, without any streams.
     */
    class Merger(private val gap: Long = MERGE_GAP, private val limit: Int = MAX_RANGES) {
        private val out = ArrayList<DiffRange>()
        private var start = -1L
        private var end = -1L

        /** True once the cap is reached; the caller should stop scanning. */
        var full = false
            private set

        /** Add a differing byte at [pos]. Returns false when the cap is reached. */
        fun add(pos: Long): Boolean = addRange(pos, pos + 1)

        /** Add a whole differing range `[s, e)`. Returns false when the cap is reached. */
        fun addRange(s: Long, e: Long): Boolean {
            if (full || e <= s) return !full
            if (start < 0) {
                start = s
                end = e
                return true
            }
            if (s - end <= gap) {
                end = maxOf(end, e)
                return true
            }
            out.add(DiffRange(start, end))
            if (out.size >= limit) {
                // The open range is dropped on purpose: it would be range limit+1, past the cap
                start = -1
                full = true
                return false
            }
            start = s
            end = e
            return true
        }

        fun finish(): List<DiffRange> {
            if (start >= 0) {
                out.add(DiffRange(start, end))
                start = -1
            }
            return out
        }
    }

    /**
     * Compare two streams. [sizeA] / [sizeB] are the declared lengths, used only for the tail range when
     * one side is longer — the comparison itself trusts what it actually reads. [active] returning false
     * stops the scan (coroutine cancellation); [onProgress] is called with the bytes compared so far.
     *
     * Both streams are read to the end of the **shorter** one only; the tail of the longer side becomes a
     * single range, since "present on one side only" needs no per-byte comparison.
     */
    fun scan(
        a: InputStream,
        b: InputStream,
        sizeA: Long,
        sizeB: Long,
        active: () -> Boolean = { true },
        onProgress: (Long) -> Unit = {},
    ): Result {
        val ba = ByteArray(BUF)
        val bb = ByteArray(BUF)
        val merger = Merger()
        var pos = 0L
        var nextProgress = PROGRESS_STEP
        var cancelled = false

        loop@ while (true) {
            if (!active()) {
                cancelled = true
                break
            }
            val na = fill(a, ba)
            val nb = fill(b, bb)
            val n = minOf(na, nb)
            for (i in 0 until n) {
                if (ba[i] != bb[i] && !merger.add(pos + i)) break@loop
            }
            pos += n
            if (pos >= nextProgress) {
                onProgress(pos)
                nextProgress = pos + PROGRESS_STEP
            }
            // A short read means that side ended: everything past here exists on one side only
            if (na != nb || n < BUF) break
        }

        if (!cancelled && !merger.full) {
            val longer = maxOf(maxOf(sizeA, sizeB), pos)
            if (longer > pos) merger.addRange(pos, longer)
        }
        return Result(merger.finish(), merger.full, cancelled, pos)
    }

    /** Read until [buf] is full or the stream ends; `InputStream.read` is free to return less than asked. */
    private fun fill(s: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = s.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    /**
     * The ranges intersecting one row, converted to row-relative [HexHit]s. [current] is the index of the
     * range navigation sits on (-1 for none), so it can be painted in the stronger colour.
     *
     * [ranges] is sorted and non-overlapping, so the first candidate is found by binary search rather than
     * scanning from the start — with tens of thousands of ranges and a bind on every row, a linear search
     * here is felt as scroll stutter.
     */
    fun hitsInRow(ranges: List<DiffRange>, off: Long, len: Int, current: Int): List<HexHit> {
        if (ranges.isEmpty() || len <= 0) return emptyList()
        // First range that can intersect: the earliest whose end is past the row start
        var i = ranges.binarySearch { if (it.end <= off) -1 else 1 }
        if (i < 0) i = -i - 1
        val out = ArrayList<HexHit>(2)
        val rowEnd = off + len
        while (i < ranges.size && ranges[i].start < rowEnd) {
            val r = ranges[i]
            val s = maxOf(0L, r.start - off).toInt()
            val e = minOf(len.toLong(), r.end - off).toInt()
            if (e > s) out.add(HexHit(s, e, i == current))
            i++
        }
        return out
    }
}
